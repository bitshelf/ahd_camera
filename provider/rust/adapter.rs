// SPDX-License-Identifier: Apache-2.0
//! The only unsafe boundary: immutable profile, owned Binder reference, metadata ABI.
use android_hardware_camera_device::aidl::android::hardware::camera::device::ICameraDevice::ICameraDevice;
use binder::Strong;
use std::ffi::{c_char, c_void, CStr};
use std::ptr::NonNull;

// Generated camera parcelables deliberately do not implement Clone. A parcel
// round-trip duplicates native handle/fence FDs and metadata before async delivery.
pub fn copy_parcel<T>(values: &[T]) -> binder::Result<Vec<T>>
where
    [T]: binder::binder_impl::Serialize,
    Vec<T>: binder::binder_impl::Deserialize,
{
    let mut parcel = binder::binder_impl::Parcel::new();
    parcel.write(values)?;
    // SAFETY: zero is the start of the parcel we have just successfully written.
    unsafe {
        parcel.set_data_position(0)?;
    }
    Ok(parcel.read()?)
}

extern "C" {
    fn ahd_adapter_create() -> *mut c_void;
    fn ahd_adapter_destroy(adapter: *mut c_void);
    fn ahd_adapter_count(adapter: *const c_void) -> usize;
    fn ahd_adapter_id(adapter: *const c_void, index: usize) -> *const c_char;
    fn ahd_adapter_max_concurrent(adapter: *const c_void) -> u32;
    fn ahd_adapter_max_pixel_rate(adapter: *const c_void) -> u64;
    fn ahd_adapter_probe(adapter: *const c_void, index: usize) -> bool;
    fn ahd_adapter_device(adapter: *const c_void, index: usize) -> *mut c_void;
    fn ahd_metadata_budget(bytes: *const u8, size: usize, pixels: *mut u64, fps: *mut u32) -> bool;
}

pub struct Adapter {
    handle: NonNull<c_void>,
    pub ids: Vec<String>,
    pub max_concurrent: usize,
    pub max_pixel_rate: u64,
}
// SAFETY: after construction the C++ Adapter is immutable; capture::setNodes is
// called exactly once before publication. Probes use independent FDs and the
// capture adapter protects its shared state. Drop occurs after the worker joins.
unsafe impl Send for Adapter {}
unsafe impl Sync for Adapter {}

impl Adapter {
    pub fn new() -> Self {
        // SAFETY: no arguments; returns an owned Adapter or null.
        let handle =
            NonNull::new(unsafe { ahd_adapter_create() }).expect("Adapter allocation failed");
        let p = handle.as_ptr();
        // SAFETY: p is live; strings are immutable and copied before any Drop.
        let ids = unsafe {
            (0..ahd_adapter_count(p))
                .map(|i| {
                    CStr::from_ptr(ahd_adapter_id(p, i))
                        .to_str()
                        .expect("ASCII camera ID")
                        .to_owned()
                })
                .collect()
        };
        // SAFETY: p points to an initialized immutable Adapter.
        let (max_concurrent, max_pixel_rate) = unsafe {
            (
                ahd_adapter_max_concurrent(p) as usize,
                ahd_adapter_max_pixel_rate(p),
            )
        };
        Self {
            handle,
            ids,
            max_concurrent,
            max_pixel_rate,
        }
    }

    pub fn probe(&self, index: usize) -> bool {
        // SAFETY: self keeps the profile alive; C++ checks the index.
        unsafe { ahd_adapter_probe(self.handle.as_ptr(), index) }
    }

    pub fn device(&self, index: usize) -> Option<Strong<dyn ICameraDevice>> {
        // SAFETY: the bridge returns null or one owned AIBinder strong reference.
        // new_spibinder adopts that reference, releasing it on every error path.
        let binder = unsafe {
            binder::unstable_api::new_spibinder(
                ahd_adapter_device(self.handle.as_ptr(), index).cast(),
            )
        }?;
        binder.into_interface().ok()
    }
}

impl Drop for Adapter {
    fn drop(&mut self) {
        // SAFETY: unique owner; all borrowed operations have completed.
        unsafe { ahd_adapter_destroy(self.handle.as_ptr()) }
    }
}

pub fn native_budget(bytes: &[u8]) -> Option<(u64, u32)> {
    let (mut pixels, mut fps) = (0, 0);
    // SAFETY: slice and out parameters are valid for the call; bridge validates
    // metadata size/layout and never retains pointers.
    unsafe { ahd_metadata_budget(bytes.as_ptr(), bytes.len(), &mut pixels, &mut fps) }
        .then_some((pixels, fps))
}
