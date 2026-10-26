//! Platform-independent policies. Video pixels never pass through the UI model.
pub mod frame_rate;
pub mod latest;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
/// Pixel layouts accepted by the shared contiguous-buffer pipeline.
pub enum PixelFormat {
    /// 4:2:0 luma followed by interleaved Cb/Cr.
    Nv12,
    /// 4:2:2 luma followed by interleaved Cb/Cr.
    Nv16,
    /// Packed 4:2:2 in Y0/Cb/Y1/Cr byte order.
    Yuyv,
    /// Packed 4:2:2 in Cb/Y0/Cr/Y1 byte order.
    Uyvy,
    /// Eight bits each of red, green, blue, and alpha.
    Rgba8888,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
/// Negotiated storage layout, including padding and allocation capacity.
pub struct FrameLayout {
    /// Visible width in pixels.
    pub width: u32,
    /// Visible height in pixels.
    pub height: u32,
    /// Byte distance between adjacent luma or packed rows.
    pub stride_bytes: u32,
    /// Storage height; determines the start of the contiguous chroma plane.
    pub allocated_height: u32,
    /// Ordering and subsampling of stored pixels.
    pub format: PixelFormat,
    /// Total available bytes across the contiguous allocation.
    pub allocation_bytes: usize,
}

impl FrameLayout {
    /// Reject layouts that cannot safely be submitted as one contiguous RGA image.
    /// Separate-plane fds need a different importer and must never be guessed.
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.width == 0 || self.height == 0 || self.height > self.allocated_height {
            return Err("invalid frame dimensions");
        }
        let bytes_per_pixel = match self.format {
            PixelFormat::Yuyv | PixelFormat::Uyvy => 2_u64,
            PixelFormat::Rgba8888 => 4,
            _ => 1,
        };
        if u64::from(self.stride_bytes) < u64::from(self.width) * bytes_per_pixel {
            return Err("stride is smaller than visible width");
        }
        if u64::from(self.stride_bytes) % bytes_per_pixel != 0 {
            return Err("packed pixel stride is not an integer number of pixels");
        }
        if self.format != PixelFormat::Rgba8888 && self.width % 2 != 0 {
            return Err("YUV requires an even width");
        }
        if self.format == PixelFormat::Nv12
            && (self.height % 2 != 0 || self.allocated_height % 2 != 0)
        {
            return Err("NV12 requires even height and storage height");
        }
        let base = u64::from(self.stride_bytes) * u64::from(self.allocated_height);
        let required = match self.format {
            PixelFormat::Nv12 => base.checked_mul(3).and_then(|n| n.checked_div(2)),
            PixelFormat::Nv16 => base.checked_mul(2),
            _ => Some(base),
        }
        .ok_or("frame size overflow")?;
        if required > self.allocation_bytes as u64 {
            return Err("allocation is smaller than negotiated frame layout");
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
/// Time base established by the capture driver.
pub enum ClockDomain {
    /// Comparable with CLOCK_MONOTONIC.
    Monotonic,
    /// No safe cross-clock latency subtraction is possible.
    Unknown,
}

#[derive(Clone, Copy, Debug)]
/// Capture timestamp paired with its explicit clock domain.
pub struct FrameTimestamp {
    /// Timestamp in nanoseconds in the declared domain.
    pub nanos: u64,
    /// Clock used by the driver.
    pub clock: ClockDomain,
}

impl FrameTimestamp {
    /// This is capture-to-callback age, NEVER an end-to-end display latency claim.
    pub fn age_at_monotonic(&self, now_nanos: u64) -> Option<u64> {
        (self.clock == ClockDomain::Monotonic)
            .then(|| now_nanos.checked_sub(self.nanos))
            .flatten()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_short_allocation_and_uses_storage_stride() {
        let mut layout = FrameLayout {
            width: 1920,
            height: 1080,
            stride_bytes: 2048,
            allocated_height: 1088,
            format: PixelFormat::Nv12,
            allocation_bytes: 1920 * 1080 * 3 / 2,
        };
        assert!(layout.validate().is_err());
        layout.allocation_bytes = 2048 * 1088 * 3 / 2;
        assert!(layout.validate().is_ok());
    }
    #[test]
    fn timestamps_require_matching_clocks_and_order() {
        let ts = FrameTimestamp {
            nanos: 20,
            clock: ClockDomain::Unknown,
        };
        assert_eq!(ts.age_at_monotonic(30), None);
        let ts = FrameTimestamp {
            clock: ClockDomain::Monotonic,
            ..ts
        };
        assert_eq!(ts.age_at_monotonic(30), Some(10));
        assert_eq!(ts.age_at_monotonic(10), None);
    }
}
