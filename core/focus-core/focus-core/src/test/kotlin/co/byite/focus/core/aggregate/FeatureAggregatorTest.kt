package co.byite.focus.core.aggregate

import co.byite.focus.core.Synth
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.PowerState
import co.byite.focus.core.model.ScreenState
import co.byite.focus.core.model.ZoneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureAggregatorTest {
    private val t0 = Synth.T0
    private val device = DeviceSample(thermalStatus = 1, batteryPct = 80, batteryCurrentUa = -350_000, batteryVoltageMv = 4000, isInteractive = false, isDeviceIdle = true, screenState = ScreenState.OFF, appState = AppState.BACKGROUND)

    private fun ns(ms: Long): Long = ms * 1_000_000L
    /** Aggregator with the first-frame scene sample the device layer guarantees (session start = first processed frame). */
    private fun agg(delay: Long = 300) = FeatureAggregator(t0, Synth.UTC0, closeDelayMs = delay).also { it.onScene(SceneSample(ns(t0 + 5), 118.0, 4.0, 9.0)) }

    private fun frame(ms: Long, face: Boolean = true, yaw: Double = 1.0, pitch: Double = -5.0, roll: Double = 0.5, width: Double = 200.0, j: Double? = 0.01, infer: Double = 15.0) =
        if (face) FrameSample(ns(t0 + ms), 30_000_000L, true, yaw, pitch, roll, width, j, infer)
        else FrameSample(ns(t0 + ms), 30_000_000L, false, faceInferMs = infer)

    private fun pose(ms: Long, cx: Double = 640.0, cy: Double = 700.0, w: Double = 300.0, vis: Double = 0.9, head: Boolean = true, offset: Double? = -0.8, infer: Double = 30.0) =
        PoseSample(ns(t0 + ms), infer, true, vis, cx, cy, w, head, if (head) offset else null, 720, 1280)

    @Test
    fun bucketsAlignToSessionStartAndCloseAfterTheDelay() {
        val a = agg()
        a.onFrameRequested(ns(t0 + 10))
        a.onFrame(frame(10))
        assertTrue(a.closeBuckets(t0 + 1000, device).isEmpty(), "bucket 0 ends at +1000 but the delay has not passed")
        assertTrue(a.closeBuckets(t0 + 1299, device).isEmpty())
        val closed = a.closeBuckets(t0 + 1300, device)
        assertEquals(1, closed.size)
        val s = closed[0].second
        assertEquals(t0, s.tMonoMs)
        assertEquals(Synth.UTC0, s.tUtcMs)
        assertEquals(1, s.framesRequested)
        assertEquals(1, s.framesProcessed)
        assertEquals(0, s.framesDropped)
        assertEquals(1.0, s.faceDetectRatio)
        assertEquals(1L, a.closedBuckets)
    }

    @Test
    fun emptySecondsStillProduceRecords() {
        val a = agg()
        a.onFrame(frame(2500))
        val closed = a.closeBuckets(t0 + 3300, device)
        assertEquals(listOf(t0, t0 + 1000, t0 + 2000), closed.map { it.second.tMonoMs })
        assertEquals(0, closed[0].second.framesProcessed)
        assertEquals(0.0, closed[0].second.faceDetectRatio)
        assertNull(closed[0].second.yawMean)
        assertEquals(1, closed[2].second.framesProcessed)
    }

    @Test
    fun requestedProcessedAndDroppedAreCountedSeparately() {
        val a = agg()
        for (i in 0 until 24) a.onFrameRequested(ns(t0 + i * 41L))
        for (i in 0 until 20) a.onFrame(frame(i * 41L))
        val s = a.closeBuckets(t0 + 1300, device)[0].second
        assertEquals(24, s.framesRequested)
        assertEquals(20, s.framesProcessed)
        assertEquals(4, s.framesDropped)
    }

    @Test
    fun withoutCaptureResultsRequestedFallsBackToProcessed() {
        val a = agg()
        for (i in 0 until 20) a.onFrame(frame(i * 41L))
        val s = a.closeBuckets(t0 + 1300, device)[0].second
        assertEquals(20, s.framesRequested)
        assertEquals(0, s.framesDropped)
    }

    @Test
    fun gapsAreMeasuredBetweenProcessedFramesAcrossBuckets() {
        val a = agg()
        a.onFrame(frame(900))
        a.onFrame(frame(1000)) // gap 100 ms, belongs to bucket 1
        a.onFrame(frame(1041))
        a.onFrame(frame(1200)) // gap 159 ms
        val closed = a.closeBuckets(t0 + 2300, device)
        assertNull(closed[0].second.maxFrameGapMs)
        assertEquals(0, closed[0].second.gapsOver80Ms)
        assertEquals(159L, closed[1].second.maxFrameGapMs)
        assertEquals(2, closed[1].second.gapsOver80Ms)
    }

    @Test
    fun faceScalarsAreAveragedOverFaceFramesOnly() {
        val a = agg()
        a.onFrame(frame(0, yaw = 10.0, pitch = -10.0, roll = 2.0, width = 100.0, j = 0.02, infer = 10.0))
        a.onFrame(frame(41, yaw = 20.0, pitch = -20.0, roll = 4.0, width = 300.0, j = 0.04, infer = 30.0))
        a.onFrame(frame(82, face = false, infer = 20.0))
        val (s, raw) = a.closeBuckets(t0 + 1300, device)[0]
        assertEquals(2.0 / 3.0, s.faceDetectRatio, 1e-12)
        assertEquals(15.0, s.yawMean)
        assertEquals(-15.0, s.pitchMean)
        assertEquals(3.0, s.rollMean)
        assertEquals(200.0, s.faceWidthPx)
        assertEquals(0.03, s.jitterJ!!, 1e-12)
        assertEquals(20.0, raw.faceInferMsMean)
        assertEquals(30.0, raw.faceInferMsP95)
        assertEquals(30.0, raw.faceInferMsMax)
        assertEquals(30.0, raw.frameLatencyMsMean)
    }

    @Test
    fun poseFieldsComeFromTheLastDetectedSampleAndAreNormalised() {
        val a = agg()
        a.onPose(pose(100, cx = 100.0, cy = 200.0, w = 100.0, vis = 0.5, head = false, infer = 20.0))
        a.onPose(pose(500, cx = 360.0, cy = 640.0, w = 180.0, vis = 0.95, head = true, offset = -0.7, infer = 40.0))
        a.onPose(PoseSample(ns(t0 + 900), 25.0, detected = false, frameWidthPx = 720, frameHeightPx = 1280))
        val (s, raw) = a.closeBuckets(t0 + 1300, device)[0]
        assertEquals(0.95, s.shoulderVisibilityMin)
        assertTrue(s.headLandmarkPresent)
        assertEquals(-0.7, s.headOffsetBelowShoulderRatio)
        assertEquals(0.5, raw.shoulderCenterX)
        assertEquals(0.5, raw.shoulderCenterY)
        assertEquals(0.25, raw.shoulderWidth)
        assertEquals(3, raw.poseSamples)
        assertEquals((20.0 + 40.0 + 25.0) / 3, raw.poseInferMsMean!!, 1e-12)
        assertEquals(40.0, raw.poseInferMsMax)
        assertNull(s.poseMotion, "no reference one second earlier")
    }

    @Test
    fun poseMotionUsesTheSampleAboutOneSecondEarlierDividedByShoulderWidth() {
        val a = agg()
        a.onPose(pose(0, cx = 100.0, cy = 100.0, w = 200.0))
        a.onPose(pose(333, cx = 110.0, cy = 100.0, w = 200.0))
        a.onPose(pose(666, cx = 120.0, cy = 100.0, w = 200.0))
        a.onPose(pose(1000, cx = 130.0, cy = 140.0, w = 200.0)) // vs sample at 0: (30, 40) → 50 / 200
        val closed = a.closeBuckets(t0 + 2300, device)
        assertNull(closed[0].second.poseMotion)
        assertEquals(0.25, closed[1].second.poseMotion!!, 1e-12)
    }

    @Test
    fun poseMotionIsNullWhenTheReferenceIsTooOld() {
        val a = agg()
        a.onPose(pose(0))
        a.onPose(pose(3500, cx = 700.0))
        val closed = a.closeBuckets(t0 + 4300, device)
        assertNull(closed[3].second.poseMotion)
    }

    @Test
    fun sceneLumaIsMeasuredAndCarriesForwardOnlyWhenTheBucketHasNoSample() {
        val a = FeatureAggregator(t0, Synth.UTC0)
        a.onScene(SceneSample(ns(t0 + 100), 100.0, 3.0, 8.0))
        a.onScene(SceneSample(ns(t0 + 600), 120.0, 2.0, 10.0))
        a.onScene(SceneSample(ns(t0 + 2100), 90.0, 1.0, 7.0))
        val closed = a.closeBuckets(t0 + 3300, device)
        assertEquals(110.0, closed[0].second.sceneLuma)
        assertEquals(2.0, closed[0].raw.tileTextureMin)
        assertEquals(9.0, closed[0].raw.tileTextureMedian)
        assertEquals(2, closed[0].raw.sceneSamples)
        assertEquals(110.0, closed[1].second.sceneLuma, "no sample in the bucket: last measured value")
        assertEquals(0, closed[1].raw.sceneSamples)
        assertNull(closed[1].raw.tileTextureMin)
        assertNull(closed[1].second.bgTileTextureRatio)
        assertEquals(90.0, closed[2].second.sceneLuma)
    }

    @Test
    fun aBucketClosedBeforeAnySceneSampleIsAContractViolationNotAConstant() {
        val a = FeatureAggregator(t0, Synth.UTC0)
        a.onFrame(frame(0))
        assertFailsWith<IllegalStateException> { a.closeBuckets(t0 + 1300, device) }
    }

    @Test
    fun imuMeansAndVarianceAreSummedPerAxis() {
        val a = agg()
        a.onImu(ImuSample(ns(t0 + 0), 0.0, 9.0, 1.0))
        a.onImu(ImuSample(ns(t0 + 200), 2.0, 9.0, 3.0))
        val raw = a.closeBuckets(t0 + 1300, device)[0].raw
        assertEquals(2, raw.imuSamples)
        assertEquals(1.0, raw.accelXMean)
        assertEquals(9.0, raw.accelYMean)
        assertEquals(2.0, raw.accelZMean)
        assertEquals(2.0, raw.accelVariance!!, 1e-9) // var x = 1, var y = 0, var z = 1
    }

    @Test
    fun segmentLabelIsTheOneActiveAtBucketStart() {
        val a = agg()
        a.setSegmentLabel("정면", t0 + 1500)
        a.setSegmentLabel("정지", t0 + 3000)
        a.setSegmentLabel(null, t0 + 4200)
        val closed = a.closeBuckets(t0 + 6300, device)
        assertEquals(listOf(null, null, "정면", "정지", "정지", null), closed.map { it.raw.segmentLabel })
    }

    @Test
    fun placeholdersForFieldsWithoutCalibrationOrGates() {
        val a = agg()
        a.onFrame(frame(0))
        val s = a.closeBuckets(t0 + 1300, device)[0].second
        assertNull(s.rawState)
        assertNull(s.finalState)
        assertNull(s.invalidReason)
        assertNull(s.candidateState)
        assertTrue(s.events.isEmpty())
        assertNull(s.torsoCenterOffsetRatio)
        assertNull(s.torsoWidthRatio)
        assertEquals(ZoneStatus.UNCALIBRATED, s.zoneStatus)
        assertNull(s.zoneId)
        assertEquals(ImuState.UNKNOWN, s.imuState)
        assertEquals(PowerState.P0, s.powerState)
        assertEquals(ScreenState.OFF, s.screenState)
        assertEquals(AppState.BACKGROUND, s.appState)
    }

    @Test
    fun lateAndEarlyInputsAreCountedAndDropped() {
        val a = agg()
        a.onFrame(frame(-10))
        assertEquals(1L, a.inputsBeforeStart)
        a.closeBuckets(t0 + 1300, device)
        a.onFrame(frame(500))
        a.onFrameRequested(ns(t0 + 500))
        assertEquals(2L, a.lateInputs)
        val next = a.closeBuckets(t0 + 2300, device)
        assertEquals(1, next.size)
        assertEquals(0, next[0].second.framesProcessed)
    }

    @Test
    fun finishClosesCompleteBucketsOnlyAndDropsThePartialOne() {
        val a = agg()
        a.onFrame(frame(0))
        a.onFrame(frame(1000))
        a.onFrame(frame(2000))
        val closed = a.finish(t0 + 2500, device)
        assertEquals(listOf(t0, t0 + 1000), closed.map { it.second.tMonoMs })
        assertEquals(2L, a.closedBuckets)
    }

    @Test
    fun deviceSampleFillsTheRawLine() {
        val a = agg()
        val raw = a.closeBuckets(t0 + 1300, device)[0].raw
        assertEquals(1, raw.thermalStatus)
        assertEquals(80, raw.batteryPct)
        assertEquals(-350_000, raw.batteryCurrentUa)
        assertEquals(4000, raw.batteryVoltageMv)
        assertEquals(false, raw.isInteractive)
        assertEquals(true, raw.isDeviceIdle)
    }

    @Test
    fun outputRoundTripsThroughTheJsonlCodecAndIsDeterministic() {
        fun run(): List<AggregatedSecond> {
            val a = agg()
            a.setSegmentLabel("정면", t0)
            for (i in 0 until 48) {
                a.onFrameRequested(ns(t0 + i * 41L))
                a.onFrame(frame(i * 41L, yaw = i * 0.1, j = if (i == 0) null else 0.005))
            }
            a.onPose(pose(10))
            a.onPose(pose(1010, cx = 650.0))
            a.onScene(SceneSample(ns(t0 + 10), 118.0, 5.0, 12.0))
            a.onImu(ImuSample(ns(t0 + 0), 0.1, 7.2, 6.6))
            return a.closeBuckets(t0 + 2300, device)
        }
        val first = run()
        assertEquals(first, run())
        val log = SessionLog(Synth.header, first.map { it.second }, v0bRaw = first.map { it.raw })
        val text = JsonlCodec.encode(log)
        val decoded = JsonlCodec.decode(text)
        assertEquals(log, decoded)
        assertEquals(2, decoded.v0bRaw.size)
        assertNotNull(decoded.records[1].poseMotion)
        assertTrue(text.lines()[2].startsWith("{\"type\":\"v0b_raw\",\"t_mono_ms\":$t0,"), text.lines()[2])
    }
}
