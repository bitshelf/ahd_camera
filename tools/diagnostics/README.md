# Native diagnostics

These programs do not change device-node permissions, SELinux policy, HALs or firmware.
Root execution is a development measurement, never application/deployment acceptance.
Always select the intended device with `adb -s SERIAL`; never run against an implicit device.

`ahd-v4l2-probe` is built by Cargo (`-p ahd-v4l2 --bin ahd-v4l2-probe`) or Soong.
With explicit `/dev/videoN` arguments it queries capabilities only. `--frames N` additionally
negotiates the largest reported size, requests a reported integral rate, and captures N frames
concurrently for each specified node. It changes active stream configuration temporarily,
then stops and releases it. This does not exercise RGA, rendering or recording.

The Java files declare only the existing JNI interface and exercise real Rust capture → RGA →
AHardwareBuffer → GLES → ImageReader surfaces. Compile them separately with Android SDK javac/d8
into a dex JAR, then run the main class using a development `app_process` with that JAR on CLASSPATH.
Arguments: absolute native library path followed by explicit capture-node paths. They are never
packaged in the App, never declare a persistent service, and never add an alternate production backend.

The pipeline probe runs 12 seconds, drains surfaces, reports frame counts and sampled image hashes,
and closes every producer before releasing its Surface. Any node without images fails the probe.
Changing hashes help identify changing content but do not measure end-to-end latency or prove no loss.
The test does not present a user-visible camera preview and is not App visual acceptance.
