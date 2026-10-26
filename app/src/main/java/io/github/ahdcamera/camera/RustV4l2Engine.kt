package io.github.ahdcamera.camera

import android.content.Context
import io.github.ahdcamera.R
import io.github.ahdcamera.diagnostics.Diagnostics
import io.github.ahdcamera.recording.SurfaceRecorder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Serializes ownership; Rust owns each device, DMA-BUF and rendering worker. */
class RustV4l2Engine(private val context: Context) : WallEngine {
    private data class Running(val target: PreviewTarget, val handle: Long, val recorder: SurfaceRecorder?)
    private val control = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ahd-v4l2-control") }
    private val closed = AtomicBoolean(false)
    private var desired = emptyList<PreviewTarget>()
    private val running = linkedMapOf<String, Running>()
    private val mutableStatus = MutableStateFlow<Map<String, TileStatus>>(emptyMap())
    override val status = mutableStatus.asStateFlow()
    private val mutableMessages = MutableStateFlow("")
    override val messages = mutableMessages.asStateFlow()

    init { control.scheduleWithFixedDelay(::poll, 1, 1, TimeUnit.SECONDS) }

    override fun configure(targets: List<PreviewTarget>) {
        if (!closed.get()) control.execute {
            if (!closed.get() && targets != desired) {
                desired = targets
                reconcile()
            }
        }
    }

    private fun stop(id: String) {
        val capture = running.remove(id) ?: return
        try { NativeBridge.stop(capture.handle) }
        finally { capture.recorder?.close() }
    }

    private fun reconcile() {
        mutableMessages.value = ""
        running.keys.toList().filter { id -> desired.none { it == running.getValue(id).target } }.forEach(::stop)
        mutableStatus.value = mutableStatus.value.filterKeys { id -> desired.any { it.id == id } }
        for (target in desired.filter { it.id !in running }) {
            var recorder: SurfaceRecorder? = null
            try {
                require(target.surface.isValid) { context.getString(R.string.invalid_surface) }
                val rate = requireNotNull(target.fpsRange) { context.getString(R.string.capability_not_reported) }
                require(rate.first == rate.second && rate.first > 0) { context.getString(R.string.preview_config_rejected) }
                mutableStatus.update { it + (target.id to TileStatus(context.getString(R.string.native_opening))) }
                if (target.recording) recorder = SurfaceRecorder.create(context, target.id, target.size, rate.second) {
                    mutableMessages.value = it
                }
                val handle = NativeBridge.start(target.id, target.surface, recorder?.surface,
                    target.size.width, target.size.height, rate.second, target.size.width, target.size.height)
                check(handle != 0L) { "V4L2 returned an invalid handle" }
                running[target.id] = Running(target, handle, recorder)
            } catch (e: Exception) {
                recorder?.close()
                reject(target.id, e)
            } catch (e: LinkageError) {
                recorder?.close()
                reject(target.id, e)
            }
        }
    }

    private fun reject(id: String, error: Throwable) {
        Diagnostics.error("v4l2_capture_failed", error)
        mutableStatus.update { it + (id to TileStatus(context.getString(R.string.native_capture_error,
            error.message ?: error.javaClass.simpleName))) }
    }

    private fun poll() {
        for ((id, capture) in running.toMap()) {
            try {
                val data = NativeBridge.status(capture.handle).split('|')
                val state = data.getOrNull(5)?.toIntOrNull() ?: 3
                if (state >= 2) {
                    stop(id)
                    reject(id, IllegalStateException(data.firstOrNull().orEmpty()))
                    continue
                }
                val fps = data.getOrNull(1)?.toFloatOrNull() ?: 0f
                mutableStatus.update { it + (id to TileStatus(
                    if (state == 1) context.getString(R.string.native_streaming) else context.getString(R.string.native_opening),
                    fps = fps, callbackAgeMs = data.getOrNull(4)?.toFloatOrNull()?.takeIf { age -> age >= 0 },
                    streaming = state == 1 && fps > 0,
                    processMs = data.getOrNull(3)?.toFloatOrNull(),
                    skippedFrames = data.getOrNull(2)?.toLongOrNull() ?: 0,
                    inputFps = data.getOrNull(6)?.toFloatOrNull() ?: 0f,
                    rateControllable = data.getOrNull(7) == "1",
                )) }
            } catch (e: Exception) {
                stop(id)
                reject(id, e)
            } catch (e: LinkageError) {
                stop(id)
                reject(id, e)
            }
        }
    }

    override fun retry() {
        if (!closed.get()) control.execute {
            running.keys.toList().forEach(::stop)
            reconcile()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) control.execute {
            desired = emptyList()
            running.keys.toList().forEach(::stop)
            control.shutdown()
        }
    }
}
