package io.github.ahdcamera

import android.content.Context
import android.hardware.camera2.CameraManager
import io.github.ahdcamera.camera.*

fun createEngine(context: Context): WallEngine = KotlinCamera2Engine(context)

fun readCatalog(context: Context): Catalog =
    context.getSystemService(CameraManager::class.java).catalog(context)

/** Standard availability notifications cover Provider startup, unplug and service restart. */
fun watchCatalogChanges(context: Context, onChanged: () -> Unit): AutoCloseable {
    val manager = context.getSystemService(CameraManager::class.java)
    val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = onChanged()
        override fun onCameraUnavailable(cameraId: String) = onChanged()
    }
    manager.registerAvailabilityCallback(context.mainExecutor, callback)
    return AutoCloseable { manager.unregisterAvailabilityCallback(callback) }
}
