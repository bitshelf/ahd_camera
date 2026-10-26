# Rockchip RGA FFI headers

Headers restored from this project's original commit `f756d53`; corresponding BSP librga
checkout is `f3be6d8132b09a3b5569d1981a37fe627a27e370`. Preserve the upstream notices and COPYING.
The application does not bundle the vendor library. It loads the device-supplied `librga.so`
and reports loader/ABI failures explicitly. Native-library visibility and access to the RGA
and capture devices remain deployment requirements, not capabilities granted by this APK.

The Rust adapter is isolated in `crates/ahd-android/src/android/rga.rs`. Replacing the SoC
processing backend should not change V4L2 ownership or Kotlin UI. No C++ bridge is added.
