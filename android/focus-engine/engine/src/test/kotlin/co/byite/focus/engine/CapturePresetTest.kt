package co.byite.focus.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Presets of directive D: C's resolution fallback, E's every-other-frame rule, gap thresholds, G's hint target. */
class CapturePresetTest {
    @Test
    fun presetsDifferFromAInExactlyOneThing() {
        val a = CapturePreset.A
        assertEquals(FaceDelegate.CPU, a.faceDelegate)
        assertTrue(a.faceBlendshapes)
        assertEquals(1, a.frameProcessDivisor)
        assertNull(a.perfHintTargetMs)
        assertEquals(1280 to 720, a.requestedWidth to a.requestedHeight)
        assertFalse(CapturePreset.B.faceBlendshapes)
        assertEquals(640 to 360, CapturePreset.C.requestedWidth to CapturePreset.C.requestedHeight)
        assertEquals(FaceDelegate.GPU, CapturePreset.D.faceDelegate)
        assertEquals(2, CapturePreset.E.frameProcessDivisor)
        assertEquals(35, CapturePreset.G.perfHintTargetMs)
        assertEquals(35, CapturePreset.PERF_HINT_TARGET_MS)
        assertEquals(listOf("A", "B", "C", "D", "E", "G"), CapturePreset.entries.map { it.id })
        assertEquals(CapturePreset.A, CapturePreset.byId(null))
        assertEquals(CapturePreset.A, CapturePreset.byId("F"), "F (ROI crop) is not implemented; unknown ids fall back to A")
        assertEquals(CapturePreset.E, CapturePreset.byId("E"))
    }

    @Test
    fun gapThresholdIs80msForEveryFramePresetsAndTwiceTheExpectedIntervalForE() {
        for (p in CapturePreset.entries) if (p.frameProcessDivisor == 1) assertEquals(80, p.gapThresholdMs(24), p.id)
        assertEquals(167, CapturePreset.E.gapThresholdMs(24))
        assertEquals(133, CapturePreset.E.gapThresholdMs(30))
        for (p in CapturePreset.entries) if (p.frameProcessDivisor == 1) assertEquals(200, p.longGapThresholdMs(24), p.id)
        assertEquals(417, CapturePreset.E.longGapThresholdMs(24), "5 × 83.3 ms")
        assertEquals(333, CapturePreset.E.longGapThresholdMs(30))
    }

    @Test
    fun presetCTakes640x360WhenOfferedElseTheNearest16x9ElseC2() {
        val exact = PresetResolution.choose(CapturePreset.C, listOf(FrameSize(1280, 720), FrameSize(640, 480), FrameSize(640, 360)))
        assertEquals(FrameSize(640, 360), exact.size)
        assertEquals("C", exact.presetId)
        val nearest = PresetResolution.choose(CapturePreset.C, listOf(FrameSize(1920, 1080), FrameSize(1280, 720), FrameSize(800, 450), FrameSize(640, 480), FrameSize(320, 240)))
        assertEquals(FrameSize(800, 450), nearest.size)
        assertEquals("C", nearest.presetId)
        val fallback = PresetResolution.choose(CapturePreset.C, listOf(FrameSize(1024, 768), FrameSize(640, 480)))
        assertEquals(FrameSize(640, 480), fallback.size)
        assertEquals("C2", fallback.presetId)
        val a = PresetResolution.choose(CapturePreset.A, listOf(FrameSize(640, 480)))
        assertEquals(FrameSize(1280, 720), a.size, "other presets request their nominal size; CameraX picks the closest")
        assertEquals("A", a.presetId)
    }

    @Test
    fun everyOtherFrameRuleSkipsByCaptureTimestampNotByArrivalParity() {
        val r = FrameSkipRule(2, 24)
        val period = 1_000_000_000L / 24
        assertEquals((1.5 * 1_000_000_000.0 / 24).toLong(), r.minIntervalNs)
        assertFalse(r.shouldSkip(0), "the first frame is processed")
        r.onProcessed(0)
        assertTrue(r.shouldSkip(period), "41.7 ms after the last processed frame: skip")
        assertFalse(r.shouldSkip(2 * period), "83.3 ms: process")
        r.onProcessed(2 * period)
        // the camera dropped the frame at 3·period: the one at 4·period is processed, the phase does not slip
        assertFalse(r.shouldSkip(4 * period))
        r.onProcessed(4 * period)
        assertTrue(r.shouldSkip(5 * period))
        val every = FrameSkipRule(1, 24)
        every.onProcessed(0)
        assertFalse(every.shouldSkip(1), "divisor 1 never skips")
    }
}
