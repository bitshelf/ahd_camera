package io.github.ahdcamera.camera

import android.view.Surface

internal object NativeBridge {
    init {
        System.loadLibrary("ahd_android")
    }

    @JvmStatic
    external fun start(
        node: String,
        preview: Surface,
        encoder: Surface?,
        width: Int,
        height: Int,
        fps: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Long

    @JvmStatic external fun stop(handle: Long)

    @JvmStatic external fun status(handle: Long): String

    @JvmStatic external fun probe(node: String): String
}
