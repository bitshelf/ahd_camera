package io.github.ahdcamera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.SystemClock
import android.util.Range
import androidx.core.content.ContextCompat
import io.github.ahdcamera.R
import io.github.ahdcamera.diagnostics.Diagnostics
import io.github.ahdcamera.recording.SurfaceRecorder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Camera2 devices, sessions and callbacks share one executor; pixels stay in Surfaces. */
class KotlinCamera2Engine(private val context: Context) : WallEngine {
    private class Running(val target: PreviewTarget, val recorder: SurfaceRecorder?) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var frames = 0L
        var failures = 0L
        var lastFrameNs = 0L
        var lastPollNs = SystemClock.elapsedRealtimeNanos()
        var lastFrames = 0L
        val surfaces get() = listOf(target.surface) + listOfNotNull(recorder?.surface)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val control = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ahd-kotlin-camera2") }
    private val closed = AtomicBoolean(false)
    private val running = linkedMapOf<String, Running>()
    private var desired = emptyList<PreviewTarget>()
    private var generation = 0L
    private var pendingRestart: ScheduledFuture<*>? = null
    private val mutableStatus = MutableStateFlow<Map<String, TileStatus>>(emptyMap())
    override val status = mutableStatus.asStateFlow()
    private val mutableMessages = MutableStateFlow("")
    override val messages = mutableMessages.asStateFlow()

    init { control.scheduleWithFixedDelay(::poll, 1, 1, TimeUnit.SECONDS) }

    override fun configure(targets: List<PreviewTarget>) {
        if (closed.get()) return
        control.execute {
            if (closed.get()) return@execute
            // A restarted Provider can return the same IDs/Surfaces after an error.
            // An empty running group must be allowed to reopen those targets.
            if (targets == desired && (targets.isEmpty() || running.isNotEmpty() || pendingRestart != null)) return@execute
            desired = targets
            pendingRestart?.cancel(false)
            pendingRestart = null
            if (targets.isEmpty()) restart()
            else pendingRestart = control.schedule({ pendingRestart = null; restart() }, 150, TimeUnit.MILLISECONDS)
        }
    }

    private fun current(epoch: Long) = !closed.get() && generation == epoch

    private fun stopRunning(keepRecorders: Set<SurfaceRecorder> = emptySet()) {
        // Invalidate queued callbacks before closing any device. Late onOpened
        // and onConfigured callbacks close their own handles without touching a new group.
        generation++
        val previous = running.values.toList()
        running.clear()
        previous.forEach {
            runCatching { it.session?.close() }.onFailure { e -> Diagnostics.error("camera2_session_close_failed", e) }
            runCatching { it.device?.close() }.onFailure { e -> Diagnostics.error("camera2_close_failed", e) }
        }
        // Disconnect every producer before encoder EOS and Surface release.
        previous.mapNotNull { it.recorder }.filter { it !in keepRecorders }.forEach { it.close() }
    }

    private fun restart() {
        // A new configuration (including an app locale change) clears old result text.
        mutableMessages.value = ""
        // Adding another recording output may require rebuilding the camera group.
        // Keep existing encoders open across that gap so their MP4 stays one file.
        val retained = running.values.filter { it.target in desired }
            .mapNotNull { capture -> capture.recorder?.let { capture.target.id to it } }.toMap()
        stopRunning(retained.values.toSet())
        val targets = desired.toList()
        mutableStatus.value = targets.associate { it.id to TileStatus(context.getString(R.string.waiting_first_frame)) }
        if (targets.isEmpty()) return
        val prepared = linkedMapOf<String, SurfaceRecorder>().apply { putAll(retained) }
        val epoch = generation
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException(context.getString(R.string.permission_required))
            }
            val catalog = manager.catalog(context)
            check(catalog.error == null) { catalog.error.orEmpty() }
            val ids = targets.map { it.id }.toSet()
            check(ids.size == targets.size) { context.getString(R.string.duplicate_camera) }
            check(StreamPolicy.concurrentSupported(ids, catalog.concurrent)) {
                context.getString(R.string.unsupported_combination, ids.size)
            }
            for (target in targets) {
                check(target.surface.isValid) { context.getString(R.string.invalid_surface) }
                val camera = catalog.cameras.firstOrNull { it.id == target.id }
                check(camera != null && target.size in camera.sizes &&
                    target.fpsRange in StreamPolicy.supportedFps(listOf(camera), target.size)) {
                    context.getString(R.string.preview_config_rejected)
                }
                if (target.recording && target.id !in prepared) prepared[target.id] = SurfaceRecorder.create(
                    context, target.id, target.size, requireNotNull(target.fpsRange).second,
                ) { mutableMessages.value = it }
            }
            targets.forEach { running[it.id] = Running(it, prepared[it.id]) }
            if (targets.size > 1) {
                val configurations = running.mapValues { (_, capture) ->
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        capture.surfaces.map(::OutputConfiguration), control,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) = Unit
                            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                        })
                }
                check(manager.isConcurrentSessionConfigurationSupported(configurations)) {
                    context.getString(R.string.unsupported_concurrent_resolution)
                }
            }
            // Open the complete advertised group before configuring any session.
            running.values.toList().forEach { capture ->
                manager.openCamera(capture.target.id, control, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!current(epoch)) { camera.close(); return }
                        capture.device = camera
                        if (running.values.all { it.device != null }) startSessions(epoch)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (current(epoch)) reject(IllegalStateException(context.getString(R.string.camera_disconnected)))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (current(epoch)) {
                            val message = when (error) {
                                ERROR_CAMERA_IN_USE -> context.getString(R.string.camera_in_use)
                                ERROR_MAX_CAMERAS_IN_USE -> context.getString(R.string.camera_resources_exhausted)
                                ERROR_CAMERA_DISABLED -> context.getString(R.string.camera_disabled)
                                else -> context.getString(R.string.camera_error, error)
                            }
                            reject(IllegalStateException(message))
                        }
                    }
                })
            }
        } catch (error: Exception) {
            reject(error, prepared.values)
        }
    }

    private fun startSessions(epoch: Long) {
        try {
            running.values.toList().forEach { capture ->
                val device = requireNotNull(capture.device)
                val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    capture.surfaces.map(::OutputConfiguration), control,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!current(epoch)) { session.close(); return }
                            capture.session = session
                            try {
                                val fps = requireNotNull(capture.target.fpsRange)
                                val request = device.createCaptureRequest(
                                    if (capture.recorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,
                                ).apply {
                                    capture.surfaces.forEach { addTarget(it) }
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.first, fps.second))
                                }.build()
                                session.setSingleRepeatingRequest(request, control, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        if (current(epoch)) {
                                            capture.frames++
                                            capture.lastFrameNs = SystemClock.elapsedRealtimeNanos()
                                        }
                                    }

                                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                        if (current(epoch)) capture.failures++
                                    }
                                })
                                Diagnostics.event("camera2_started", "Kotlin ${capture.target.id}:${capture.target.size}@${capture.target.fpsRange}")
                            } catch (error: Exception) { if (current(epoch)) reject(error) }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            if (current(epoch)) reject(IllegalStateException(context.getString(R.string.preview_config_rejected)))
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            if (current(epoch) && capture.session === session) {
                                reject(IllegalStateException(context.getString(R.string.capture_stopped)))
                            }
                        }
                    })
                device.createCaptureSession(configuration)
            }
        } catch (error: Exception) { if (current(epoch)) reject(error) }
    }

    private fun reject(error: Throwable, prepared: Collection<SurfaceRecorder> = emptyList()) {
        Diagnostics.error("camera2_start_failed", error)
        val owned = running.values.mapNotNull { it.recorder }.toSet()
        stopRunning()
        prepared.filter { it !in owned }.forEach { it.close() }
        mutableStatus.value = desired.associate {
            it.id to TileStatus(context.getString(R.string.capture_error, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun poll() {
        val now = SystemClock.elapsedRealtimeNanos()
        mutableStatus.value = running.mapValues { (_, capture) ->
            val fps = (capture.frames - capture.lastFrames) * 1_000_000_000.0 / (now - capture.lastPollNs).coerceAtLeast(1)
            capture.lastFrames = capture.frames
            capture.lastPollNs = now
            val age = capture.lastFrameNs.takeIf { it > 0 }?.let { (now - it) / 1_000_000f }
            TileStatus(
                if (capture.frames > 0) context.getString(R.string.capture_callbacks, capture.target.size.toString())
                else context.getString(R.string.waiting_first_frame),
                fps.toFloat(), age, capture.failures, capture.frames > 0 && fps > 0,
            )
        }.takeIf { it.isNotEmpty() } ?: mutableStatus.value
    }

    override fun retry() {
        if (!closed.get()) control.execute {
            pendingRestart?.cancel(false)
            pendingRestart = null
            if (!closed.get()) restart()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) control.execute {
            pendingRestart?.cancel(false)
            pendingRestart = null
            desired = emptyList()
            stopRunning()
            control.shutdown()
        }
    }
}
