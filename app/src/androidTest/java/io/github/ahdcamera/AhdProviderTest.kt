// SPDX-License-Identifier: Apache-2.0
package io.github.ahdcamera

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Explicit hardware acceptance test: requires the independently integrated AHD Provider. */
@RunWith(AndroidJUnit4::class)
class AhdProviderTest {
    @Suppress("DEPRECATION", "MissingPermission")
    @Test fun concurrentCaptureAndRepeatedSessionReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList.filter { it.startsWith("ahd-") }.sorted()
        assertTrue("No independent AHD cameras enumerated", ids.isNotEmpty())
        val thread = HandlerThread("AhdProviderAcceptance").apply { start() }
        val handler = Handler(thread.looper)
        try {
            repeat(3) { round ->
                val devices = mutableListOf<CameraDevice>()
                val sessions = mutableListOf<CameraCaptureSession>()
                val readers = mutableListOf<ImageReader>()
                val closed = mutableListOf<CountDownLatch>()
                val error = AtomicReference<String?>(null)
                val counts = ids.map { AtomicInteger() }
                val timestamps = ids.map { AtomicLong() }
                try {
                    // Open the complete set before configuring streams (Camera2 concurrency contract).
                    for (id in ids) {
                        val opened = CountDownLatch(1)
                        val finished = CountDownLatch(1)
                        val device = AtomicReference<CameraDevice?>()
                        closed += finished
                        manager.openCamera(id, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) { device.set(camera); opened.countDown() }
                            override fun onDisconnected(camera: CameraDevice) {
                                error.set("$id disconnected"); camera.close(); opened.countDown()
                            }
                            override fun onError(camera: CameraDevice, code: Int) {
                                error.set("$id device error $code"); camera.close(); opened.countDown()
                            }
                            override fun onClosed(camera: CameraDevice) { finished.countDown() }
                        }, handler)
                        assertTrue("Open timeout: $id", opened.await(10, TimeUnit.SECONDS))
                        checkNotNull(device.get()) { error.get() ?: "Open failed: $id" }.also { devices += it }
                    }
                    for ((index, device) in devices.withIndex()) {
                        val characteristics = manager.getCameraCharacteristics(device.id)
                        val sizes = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                            .getOutputSizes(ImageFormat.YUV_420_888).sortedBy { it.width.toLong() * it.height }
                        val size = if (round == 1) sizes.first() else sizes.last()
                        val fps = checkNotNull(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                            .maxBy { it.upper }
                        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
                        readers += reader
                        reader.setOnImageAvailableListener({ source ->
                            source.acquireLatestImage()?.use { image ->
                                val previous = timestamps[index].getAndSet(image.timestamp)
                                if (previous > 0 && image.timestamp <= previous) error.set("Non-monotonic timestamp: ${device.id}")
                                counts[index].incrementAndGet()
                            }
                        }, handler)
                        val configured = CountDownLatch(1)
                        val current = AtomicReference<CameraCaptureSession?>()
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) { current.set(session); configured.countDown() }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close(); error.set("Configure failed: ${device.id}"); configured.countDown()
                            }
                        }, handler)
                        assertTrue("Configure timeout: ${device.id}", configured.await(10, TimeUnit.SECONDS))
                        val session = checkNotNull(current.get()) { error.get() ?: "Configure failed" }
                        sessions += session
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        Log.i("AhdProviderTest", "round=$round ${device.id} $size $fps")
                    }
                    // Wait for real buffers from all cameras, not just successful Binder calls.
                    Thread.sleep(4000)
                    for ((index, id) in ids.withIndex()) {
                        Log.i("AhdProviderTest", "round=$round $id frames=${counts[index].get()}")
                        assertTrue("No sustained buffers: $id, ${counts[index].get()} frames", counts[index].get() >= 40)
                    }
                    assertTrue(error.get(), error.get() == null)
                } finally {
                    sessions.forEach { it.close() }
                    devices.forEach { it.close() }
                    closed.forEach { it.await(5, TimeUnit.SECONDS) }
                    readers.forEach { it.close() }
                }
            }
        } finally {
            thread.quitSafely()
            thread.join(5000)
        }
    }
}
