//! Android host adapter. Platform-neutral capture is in ahd-v4l2 and ahd-core.
#![deny(unsafe_op_in_unsafe_fn, clippy::undocumented_unsafe_blocks)]
#[cfg(target_os = "android")]
mod android;
