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

    @Test fun v4l2RatesRemainSpecificToTheirReportedSize() {
        val small = StreamSize(640, 480)
        val c = camera(listOf(60 to 60), 0).copy(
            sizes = listOf(size, small),
            fpsBySize = mapOf(size to listOf(25 to 25), small to emptyList()),
        )
        assertEquals(listOf(25 to 25), StreamPolicy.supportedFps(listOf(c), size))
        assertTrue(StreamPolicy.supportedFps(listOf(c), small).isEmpty())
    }

    @Test fun pixelCountDoesNotOverflow() {
        assertEquals(10_000_000_000L, StreamSize(100000, 100000).area)
    }
    @org.junit.Test fun fixedInputLimitsMetadataToItsObservedCadence() {
        val reported = (1..30).map { it to it }
        val available = StreamPolicy.effectiveFps(reported, 25, false)
        org.junit.Assert.assertEquals(25 to 25, available.first())
        org.junit.Assert.assertTrue((24 to 24) in available)
        org.junit.Assert.assertFalse((30 to 30) in available)
    }

    @org.junit.Test fun adjustableDeviceKeepsHigherModesAfterChoosingALowRate() {
        val reported = listOf(10 to 10, 30 to 30)
        org.junit.Assert.assertEquals(reported, StreamPolicy.effectiveFps(reported, 10, true))
        org.junit.Assert.assertEquals(listOf(25 to 25), StreamPolicy.effectiveFps(listOf(30 to 30), 25, false))
    }
}
