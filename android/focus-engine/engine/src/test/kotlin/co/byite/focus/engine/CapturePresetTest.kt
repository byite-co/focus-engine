package co.byite.focus.engine

import co.byite.focus.core.aggregate.GapThresholds
import co.byite.focus.core.model.FaceSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Presets of directives D and E: C's resolution fallback, the slot presets, the camera-cadence presets and their availability, gap thresholds. */
class CapturePresetTest {
    private val galaxyRanges = listOf(FpsRange(7, 15), FpsRange(10, 30), FpsRange(12, 12), FpsRange(15, 15), FpsRange(24, 24), FpsRange(30, 30))

    @Test
    fun presetsDifferFromAInOneThing() {
        val a = CapturePreset.A
        assertEquals(FaceDelegate.CPU, a.faceDelegate)
        assertTrue(a.faceBlendshapes)
        assertEquals(1, a.frameProcessDivisor)
        assertNull(a.perfHintTargetMs)
        assertNull(a.faceRateHz)
        assertEquals(CameraFpsRequest.Default, a.cameraFps)
        assertEquals(1280 to 720, a.requestedWidth to a.requestedHeight)
        assertFalse(CapturePreset.B.faceBlendshapes)
        assertEquals(640 to 360, CapturePreset.C.requestedWidth to CapturePreset.C.requestedHeight)
        assertEquals(FaceDelegate.GPU, CapturePreset.D.faceDelegate)
        assertEquals(12, CapturePreset.E.faceRateHz)
        assertEquals(2, CapturePreset.E.frameProcessDivisor, "legacy header field kept for E")
        assertEquals(15, CapturePreset.E15.faceRateHz)
        assertEquals(CameraFpsRequest.Default, CapturePreset.E15.cameraFps, "E15 keeps the 24 fps camera")
        assertEquals(35, CapturePreset.G.perfHintTargetMs)
        assertEquals(CameraFpsRequest.Fixed(12), CapturePreset.H12.cameraFps)
        assertEquals(CameraFpsRequest.Fixed(15), CapturePreset.H15.cameraFps)
        assertEquals(CameraFpsRequest.VariableUpper(15), CapturePreset.Hvar.cameraFps)
        for (h in listOf(CapturePreset.H12, CapturePreset.H15, CapturePreset.Hvar)) {
            assertNull(h.faceRateHz, "${h.id}: Face on every frame, the camera cadence is the only variable")
            assertEquals(FaceSchedule.EVERY_FRAME, h.faceSchedule)
            assertTrue(h.isCameraCadencePreset)
        }
        assertEquals(FaceSchedule.SLOT, CapturePreset.E.faceSchedule)
        assertEquals(FaceSchedule.SLOT, CapturePreset.E15.faceSchedule)
        assertEquals(listOf("A", "B", "C", "D", "E", "E15", "G", "H12", "H15", "Hvar"), CapturePreset.entries.map { it.id })
        assertEquals(CapturePreset.A, CapturePreset.byId(null))
        assertEquals(CapturePreset.A, CapturePreset.byId("F"), "F (ROI crop) is not implemented; unknown ids fall back to A")
        assertEquals(CapturePreset.Hvar, CapturePreset.byId("Hvar"))
    }

    @Test
    fun fpsRequestsSelectTheirRangeAndTheHVariantsAreListedOnlyWhenSupported() {
        assertEquals(FpsRange(24, 24), CameraFpsRequest.Default.select(galaxyRanges))
        assertEquals(FpsRange(30, 30), CameraFpsRequest.Default.select(listOf(FpsRange(15, 30), FpsRange(30, 30))))
        assertNull(CameraFpsRequest.Default.select(listOf(FpsRange(15, 30))), "no [24,24] and no [30,30]: no request, HAL default")
        assertTrue(CameraFpsRequest.Default.isAvailable(listOf(FpsRange(15, 30))), "the default presets always run")
        assertEquals(FpsRange(12, 12), CameraFpsRequest.Fixed(12).select(galaxyRanges))
        assertEquals(FpsRange(7, 15), CameraFpsRequest.VariableUpper(15).select(galaxyRanges), "the widest variable range under the bound")
        assertEquals(FpsRange(10, 15), CameraFpsRequest.VariableUpper(15).select(listOf(FpsRange(10, 15), FpsRange(15, 15))))
        assertNull(CameraFpsRequest.VariableUpper(15).select(listOf(FpsRange(15, 15), FpsRange(24, 24))), "[15,15] is fixed, not variable")
        val only15 = listOf(FpsRange(8, 15), FpsRange(15, 15), FpsRange(24, 24))
        assertEquals(listOf("A", "B", "C", "D", "E", "E15", "G", "H15", "Hvar"), CapturePreset.availableOn(only15).map { it.id }, "[12,12] missing: H12 hidden, E15 always shown")
        assertEquals(CapturePreset.entries, CapturePreset.availableOn(galaxyRanges))
        assertEquals(listOf("A", "B", "C", "D", "E", "E15", "G"), CapturePreset.availableOn(listOf(FpsRange(24, 24), FpsRange(30, 30))).map { it.id })
    }

    @Test
    fun gapThresholdsAreTheFormulaOfTheExpectedProcessingInterval() {
        // every-frame presets at 24 fps: 62.5 / 187.5 ms — same classification as the old 80 / 200 ms
        for (p in listOf(CapturePreset.A, CapturePreset.B, CapturePreset.C, CapturePreset.D, CapturePreset.G)) {
            assertEquals(GapThresholds.frameIntervalNs(24), p.faceProcessPeriodNs(24), p.id)
            assertEquals(62_500_000L, p.gapThresholdNs(24), p.id)
            assertEquals(63, p.gapThresholdMs(24), p.id)
            assertEquals(188, p.longGapThresholdMs(24), p.id)
            assertNull(p.slotPeriodNs())
        }
        // slot presets: the slot period, whatever the camera cadence
        assertEquals(GapThresholds.periodNs(12.0), CapturePreset.E.faceProcessPeriodNs(24))
        assertEquals(125, CapturePreset.E.gapThresholdMs(24))
        assertEquals(375, CapturePreset.E.longGapThresholdMs(24))
        assertEquals(GapThresholds.periodNs(15.0), CapturePreset.E15.faceProcessPeriodNs(24))
        assertEquals(100, CapturePreset.E15.gapThresholdMs(24))
        assertEquals(300, CapturePreset.E15.longGapThresholdMs(24))
        assertEquals(GapThresholds.periodNs(12.0), CapturePreset.E.slotPeriodNs())
        // camera-cadence presets: the camera frame interval at their cadence
        assertEquals(125, CapturePreset.H12.gapThresholdMs(12))
        assertEquals(375, CapturePreset.H12.longGapThresholdMs(12))
        assertEquals(100, CapturePreset.H15.gapThresholdMs(15))
        assertEquals(300, CapturePreset.H15.longGapThresholdMs(15))
        assertEquals(100, CapturePreset.Hvar.gapThresholdMs(15), "Hvar: computed at the upper bound, reported as n/a and never judged")
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
}
