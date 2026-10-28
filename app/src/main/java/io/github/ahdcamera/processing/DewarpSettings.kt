package io.github.ahdcamera.processing

/** Approximate equidistant lens, not a measured camera calibration. Angles are horizontal. */
data class DewarpSettings(
    val enabled: Boolean = true,
    val strength: Float = 1f,
    val lensFov: Float = 180f,
    val outputFov: Float = 100f,
) {
    fun validated() = copy(
        strength = strength.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        lensFov = lensFov.takeIf { it.isFinite() }?.coerceIn(100f, 220f) ?: 180f,
        outputFov = outputFov.takeIf { it.isFinite() }?.coerceIn(50f, 120f) ?: 100f,
    )
}
