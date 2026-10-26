package io.github.ahdcamera.camera

import kotlinx.coroutines.flow.StateFlow

interface WallEngine {
    val status: StateFlow<Map<String, TileStatus>>
    val messages: StateFlow<String>

    fun configure(targets: List<PreviewTarget>)

    fun retry()

    fun close()
}
