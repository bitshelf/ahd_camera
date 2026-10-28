package io.github.ahdcamera.camera

import android.view.Surface
import io.github.ahdcamera.processing.DewarpSettings
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** SurfaceHolder destruction waits for the current GPU frame before releasing its BufferQueue. */
class PreviewLifetime {
    private val lock = ReentrantLock()
    @Volatile var alive = true
        private set
    fun invalidate() = lock.withLock { alive = false }
    fun whileAlive(block: () -> Unit) = lock.withLock { if (alive) block() }
}

data class PreviewTarget(
    val id: String,
    val surface: Surface,
    val size: StreamSize,
    val recording: Boolean = false,
    val fpsRange: Pair<Int, Int>? = null,
    val dewarp: DewarpSettings = DewarpSettings(),
    val lifetime: PreviewLifetime = PreviewLifetime(),
)

data class TileStatus(
    val message: String = "",
    val fps: Float = 0f,
    val callbackAgeMs: Float? = null,
    val failures: Long = 0,
    val streaming: Boolean = false,
    val processMs: Float? = null,
)
