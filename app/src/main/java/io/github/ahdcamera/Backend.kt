package io.github.ahdcamera

import android.content.Context
import io.github.ahdcamera.camera.*
import java.io.File

fun createEngine(context: Context): WallEngine = RustV4l2Engine(context)

/** Read-only discovery exposes only nodes with usable capture capabilities. */
fun readCatalog(context: Context): Catalog = try {
    val entries = File("/dev").listFiles()
    if (entries == null) {
        Catalog(error = context.getString(R.string.native_scan_denied))
    } else {
        val cameras = entries
            .filter { it.name.matches(Regex("video[0-9]+")) }
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull camera@ { node ->
                val probe = NativeBridge.probe(node.path)
                if (probe.startsWith("error:")) return@camera null
                val options = probe.split(';').mapNotNull option@ { entry ->
                    val fields = entry.split('@', limit = 2)
                    val dimensions = fields[0].split('x')
                    val width = dimensions.getOrNull(0)?.toIntOrNull() ?: return@option null
                    val height = dimensions.getOrNull(1)?.toIntOrNull() ?: return@option null
                    if (width <= 0 || height <= 0) return@option null
                    val rates = fields.getOrNull(1).orEmpty().split(',').mapNotNull rate@ { rate ->
                        val parts = rate.split('-')
                        val low = parts.getOrNull(0)?.toIntOrNull() ?: return@rate null
                        val high = parts.getOrNull(1)?.toIntOrNull() ?: return@rate null
                        if (low <= 0 || high < low) return@rate null
                        low to high
                    }
                    if (rates.isEmpty()) return@option null
                    StreamSize(width, height) to rates
                }.toMap()
                if (options.isEmpty()) return@camera null
                val description = runCatching {
                    File("/sys/class/video4linux/${node.name}/name").readText().trim()
                }.getOrDefault("V4L2")
                CameraInfo(node.path, description, options.keys.toList(), options.values.flatten().distinct(),
                    false, 0, false, fpsBySize = options)
            }
        Catalog(cameras)
    }
} catch (e: Exception) {
    Catalog(error = context.getString(R.string.enumeration_failed, e.javaClass.simpleName))
} catch (e: LinkageError) {
    Catalog(error = context.getString(R.string.native_capture_error, e.message ?: e.javaClass.simpleName))
}
