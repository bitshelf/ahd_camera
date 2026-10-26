# JNI entry point names must remain stable; image buffers never cross JNI.
-keep class io.github.ahdcamera.camera.NativeBridge { *; }
-keepclassmembers class io.github.ahdcamera.WallViewModel {
    public <init>(android.app.Application);
}
