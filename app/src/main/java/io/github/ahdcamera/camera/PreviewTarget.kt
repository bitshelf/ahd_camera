package io.github.ahdcamera.camera

import android.view.Surface

data class PreviewTarget(
    val id: String,
    val surface: Surface,
    val size: StreamSize,
    val recording: Boolean = false,
    val fpsRange: Pair<Int, Int>? = null,
)

data class TileStatus(
    val message: String = "",
    val fps: Float = 0f,
    val callbackAgeMs: Float? = null,
    val failures: Long = 0,
    val streaming: Boolean = false,
    val processMs: Float? = null,
)
