package io.github.ahdcamera.camera

data class Catalog(
    val cameras: List<CameraInfo> = emptyList(),
    val concurrent: Set<Set<String>> = emptySet(),
    val error: String? = null,
)
