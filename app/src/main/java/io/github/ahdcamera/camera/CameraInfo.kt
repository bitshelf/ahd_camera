package io.github.ahdcamera.camera

data class CameraInfo(
    val id: String,
    val facing: String,
    val sizes: List<StreamSize>,
    val fps: List<Pair<Int, Int>>,
    val realtimeTimestamps: Boolean,
    val orientation: Int,
    val front: Boolean,
    val minFrameDurations: Map<StreamSize, Long> = emptyMap(),
)
