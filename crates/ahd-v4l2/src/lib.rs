//! V4L2 ownership and DMA-BUF export for the Android native pipeline.
use ahd_core::{ClockDomain, FrameLayout, FrameTimestamp, PixelFormat};
use std::{
    fs::OpenOptions,
    io,
    mem::size_of,
    os::{
        fd::{AsRawFd, FromRawFd, OwnedFd},
        unix::fs::OpenOptionsExt,
    },
};

#[allow(
    non_upper_case_globals,
    non_camel_case_types,
    non_snake_case,
    dead_code,
    clippy::undocumented_unsafe_blocks,
    clippy::type_complexity
)]
mod sys {
    #[cfg(not(soong))]
    include!(concat!(env!("OUT_DIR"), "/v4l2.rs"));
    #[cfg(soong)]
    include!("bindings_android.rs");
}
use sys::*;

fn request<T>(dir: u32, number: u32) -> libc::c_ulong {
    ((dir << 30) | ((size_of::<T>() as u32) << 16) | (u32::from(b'V') << 8) | number) as _
}
fn ioctl<T>(fd: i32, dir: u32, number: u32, value: &mut T) -> io::Result<()> {
    loop {
        // SAFETY: request size is derived from T; the fd is owned by Capture.
        if unsafe { libc::ioctl(fd, request::<T>(dir, number) as _, value as *mut T) } >= 0 {
            return Ok(());
        }
        let e = io::Error::last_os_error();
        if e.kind() != io::ErrorKind::Interrupted {
            return Err(e);
        }
    }
}
fn fourcc(value: &[u8; 4]) -> u32 {
    u32::from_le_bytes(*value)
}
fn pixel_format(code: u32) -> Option<PixelFormat> {
    [
        (b"NV12", PixelFormat::Nv12),
        (b"NV16", PixelFormat::Nv16),
        (b"YUYV", PixelFormat::Yuyv),
        (b"UYVY", PixelFormat::Uyvy),
    ]
    .into_iter()
    .find(|(name, _)| fourcc(name) == code)
    .map(|(_, fmt)| fmt)
}

/// Exclusive capture session owning the device and its exported DMA-BUFs.
pub struct Capture {
    fd: OwnedFd,
    buffers: Vec<OwnedFd>,
    kind: u32,
    /// Actual layout returned by the capture driver.
    pub layout: FrameLayout,
    /// Whether standard G_PARM/S_PARM support changing the capture interval.
    pub can_set_fps: bool,
    streaming: bool,
    /// Completed stale buffers deliberately skipped before rendering.
    pub dropped: u64,
}

impl Capture {
    /// Validate a capture node, negotiate format, export buffers, and start streaming.
    pub fn open(path: &str, width: u32, height: u32, fps: u32) -> io::Result<Self> {
        if !valid_node(path) || width == 0 || height == 0 || fps == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "expected /dev/videoN and nonzero dimensions/fps",
            ));
        }
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(path)?;
        let fd: OwnedFd = file.into();
        // Advisory exclusivity among cooperating clients; driver busy errors remain authoritative.
        // SAFETY: flock operates only on this owned, live device descriptor.
        if unsafe { libc::flock(fd.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let mut cap = v4l2_capability::default();
        ioctl(fd.as_raw_fd(), 2, 0, &mut cap)?;
        let caps = if cap.capabilities & V4L2_CAP_DEVICE_CAPS != 0 {
            cap.device_caps
        } else {
            cap.capabilities
        };
        if caps & V4L2_CAP_STREAMING == 0 {
            return Err(io::Error::other("device does not support streaming"));
        }
        let kind = if caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE != 0 {
            9
        } else if caps & V4L2_CAP_VIDEO_CAPTURE != 0 {
            1
        } else {
            return Err(io::Error::other("not a video capture device"));
        };
        let mut format = v4l2_format {
            type_: kind,
            ..Default::default()
        };
        let mut found = false;
        for code in [b"NV12", b"NV16", b"YUYV", b"UYVY"] {
            // SAFETY: union member matches the V4L2 capture type.
            unsafe {
                if kind == 9 {
                    format.fmt.pix_mp = v4l2_pix_format_mplane {
                        width,
                        height,
                        pixelformat: fourcc(code),
                        field: 1,
                        ..Default::default()
                    };
                } else {
                    format.fmt.pix = v4l2_pix_format {
                        width,
                        height,
                        pixelformat: fourcc(code),
                        field: 1,
                        ..Default::default()
                    };
                }
                if ioctl(fd.as_raw_fd(), 3, 5, &mut format).is_ok() {
                    let actual = if kind == 9 {
                        format.fmt.pix_mp.pixelformat
                    } else {
                        format.fmt.pix.pixelformat
                    };
                    if pixel_format(actual).is_some() {
                        found = true;
                        break;
                    }
                }
            }
        }
        if !found {
            return Err(io::Error::other(
                "no supported contiguous NV12/NV16/YUYV/UYVY format",
            ));
        }
        // SAFETY: S_FMT succeeded and kind selects the matching initialized union member.
        let (w, h, stride, bytes, code, field) = unsafe {
            if kind == 9 {
                let p = format.fmt.pix_mp;
                if p.num_planes != 1 {
                    return Err(io::Error::other(
                        "separate-plane capture is not supported by this importer",
                    ));
                }
                let plane = p.plane_fmt[0];
                (
                    p.width,
                    p.height,
                    plane.bytesperline,
                    plane.sizeimage,
                    p.pixelformat,
                    p.field,
                )
            } else {
                let p = format.fmt.pix;
                (
                    p.width,
                    p.height,
                    p.bytesperline,
                    p.sizeimage,
                    p.pixelformat,
                    p.field,
                )
            }
        };
        if w != width || h != height {
            return Err(io::Error::other("driver changed requested resolution"));
        }
        if field != 1 {
            return Err(io::Error::other(
                "interlaced capture requires a deinterlacing backend",
            ));
        }
        let layout = FrameLayout {
            width: w,
            height: h,
            stride_bytes: stride,
            allocated_height: h,
            format: pixel_format(code).unwrap(),
            allocation_bytes: bytes as usize,
        };
        layout.validate().map_err(io::Error::other)?;
        let mut result = Self {
            fd,
            buffers: Vec::new(),
            kind,
            layout,
            can_set_fps: false,
            streaming: false,
            dropped: 0,
        };
        let mut params = v4l2_streamparm {
            type_: kind,
            ..Default::default()
        };
        params.parm.capture.timeperframe = v4l2_fract {
            numerator: 1,
            denominator: fps,
        };
        let mut current = v4l2_streamparm {
            type_: kind,
            ..Default::default()
        };
        if ioctl(result.fd.as_raw_fd(), 3, 21, &mut current).is_ok()
            // SAFETY: G_PARM initialized capture parameters for the selected capture type.
            && unsafe { current.parm.capture.capability & V4L2_CAP_TIMEPERFRAME != 0 }
        {
            result.can_set_fps = ioctl(result.fd.as_raw_fd(), 3, 22, &mut params).is_ok();
        }
        // Fixed-rate inputs are paced at delivery; no unsupported S_PARM is silently assumed.
        let mut req = v4l2_requestbuffers {
            count: 4,
            type_: kind,
            memory: 1,
            ..Default::default()
        };
        ioctl(result.fd.as_raw_fd(), 3, 8, &mut req)?;
        if !(2..=16).contains(&req.count) {
            return Err(io::Error::other("invalid driver buffer count"));
        }
        for index in 0..req.count {
            let mut plane = v4l2_plane::default();
            let mut buffer = result.buffer(index, &mut plane);
            ioctl(result.fd.as_raw_fd(), 3, 9, &mut buffer)?;
            let length = if kind == 9 {
                plane.length
            } else {
                buffer.length
            };
            if (length as usize) < layout.allocation_bytes {
                return Err(io::Error::other(
                    "driver buffer smaller than negotiated sizeimage",
                ));
            }
            let mut export = v4l2_exportbuffer {
                type_: kind,
                index,
                plane: 0,
                flags: (libc::O_CLOEXEC | libc::O_RDWR) as u32,
                ..Default::default()
            };
            ioctl(result.fd.as_raw_fd(), 3, 16, &mut export)?;
            if export.fd < 0 {
                return Err(io::Error::other("invalid exported DMA-BUF"));
            }
            // SAFETY: VIDIOC_EXPBUF returned a new owned descriptor.
            let exported = unsafe { OwnedFd::from_raw_fd(export.fd) };
            result.buffers.push(exported);
            result.queue(index)?;
        }
        let mut stream_type = kind;
        ioctl(result.fd.as_raw_fd(), 1, 18, &mut stream_type)?;
        result.streaming = true;
        Ok(result)
    }

    fn buffer(&self, index: u32, plane: &mut v4l2_plane) -> v4l2_buffer {
        let mut b = v4l2_buffer {
            type_: self.kind,
            index,
            memory: 1,
            ..Default::default()
        };
        if self.kind == 9 {
            b.length = 1;
            b.m.planes = plane;
        }
        b
    }
    fn queue(&self, index: u32) -> io::Result<()> {
        let mut plane = v4l2_plane::default();
        ioctl(
            self.fd.as_raw_fd(),
            3,
            15,
            &mut self.buffer(index, &mut plane),
        )
    }
    fn dequeue(&self) -> io::Result<v4l2_buffer> {
        let mut plane = v4l2_plane::default();
        let mut b = self.buffer(0, &mut plane);
        ioctl(self.fd.as_raw_fd(), 3, 17, &mut b)?;
        if b.index as usize >= self.buffers.len() {
            return Err(io::Error::other("driver returned invalid buffer index"));
        }
        // mplane pointer points to the stack; never expose it to a caller.
        b.m.planes = std::ptr::null_mut();
        let bytes = if self.kind == 9 {
            plane.bytesused
        } else {
            b.bytesused
        };
        if (self.kind == 9 && plane.data_offset != 0)
            || (bytes != 0 && bytes < self.layout.allocation_bytes as u32)
        {
            self.queue(b.index)?;
            return Err(io::Error::other(
                "truncated capture buffer or nonzero plane offset",
            ));
        }
        if b.flags & V4L2_BUF_FLAG_ERROR != 0 {
            self.queue(b.index)?;
            return Err(io::Error::other("capture buffer error"));
        }
        Ok(b)
    }

    /// Wait for a completed buffer and return only the newest bounded dequeue result.
    pub fn latest(&mut self, timeout_ms: i32) -> io::Result<Option<Frame<'_>>> {
        let mut pollfd = libc::pollfd {
            fd: self.fd.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: one valid pollfd, bounded wait; stop latency is at most this timeout plus hardware completion.
        let ready = unsafe { libc::poll(&mut pollfd, 1, timeout_ms) };
        if ready < 0 {
            return Err(io::Error::last_os_error());
        }
        if ready == 0 {
            return Ok(None);
        }
        if pollfd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
            return Err(io::Error::other("capture device poll error/disconnect"));
        }
        let mut b = match self.dequeue() {
            Ok(b) => b,
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => return Ok(None),
            Err(e) => return Err(e),
        };
        // Bound draining even if the producer continues while dequeuing.
        for _ in 1..self.buffers.len() {
            match self.dequeue() {
                Ok(next) => {
                    self.queue(b.index)?;
                    b = next;
                    self.dropped = self.dropped.saturating_add(1);
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(e) => {
                    let _ = self.queue(b.index);
                    return Err(e);
                }
            }
        }
        let timestamp = FrameTimestamp {
            nanos: (b.timestamp.tv_sec.max(0) as u64)
                .saturating_mul(1_000_000_000)
                .saturating_add((b.timestamp.tv_usec.max(0) as u64).saturating_mul(1000)),
            clock: if b.flags & V4L2_BUF_FLAG_TIMESTAMP_MASK == V4L2_BUF_FLAG_TIMESTAMP_MONOTONIC {
                ClockDomain::Monotonic
            } else {
                ClockDomain::Unknown
            },
        };
        Ok(Some(Frame {
            owner: self,
            index: b.index,
            timestamp,
            sequence: b.sequence,
        }))
    }
}

/// A borrowed capture lease. Drop requeues its buffer after synchronous readers finish.
pub struct Frame<'a> {
    owner: &'a Capture,
    index: u32,
    /// Driver timestamp with a validated clock-domain flag.
    pub timestamp: FrameTimestamp,
    /// Source sequence count, including buffers deliberately skipped by the consumer.
    pub sequence: u32,
}
impl Frame<'_> {
    /// Borrow the exported DMA-BUF descriptor; ownership remains in Capture.
    pub fn dma_fd(&self) -> i32 {
        self.owner.buffers[self.index as usize].as_raw_fd()
    }
}
impl Drop for Frame<'_> {
    fn drop(&mut self) {
        let _ = self.owner.queue(self.index);
    }
}
impl Drop for Capture {
    fn drop(&mut self) {
        if self.streaming {
            let _ = ioctl(self.fd.as_raw_fd(), 1, 19, &mut self.kind);
        }
        // Exported DMA-BUF fds and then device fd are closed automatically.
    }
}
/// Accept only literal /dev/videoN paths, with no traversal or aliases.
pub fn valid_node(path: &str) -> bool {
    path.strip_prefix("/dev/video")
        .is_some_and(|tail| !tail.is_empty() && tail.bytes().all(|b| b.is_ascii_digit()))
}

/// Read-only capability discovery: never S_FMT or STREAMON during enumeration.
pub fn probe_sizes(path: &str) -> io::Result<Vec<(u32, u32)>> {
    if !valid_node(path) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid capture path",
        ));
    }
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(path)?;
    let fd = file.as_raw_fd();
    let mut capability = v4l2_capability::default();
    ioctl(fd, 2, 0, &mut capability)?;
    let caps = if capability.capabilities & V4L2_CAP_DEVICE_CAPS != 0 {
        capability.device_caps
    } else {
        capability.capabilities
    };
    let kind = if caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE != 0 {
        9
    } else if caps & V4L2_CAP_VIDEO_CAPTURE != 0 {
        1
    } else {
        return Err(io::Error::other("not a capture node"));
    };
    let mut sizes = Vec::new();
    for index in 0..64 {
        let mut format = v4l2_fmtdesc {
            index,
            type_: kind,
            ..Default::default()
        };
        if ioctl(fd, 3, 2, &mut format).is_err() {
            break;
        }
        if pixel_format(format.pixelformat).is_none() {
            continue;
        }
        for index in 0..256 {
            let mut size = v4l2_frmsizeenum {
                index,
                pixel_format: format.pixelformat,
                ..Default::default()
            };
            if ioctl(fd, 3, 74, &mut size).is_err() {
                break;
            }
            // SAFETY: ENUM_FRAMESIZES initialized the union and type_ selects its member.
            unsafe {
                if size.type_ == 1 {
                    let d = size.__bindgen_anon_1.discrete;
                    sizes.push((d.width, d.height));
                } else {
                    let step = size.__bindgen_anon_1.stepwise;
                    // A range has no finite list. Expose its actual endpoints, not a
                    // hardcoded menu of common resolutions. G_FMT is included below.
                    for (w, h) in [
                        (step.min_width, step.min_height),
                        (step.max_width, step.max_height),
                    ] {
                        if w > 0 && h > 0 {
                            sizes.push((w, h));
                        }
                    }
                    break;
                }
            }
        }
    }
    {
        let mut format = v4l2_format {
            type_: kind,
            ..Default::default()
        };
        ioctl(fd, 3, 4, &mut format)?;
        // SAFETY: G_FMT succeeded and kind selects the driver's initialized member.
        unsafe {
            let (w, h, pixel) = if kind == 9 {
                let p = format.fmt.pix_mp;
                (p.width, p.height, p.pixelformat)
            } else {
                let p = format.fmt.pix;
                (p.width, p.height, p.pixelformat)
            };
            if pixel_format(pixel).is_some() {
                sizes.push((w, h));
            }
        }
    }
    sizes.retain(|(w, h)| *w > 0 && *h > 0);
    sizes.sort_unstable();
    sizes.dedup();
    Ok(sizes)
}

/// Read driver-reported rates for each discovered size. The wire format is
/// `widthxheight@min-max,min-max;...`; no defaults are invented when absent.
pub fn probe_capabilities(path: &str) -> io::Result<String> {
    let sizes = probe_sizes(path)?;
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open(path)?;
    let fd = file.as_raw_fd();
    let mut cap = v4l2_capability::default();
    ioctl(fd, 2, 0, &mut cap)?;
    let caps = if cap.capabilities & V4L2_CAP_DEVICE_CAPS != 0 {
        cap.device_caps
    } else {
        cap.capabilities
    };
    let kind = if caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE != 0 {
        9
    } else {
        1
    };
    let mut formats = Vec::new();
    for index in 0..64 {
        let mut f = v4l2_fmtdesc {
            index,
            type_: kind,
            ..Default::default()
        };
        if ioctl(fd, 3, 2, &mut f).is_err() {
            break;
        }
        if pixel_format(f.pixelformat).is_some() {
            formats.push(f.pixelformat);
        }
    }
    let mut result = Vec::new();
    for (width, height) in sizes {
        let mut rates = Vec::new();
        for &pixel_format in &formats {
            for index in 0..256 {
                let mut rate = v4l2_frmivalenum {
                    index,
                    pixel_format,
                    width,
                    height,
                    ..Default::default()
                };
                if ioctl(fd, 3, 75, &mut rate).is_err() {
                    break;
                }
                // SAFETY: ENUM_FRAMEINTERVALS filled the tagged union.
                unsafe {
                    if rate.type_ == 1 {
                        let interval = rate.__bindgen_anon_1.discrete;
                        if interval.numerator > 0 && interval.denominator % interval.numerator == 0
                        {
                            let fps = interval.denominator / interval.numerator;
                            if fps > 0 {
                                rates.push((fps, fps));
                            }
                        }
                    } else if rate.type_ == 3 {
                        let range = rate.__bindgen_anon_1.stepwise;
                        rates.extend(continuous_integer_rates(
                            (range.min.numerator, range.min.denominator),
                            (range.max.numerator, range.max.denominator),
                        ));
                        break;
                    } else if rate.type_ == 2 {
                        let range = rate.__bindgen_anon_1.stepwise;
                        // Offer integral boundary rates only, never arbitrary presets.
                        for interval in [range.min, range.max] {
                            if interval.numerator > 0
                                && interval.denominator % interval.numerator == 0
                            {
                                let fps = interval.denominator / interval.numerator;
                                if fps > 0 {
                                    rates.push((fps, fps));
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }
        rates.sort_unstable();
        rates.dedup();
        result.push(format!(
            "{width}x{height}@{}",
            rates
                .iter()
                .map(|(low, high)| format!("{low}-{high}"))
                .collect::<Vec<_>>()
                .join(",")
        ));
    }
    Ok(result.join(";"))
}

fn continuous_integer_rates(min_interval: (u32, u32), max_interval: (u32, u32)) -> Vec<(u32, u32)> {
    let (min_num, min_den) = min_interval;
    let (max_num, max_den) = max_interval;
    if min_num == 0 || max_num == 0 || min_den == 0 || max_den == 0 {
        return Vec::new();
    }
    let low = max_den.div_ceil(max_num).max(1);
    let high = min_den / min_num;
    // Bound malformed metadata rather than generating an unbounded menu.
    if high < low || high - low > 4096 {
        return Vec::new();
    }
    (low..=high).map(|fps| (fps, fps)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn continuous_interval_exposes_interior_rates_without_presets() {
        let rates = continuous_integer_rates((1, 30), (1, 1));
        assert_eq!(rates.len(), 30);
        assert!(rates.contains(&(25, 25)));
        assert_eq!(rates.last(), Some(&(30, 30)));
    }
    #[test]
    fn continuous_interval_respects_fractional_bounds_and_bad_metadata() {
        assert_eq!(
            continuous_integer_rates((1001, 30000), (1, 1)).last(),
            Some(&(29, 29))
        );
        assert!(continuous_integer_rates((0, 30), (1, 1)).is_empty());
        assert!(continuous_integer_rates((1, u32::MAX), (1, 1)).is_empty());
    }
    #[test]
    fn strict_node_paths() {
        assert!(valid_node("/dev/video23"));
        for bad in [
            "/dev/video",
            "/dev/video0/../mem",
            "/tmp/video0",
            "/dev/video-1",
        ] {
            assert!(!valid_node(bad));
        }
    }
    #[test]
    fn ioctl_matches_linux_abi() {
        assert_eq!(request::<v4l2_capability>(2, 0), 0x80685600);
        assert_eq!(request::<v4l2_exportbuffer>(3, 16), 0xc0405610);
    }
}
