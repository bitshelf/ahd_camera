package io.github.ahdcamera.camera

data class StreamSize(val width: Int, val height: Int) {
    override fun toString() = "$width×$height"

    val area: Long
        get() = width.toLong() * height
}

/** Pure policy: no assumed vendor camera IDs, resolutions or concurrency counts. */
object StreamPolicy {
    fun supportedFps(cameras: List<CameraInfo>, size: StreamSize?): List<Pair<Int, Int>> {
        if (size == null) return emptyList()
        return cameras.map { camera ->
            val duration = camera.minFrameDurations[size] ?: 0L
            (camera.fpsBySize[size] ?: camera.fps).filter { (low, high) ->
                low > 0 && high >= low && (duration == 0L || duration * high <= 1_000_000_000L + high)
            }.toSet()
        }.reduceOrNull { a, b -> a.intersect(b) }.orEmpty()
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
    }

    /** A fixed input's timestamp cadence bounds delivery; adjustable devices keep their reported modes. */
    fun effectiveFps(reported: List<Pair<Int, Int>>, input: Int?, controllable: Boolean): List<Pair<Int, Int>> {
        if (controllable || input == null || input <= 0) return reported
        return (reported.filter { it.second <= input } + (input to input)).distinct()
            .sortedByDescending { it.second }
    }

    fun concurrentSupported(ids: Set<String>, advertised: Set<Set<String>>): Boolean =
        ids.size <= 1 || advertised.any { it.containsAll(ids) }

}
