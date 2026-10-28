package io.github.ahdcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder
import io.github.ahdcamera.R
import io.github.ahdcamera.diagnostics.Diagnostics

data class Catalog(
    val cameras: List<CameraInfo> = emptyList(),
    val concurrent: Set<Set<String>> = emptySet(),
    val error: String? = null,
)

/** Only public Camera2 metadata is queried; failure of one device leaves others usable. */
fun CameraManager.catalog(context: Context): Catalog = try {
    val cameras = cameraIdList.mapNotNull { id ->
        try {
            val characteristics = getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            CameraInfo(
                id = id,
                facing = context.getString(when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> R.string.facing_front
                    CameraCharacteristics.LENS_FACING_BACK -> R.string.facing_back
                    else -> R.string.facing_external
                }),
                sizes = outputSizes.map { StreamSize(it.width, it.height) },
                fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { it.lower to it.upper }.orEmpty(),
                realtimeTimestamps = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                front = facing == CameraCharacteristics.LENS_FACING_FRONT,
                minFrameDurations = outputSizes.associate { size ->
                    StreamSize(size.width, size.height) to map!!.getOutputMinFrameDuration(SurfaceHolder::class.java, size)
                },
            ).takeIf { camera -> camera.sizes.any { StreamPolicy.supportedFps(listOf(camera), it).isNotEmpty() } }
        } catch (error: Exception) {
            Diagnostics.error("camera2_metadata_unavailable_$id", error)
            null
        }
    }
    Catalog(cameras, concurrentCameraIds.map { it.toSet() }.toSet())
} catch (error: Exception) {
    Diagnostics.error("camera2_enumeration_failed", error)
    Catalog(error = context.getString(R.string.enumeration_failed, error.javaClass.simpleName))
}
