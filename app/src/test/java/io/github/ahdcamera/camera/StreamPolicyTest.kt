package io.github.ahdcamera.camera

import org.junit.Assert.*
import org.junit.Test

class StreamPolicyTest {
    @Test fun requiresOneAdvertisedSetContainingTheWholeGroup() {
        val groups = setOf(setOf("0", "1"), setOf("2", "3"))
        assertTrue(StreamPolicy.concurrentSupported(setOf("0"), emptySet()))
        assertTrue(StreamPolicy.concurrentSupported(setOf("0", "1"), groups))
        assertFalse(StreamPolicy.concurrentSupported(setOf("0", "2"), groups))
        assertFalse(StreamPolicy.concurrentSupported(setOf("0", "1", "2", "3"), groups))
    }

    @Test fun supportsMoreThanSixteenCamerasWhenTheHalAdvertisesThem() {
        val ids = (100..131).map(Int::toString).toSet()
        assertTrue(StreamPolicy.concurrentSupported(ids, setOf(ids)))
        assertTrue(StreamPolicy.concurrentSupported(ids.take(17).toSet(), setOf(ids)))
        assertFalse(StreamPolicy.concurrentSupported(ids + "unadvertised", setOf(ids)))
    }

    private val size = StreamSize(1920, 1080)
    private fun camera(ranges: List<Pair<Int, Int>>, duration: Long) = CameraInfo(
        "camera", "external", listOf(size), ranges, true, 0, false, mapOf(size to duration),
    )

    @Test fun rejectsRatesTooFastForTheSelectedResolution() {
        val c = camera(listOf(30 to 60, 15 to 30, 24 to 24), 33_333_334)
        assertEquals(listOf(15 to 30, 24 to 24), StreamPolicy.supportedFps(listOf(c), size))
        assertTrue(StreamPolicy.supportedFps(listOf(c), null).isEmpty())
    }

    @Test fun keepsAdvertisedRangesAndDoesNotInventDefaults() {
        val c = camera(listOf(23 to 27), 0)
        assertEquals(listOf(23 to 27), StreamPolicy.supportedFps(listOf(c), size))
        assertTrue(StreamPolicy.supportedFps(listOf(camera(emptyList(), 0)), size).isEmpty())
        assertTrue(StreamPolicy.supportedFps(emptyList(), size).isEmpty())
    }

    @Test fun rejectsSizesNotAdvertisedByEveryCamera() {
        val camera = camera(listOf(30 to 30), 0)
        assertTrue(StreamPolicy.supportedFps(listOf(camera), StreamSize(3840, 2160)).isEmpty())
        val other = camera.copy(sizes = listOf(StreamSize(640, 480)))
        assertTrue(StreamPolicy.supportedFps(listOf(camera, other), size).isEmpty())
    }

    @Test fun rejectsInvalidRangesWithoutDiscardingValidOnes() {
        val camera = camera(listOf(0 to 30, 30 to 15, -1 to 25, 25 to 25), 0)
        assertEquals(listOf(25 to 25), StreamPolicy.supportedFps(listOf(camera), size))
    }

    @Test fun pixelCountDoesNotOverflow() {
        assertEquals(10_000_000_000L, StreamSize(100000, 100000).area)
    }
}
