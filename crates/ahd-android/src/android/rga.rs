//! Rockchip processing adapter. V4L2 ownership and Kotlin UI do not depend on this ABI.
use super::{check, g::*, Result, TraceSection};
use ahd_core::{FrameLayout, PixelFormat};
use libloading::Library;
use std::{ptr, sync::Arc};

type ImportFd = unsafe extern "C" fn(i32, i32) -> u32;
type WrapHandle = unsafe extern "C" fn(u32, i32, i32, i32, i32, i32) -> rga_buffer_t;
type Process = unsafe extern "C" fn(
    rga_buffer_t,
    rga_buffer_t,
    rga_buffer_t,
    im_rect,
    im_rect,
    im_rect,
    i32,
) -> IM_STATUS;
type ImportAhb = unsafe extern "C" fn(*mut AHardwareBuffer) -> u32;
type Release = unsafe extern "C" fn(u32) -> IM_STATUS;

pub(super) struct Rga {
    _library: Library,
    import_fd: ImportFd,
    wrap_handle: WrapHandle,
    check: Process,
    process: Process,
    import: ImportAhb,
    release: Release,
}
impl Rga {
    pub(super) fn load() -> Result<Self> {
        // SAFETY: Firmware supplies the trusted librga ABI matching the checked headers;
        // the Library owner outlives every copied function pointer.
        unsafe {
            let library = Library::new("librga.so").map_err(|e| {
                format!("RGA library is not accessible; check linker namespace: {e}")
            })?;
            macro_rules! symbol {
                ($name:expr, $kind:ty) => {
                    *library.get::<$kind>($name).map_err(|e| e.to_string())?
                };
            }
            Ok(Self {
                import_fd: symbol!(b"_Z15importbuffer_fdii\0", ImportFd),
                wrap_handle: symbol!(b"wrapbuffer_handle_t\0", WrapHandle),
                check: symbol!(b"imcheck_t\0", Process),
                process: symbol!(b"improcess\0", Process),
                // SDK im2d_expand.h: C++ importer taking an opaque AHardwareBuffer pointer.
                import: symbol!(
                    b"_Z28importbuffer_AHardwareBufferP15AHardwareBuffer\0",
                    ImportAhb
                ),
                release: symbol!(b"releasebuffer_handle\0", Release),
                _library: library,
            })
        }
    }
    pub(super) fn import_input(self: &Arc<Self>, fd: i32, bytes: usize) -> Result<ImportedInput> {
        let size =
            i32::try_from(bytes).map_err(|_| "capture allocation exceeds RGA import limit")?;
        // SAFETY: Capture owns the DMA-BUF for the entire ImportedInput lifetime.
        let handle = unsafe { (self.import_fd)(fd, size) };
        check(handle != 0, "RGA could not import capture DMA-BUF")?;
        Ok(ImportedInput {
            handle,
            rga: self.clone(),
        })
    }
    pub(super) fn convert(
        &self,
        input: &ImportedInput,
        layout: FrameLayout,
        output: &HardwareFrame,
    ) -> Result<()> {
        let _trace = TraceSection::begin(c"ahd.rga.convert");
        let (format, bpp) = match layout.format {
            PixelFormat::Nv12 => (0x0a << 8, 1),
            PixelFormat::Nv16 => (0x08 << 8, 1),
            PixelFormat::Yuyv => (0x1c << 8, 2),
            PixelFormat::Uyvy => (0x1e << 8, 2),
            _ => return Err("unsupported capture pixel format".into()),
        };
        // SAFETY: Capture owns the imported buffer until synchronous RGA finishes. Layout has been
        // validated and output owns the imported destination allocation.
        unsafe {
            let src = (self.wrap_handle)(
                input.handle,
                layout.width as _,
                layout.height as _,
                (layout.stride_bytes / bpp) as _,
                layout.allocated_height as _,
                format,
            );
            let dst = (self.wrap_handle)(
                output.handle,
                output.desc.width as _,
                output.desc.height as _,
                output.desc.stride as _,
                output.desc.height as _,
                0,
            ); // RK_FORMAT_RGBA_8888
            let empty = rga_buffer_t::default();
            let rect = im_rect::default();
            let valid = (self.check)(src, dst, empty, rect, rect, rect, 1 << 19);
            check(
                valid > 0,
                &format!("RGA validation failed {valid}: check format, stride and scale"),
            )?;
            let result = (self.process)(src, dst, empty, rect, rect, rect, 1 << 19);
            check(
                result > 0,
                &format!("RGA synchronous processing failed {result}"),
            )
        }
    }
}

/// Import once per capture buffer; release before Capture closes its DMA-BUFs.
pub(super) struct ImportedInput {
    handle: u32,
    rga: Arc<Rga>,
}
impl Drop for ImportedInput {
    fn drop(&mut self) {
        // SAFETY: The capture thread finished synchronous RGA work before dropping inputs.
        unsafe {
            (self.rga.release)(self.handle);
        }
    }
}

pub(super) struct HardwareFrame {
    pub(super) buffer: *mut AHardwareBuffer,
    pub(super) desc: AHardwareBuffer_Desc,
    handle: u32,
    rga: Arc<Rga>,
}
// SAFETY: Shared immutable metadata. AHB contents are written only at strong_count == 1;
// each consumer completes GPU work before releasing its Arc.
unsafe impl Send for HardwareFrame {}
// SAFETY: Writes require the sole pool Arc; all GPU consumers finish before releasing theirs.
unsafe impl Sync for HardwareFrame {}
impl HardwareFrame {
    pub(super) fn allocate(rga: Arc<Rga>, width: u32, height: u32) -> Result<Self> {
        let mut desc = AHardwareBuffer_Desc {
            width,
            height,
            layers: 1,
            format: 1,
            // CPU_READ_RARELY requests a linear, non-AFBC gralloc allocation on this BSP.
            // RGA writes raster RGBA; no CPU mapping or pixel copy is performed.
            usage: (1 << 8) | (1 << 9) | 2,
            ..Default::default()
        };
        let mut buffer = ptr::null_mut();
        // SAFETY: NDK receives valid writable output storage and a fully initialized descriptor.
        // Allocation failure is checked; successful references are released once by Drop.
        unsafe {
            check(
                AHardwareBuffer_allocate(&desc, &mut buffer) == 0,
                "AHardwareBuffer allocation failed",
            )?;
            AHardwareBuffer_describe(buffer, &mut desc);
            let handle = (rga.import)(buffer);
            if handle == 0 {
                AHardwareBuffer_release(buffer);
                return Err("RGA cannot import AHardwareBuffer".into());
            }
            Ok(Self {
                buffer,
                desc,
                handle,
                rga,
            })
        }
    }
}
impl Drop for HardwareFrame {
    fn drop(&mut self) {
        // SAFETY: Last Arc owns both live references and no GPU reader remains.
        unsafe {
            (self.rga.release)(self.handle);
            AHardwareBuffer_release(self.buffer);
        }
    }
}
