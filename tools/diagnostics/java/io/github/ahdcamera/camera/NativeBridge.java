package io.github.ahdcamera.camera;

import android.view.Surface;

/** Standalone diagnostic JNI declaration; never packaged with the application. */
public final class NativeBridge {
    private NativeBridge() {}
    public static native long start(String node, Surface preview, Surface encoder,
        int width, int height, int fps, int outputWidth, int outputHeight);
    public static native void stop(long handle);
    public static native String status(long handle);
    public static native String probe(String node);
}
