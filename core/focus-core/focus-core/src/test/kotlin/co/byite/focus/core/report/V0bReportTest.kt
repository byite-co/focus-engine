package co.byite.focus.core.report

import co.byite.focus.core.Synth
import co.byite.focus.core.aggregate.CounterTotals
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.V0bRawRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V0bReportTest {
    private fun raw(sec: Long, label: String?, face: Double = 12.0, thermal: Int = 0, battery: Int? = 80, current: Int? = -400_000, voltage: Int? = 4000, interactive: Boolean = false, idle: Boolean = false, total: Double = face + 4, poseRequested: Int = 1, poseSamples: Int = 1, gapCauseFace: Int = 0, gapCauseOther: Int = 0, hinge: Double? = null) =
        V0bRawRecord(
            tMonoMs = Synth.mono(sec), segmentLabel = label,
            shoulderCenterX = 0.5, shoulderCenterY = 0.55, shoulderWidth = 0.4, poseSamples = poseSamples,
            tileTextureMin = 4.0, tileTextureMedian = 10.0, sceneSamples = 1,
            faceInferMsMean = face, faceInferMsP95 = face + 5, faceInferMsMax = face + 10,
            poseInferMsMean = 30.0, poseInferMsMax = 35.0, frameLatencyMsMean = 30.0,
            stageWrapMsMean = 0.5, stageFacePostMsMean = 0.3, stageSceneMsMean = 2.0,
            poseFrameCopyMsMean = 1.5, poseFrameCopyMsP95 = 1.8, poseFrameCopyMsMax = 2.0,
            frameTotalMsMean = total, frameTotalMsP95 = total + 5, frameTotalMsMax = total + 10, poseWaitMsMean = 1.0,
            stageEnqueueMsMean = 0.02, stageEnqueueMsP95 = 0.03, stageEnqueueMsMax = 0.05, poseInferMsP95 = 33.0,
            poseRequested = poseRequested, poseCompleted = poseSamples, poseApplied = poseSamples,
            gapCauseFace = gapCauseFace, gapCauseOther = gapCauseOther, hingeAngleDeg = hinge,
            imuSamples = 5, accelXMean = 0.1, accelYMean = 7.2, accelZMean = 6.6, accelVariance = 0.002,
            thermalStatus = thermal, batteryPct = battery, batteryCurrentUa = current, batteryVoltageMv = voltage,
            isInteractive = interactive, isDeviceIdle = idle,
        )

    // ---- small fixture (missing second, zero-frame second)
    private val records = listOf(
        Synth.record(0, face = true).copy(yawMean = 2.0, pitchMean = -8.0, faceWidthPx = 210.0, jitterJ = 0.004),
        Synth.record(1, face = true).copy(yawMean = 4.0, pitchMean = -6.0, faceWidthPx = 190.0, jitterJ = 0.006, shoulderVisibilityMin = 0.5),
        Synth.record(2, face = false).copy(framesProcessed = 0, framesRequested = 24, framesDropped = 24, framesAnalyzerReceived = 0, framesSampleApplied = 0),
        // second 3 is missing
        Synth.record(4, face = true).copy(faceDetectRatio = 0.5, yawMean = 0.0, pitchMean = -7.0, faceWidthPx = 200.0, jitterJ = 0.02),
    )
    private val raws = listOf(
        raw(0, "정면", face = 10.0, thermal = 1, battery = 90, interactive = true),
        raw(1, "정면", face = 20.0, battery = 89, idle = true),
        raw(2, "자리비움", face = 30.0, battery = 88, poseSamples = 0),
        raw(4, null, face = 40.0, battery = 87, current = null, voltage = null),
    )
    private val log = SessionLog(Synth.header, records, sessionEnd = SessionEnd(Synth.mono(6), Synth.utc(6), SessionEndReason.USER), timebase = listOf(Synth.timebase(0)), v0bRaw = raws)

    @Test
    fun overallCountsFramesMissingSecondsAndInferenceTime() {
        val o = V0bReport.build(log).overall
        assertEquals(6.0, o.sessionLengthS)
        assertEquals(4, o.records)
        assertEquals(2L, o.missingRecords) // 6 expected buckets − 4 records
        assertEquals(1, o.zeroFrameRecords)
        assertEquals(3L, o.missingSeconds)
        assertEquals(96L, o.framesRequested)
        assertEquals(72L, o.framesProcessed)
        assertEquals(24L, o.framesDropped)
        assertEquals(24L, o.frames.backpressureDrops)
        assertEquals(0L, o.frames.unprocessedUnexpected)
        assertEquals(0.25, o.dropRatio)
        assertEquals(18.0, o.processedFps)
        assertEquals((10.0 * 24 + 20.0 * 24 + 40.0 * 24) / 72, o.faceInferMsMean!!, 1e-9)
        assertEquals(40.0, o.faceInferMsP95OfSecondMeans)
        assertEquals(50.0, o.faceInferMsMax)
        assertEquals(4L, o.pose.requested)
        assertEquals(3L, o.pose.applied)
        assertEquals(30.0, o.poseInferMsMean)
        assertEquals(1, o.maxThermalStatus)
        assertEquals(90, o.batteryStartPct)
        assertEquals(87, o.batteryEndPct)
        assertEquals(-400_000.0, o.meanCurrentUa)
        assertEquals(1600.0, o.meanPowerMw!!, 1e-9) // 400000 µA × 4000 mV / 1e6
        assertEquals(3, o.screenOffSeconds)
        assertEquals(1, o.idleSeconds)
        assertEquals(1, o.timebaseLines)
        assertEquals("USER", o.endReason)
        assertEquals("640x480 (4:3) @ 24fps", o.camera)
        assertEquals(80, o.gapThresholdMs)
    }

    @Test
    fun segmentsGroupByMarkerInOrderOfFirstAppearance() {
        val s = V0bReport.build(log)
        assertEquals(listOf("정면", "자리비움", V0bReport.NO_LABEL), s.segments.map { it.label })
        val front = s.segments[0]
        assertEquals(2, front.seconds)
        assertEquals(1.0, front.faceDetectRatio)
        assertEquals(3.0, front.yawMean)
        assertEquals(1.0, front.yawSd)
        assertEquals(-7.0, front.pitchMean)
        assertEquals(200.0, front.faceWidthMedianPx)
        assertEquals(0.005, front.jitterMedian!!, 1e-12)
        assertEquals(0.006, front.jitterP95!!, 1e-12)
        assertEquals(0.5, front.shoulderVisibleRatio) // second 1 has 0.5 < 0.6
        assertEquals(1.0, front.headLandmarkRatio)
        assertEquals(-0.8, front.headOffsetMedian)
        assertEquals(120.0, front.lumaMean)
        val away = s.segments[1]
        assertNull(away.faceDetectRatio, "no applied samples")
        assertEquals(0.0, away.headLandmarkRatio)
        val none = s.segments[2]
        assertEquals(0.5, none.faceDetectRatio)
    }

    @Test
    fun aShortSessionIsAllWarmupAndCannotBeJudged() {
        val s = V0bReport.build(log)
        assertEquals(4, s.warmupRow.seconds)
        assertEquals(0, s.offRow.seconds)
        assertEquals(0, s.onRow.seconds)
        assertNull(s.pass.pass)
        assertTrue("판정 불가" in s.render(), s.render())
    }

    @Test
    fun renderIsDeterministicMentionsEverySegmentAndSaysWhenTheCounterCheckWasSkipped() {
        val text = V0bReport.build(log, notes = listOf("테스트 노트")).render()
        assertEquals(text, V0bReport.build(log, notes = listOf("테스트 노트")).render())
        assertTrue(text.startsWith(V0bReport.COUNTER_CHECK_SKIPPED + "\nfocus-engine V0-A/B 요약  세션 S-synth"), text)
        assertTrue("[정면] 2s" in text && "[자리비움] 1s" in text && "[${V0bReport.NO_LABEL}] 1s" in text, text)
        assertTrue("누락된 초: 3 (레코드 없는 초 2 + 프레임 0인 초 1)" in text, text)
        assertTrue("카메라 640x480 (4:3) @ 24fps(CameraX 실제 선택)" in text, text)
        assertTrue(text.endsWith("※ 테스트 노트"), text)
    }

    @Test
    fun counterCheckResultLeadsTheSummary() {
        val ok = V0bReport.build(log, stop = StopSummary(checked = true, totals = CounterTotals())).render()
        assertTrue(ok.startsWith(V0bReport.COUNTER_OK + "\n"), ok)
        val bad = V0bReport.build(log, stop = StopSummary(checked = true, mismatches = listOf("pose_requested 3 ≠ …", "frames_analyzer_received 1 ≠ …"), poseCancelledAtStop = 1)).render()
        assertTrue(bad.startsWith("계수 불일치: pose_requested 3 ≠ …\n계수 불일치: frames_analyzer_received 1 ≠ …\nfocus-engine"), bad)
        assertTrue("종료 시 취소 1" in bad, bad)
    }

    @Test
    fun withoutSessionEndTheLengthRunsToTheLastRecord() {
        val o = V0bReport.build(log.copy(sessionEnd = null)).overall
        assertEquals(5.0, o.sessionLengthS)
        assertEquals(1L, o.missingRecords)
        assertTrue(o.endReason.startsWith("없음"))
    }

    @Test
    fun recoveredLogGivesTheSameSummaryAsTheLiveRecords() {
        val text = JsonlCodec.encode(log)
        assertEquals(V0bReport.build(log), V0bReport.build(JsonlCodec.decode(text)))
    }

    // ---- long fixture: 130 s, screen on 0–59 (warm-up), off 60–99, on 100–129
    private fun longRecord(sec: Long): SecondRecord = when (sec) {
        70L -> Synth.record(sec).copy(framesRequested = 24, framesAnalyzerReceived = 22, framesProcessed = 22, framesSampleApplied = 22, framesDropped = 2, gapsOver80Ms = 1, gapsOverThreshold = 1, maxFrameGapMs = 125)
        30L -> Synth.record(sec).copy(gapsOver80Ms = 2, gapsOverThreshold = 2, gapsOverLongThreshold = 1, maxFrameGapMs = 250)
        110L -> Synth.record(sec).copy(framesRequested = 24, framesAnalyzerReceived = 24, framesSkippedIntentional = 0, framesProcessed = 23, framesSampleApplied = 23, framesDropped = 1)
        else -> Synth.record(sec)
    }
    private fun screenOn(sec: Long): Boolean = sec < 60 || sec >= 100
    private val longRecords = (0L until 130L).map { longRecord(it) }
    private val longRaws = (0L until 130L).map { sec ->
        raw(
            sec, if (sec < 70) "정면" else null, face = if (screenOn(sec)) 30.0 else 36.0, thermal = if (sec > 120) 2 else 1, interactive = screenOn(sec),
            current = if (screenOn(sec)) -500_000 else -400_000, gapCauseFace = if (sec == 70L) 1 else 0, gapCauseOther = if (sec == 30L) 2 else 0, hinge = if (sec < 100) 179.0 else 90.0,
        )
    }
    private val longLog = SessionLog(
        Synth.header.copy(capturePreset = "A", cameraResolution = "1280x720", cameraId = "1", lensFacing = "FRONT", foldable = true),
        longRecords, sessionEnd = SessionEnd(Synth.mono(130), Synth.utc(130), SessionEndReason.USER), v0bRaw = longRaws,
    )

    @Test
    fun rowsExcludeWarmupAndTransitionSecondsAndSplitByScreenState() {
        val s = V0bReport.build(longLog)
        assertEquals(60, s.warmupRow.seconds)
        assertEquals(10, s.transitionRow.seconds, "5 s after 60 and 5 s after 100")
        assertEquals(35, s.offRow.seconds)
        assertEquals(25, s.onRow.seconds)
        val off = s.offRow
        assertEquals(35L * 24, off.frames.requested)
        assertEquals(35L * 24 - 2, off.frames.processed)
        assertEquals(2L, off.frames.backpressureDrops)
        assertEquals(2L, off.frames.dropped)
        assertEquals((35.0 * 24 - 2) / 35, off.processedFps!!, 1e-9)
        assertEquals(2.0 / (35 * 24), off.frames.dropRatio!!, 1e-12)
        assertEquals(1L, off.gapsOverThreshold)
        assertEquals(1.0 / (35 * 24 - 2), off.gapRatio!!, 1e-12)
        assertEquals(0L, off.gapsOverLongThreshold)
        assertEquals(125L, off.maxFrameGapMs)
        assertEquals(listOf("Face" to 1L), off.topGapCauses)
        assertEquals(listOf("그 외" to 2L), s.warmupRow.topGapCauses)
        assertEquals(1L, s.warmupRow.gapsOverLongThreshold)
        assertEquals(0.02, off.enqueue.mean!!, 1e-12)
        assertEquals(0.05, off.enqueue.max)
        assertEquals(30.0, off.poseInfer.p95OfSecondMeans, "p95 of the per-second means, like every stage")
        assertEquals(35.0, off.poseInfer.max)
        assertEquals(36.0, off.faceInferMsMean!!, 1e-9)
        assertEquals(40.0, off.frameTotalMsMean!!, 1e-9)
        assertEquals(0.5, off.stageWrapMsMean!!, 1e-9)
        assertEquals(1.5, off.poseFrameCopyMsMean!!, 1e-9)
        assertEquals(1600.0, off.meanPowerMw!!, 1e-9)
        assertEquals(-400_000.0, off.meanCurrentUa!!, 1e-9)
        assertEquals(1, off.maxThermalStatus)
        val on = s.onRow
        assertEquals(30.0, on.faceInferMsMean!!, 1e-9)
        assertEquals(2000.0, on.meanPowerMw!!, 1e-9)
        assertEquals(2, on.maxThermalStatus)
        assertEquals(1L, on.frames.unprocessedUnexpected)
    }

    @Test
    fun passMarkUsesTheOffRowAndThePresetDivisor() {
        val s = V0bReport.build(longLog)
        assertEquals("A", s.pass.preset)
        assertEquals(23.5, s.pass.fpsMin)
        assertEquals(true, s.pass.fpsOk)
        assertEquals(true, s.pass.gapOk)
        assertEquals(true, s.pass.dropOk)
        assertEquals(true, s.pass.longGapOk, "the long gap at second 30 is warm-up, not the off row")
        assertEquals(200, s.pass.longGapThresholdMs)
        assertEquals(true, s.pass.pass)
        assertEquals(1600.0, s.pass.offMeanPowerMw!!, 1e-9)
        assertEquals(1, s.pass.offMaxThermalStatus)
        val e = V0bReport.build(longLog.copy(header = longLog.header.copy(capturePreset = "E", frameProcessDivisor = 2, frameGapThresholdMs = 167, frameLongGapThresholdMs = 417)))
        assertEquals(11.75, e.pass.fpsMin)
        assertEquals(417, e.pass.longGapThresholdMs)
        assertEquals(true, e.pass.pass)
        val text = s.render()
        assertTrue("합격(프리셋 A, 화면 off 행 기준): 합격 — fps 23.94 ≥ 23.50 OK" in text, text)
        assertTrue("200ms 초과 갭 0 = 0 OK" in text, text)
        assertTrue("화면 off 평균 전력 1600 mW, 최고 thermal 1 (LIGHT)" in text, text)
        assertTrue("갭>80ms 원인: Face 1" in text, text)
        assertTrue("카메라 id 1 (FRONT) 1280x720 (16:9) @ 24fps(CameraX 실제 선택)  접힘 상태: 펼침 77%, 반접힘 23% (hinge 평균 158°)" in text, text)
        // one long gap on the off row fails the pass mark
        val longGap = longRecords.map { if ((it.tMonoMs - Synth.T0) / 1000 == 80L) it.copy(gapsOverThreshold = 1, gapsOver80Ms = 1, gapsOverLongThreshold = 1, maxFrameGapMs = 300) else it }
        val lg = V0bReport.build(longLog.copy(records = longGap))
        assertEquals(false, lg.pass.longGapOk)
        assertEquals(false, lg.pass.pass)
        // a slow off row fails on fps
        val slow = longRecords.map { if (!screenOn((it.tMonoMs - Synth.T0) / 1000)) it.copy(framesProcessed = 20, framesAnalyzerReceived = 20, framesSampleApplied = 20, framesDropped = 4, gapsOverThreshold = 0, maxFrameGapMs = null) else it }
        val f = V0bReport.build(longLog.copy(records = slow))
        assertEquals(false, f.pass.fpsOk)
        assertEquals(false, f.pass.dropOk)
        assertEquals(false, f.pass.pass)
        assertTrue("불합격" in f.render())
    }

    @Test
    fun screenSegmentsListEveryRunWithTheExcludedSeconds() {
        val segs = V0bReport.build(longLog).screenSegments
        assertEquals(3, segs.size)
        assertEquals(listOf(0L, 60L, 100L), segs.map { it.startS })
        assertEquals(listOf(60L, 100L, 130L), segs.map { it.endS })
        assertEquals(listOf(true, false, true), segs.map { it.screenOn })
        assertEquals(listOf(60, 5, 5), segs.map { it.excludedSeconds })
        assertEquals(36.0, segs[1].faceInferMsMean!!, 1e-9)
        assertEquals(2.0 / (40 * 24), segs[1].dropRatio!!, 1e-12)
        assertEquals(1600.0, segs[1].meanPowerMw!!, 1e-9)
    }

    @Test
    fun trendTableHasOneRowPerTenSecondsWithPoseMeansAndFaceRatio() {
        val s = V0bReport.build(longLog)
        val t = s.trend
        assertEquals(10L, s.trendWindowS)
        assertEquals(13, t.size)
        assertEquals((0L until 13L).map { it * 10 }.toList(), t.map { it.startS })
        assertTrue(t.all { it.seconds == 10 })
        assertEquals(24.0, t[0].processedFps)
        assertEquals(1.0, t[0].faceDetectRatio)
        assertEquals(0.0, t[0].yawMean)
        assertEquals(-5.0, t[0].pitchMean)
        assertEquals(0.0, t[0].rollMean)
        assertEquals(10, t[0].screenOnSeconds)
        assertEquals(0, t[6].screenOnSeconds)
        assertEquals(23.8, t[7].processedFps!!, 1e-9)
        assertEquals(2.0 / 240, t[7].dropRatio!!, 1e-12)
        assertEquals(1L, t[7].gapsOverThreshold)
        assertEquals(2, t[12].maxThermalStatus)
        val text = s.render()
        assertTrue("[10초 추이]" in text && "[화면 상태 구간]" in text && "[비교 통계]" in text, text)
    }

    @Test
    fun trendWindowGrowsToKeepAtMostSixtyRows() {
        // 1800 s → 180 ten-second windows → 30 s windows, 60 rows
        val records = (0L until 1800L).map { Synth.record(it) }
        val s = V0bReport.build(SessionLog(Synth.header, records, sessionEnd = SessionEnd(Synth.mono(1800), Synth.utc(1800), SessionEndReason.USER)))
        assertEquals(30L, s.trendWindowS)
        assertEquals(60, s.trend.size)
        assertTrue(s.trend.all { it.seconds == 30 })
        assertTrue("[30초 추이]" in s.render())
        // 601 s → 61 windows → 20 s windows, 31 rows
        val s2 = V0bReport.build(SessionLog(Synth.header, (0L until 601L).map { Synth.record(it) }))
        assertEquals(20L, s2.trendWindowS)
        assertEquals(31, s2.trend.size)
    }

    @Test
    fun foldLineDescribesTheHingeAngleOrItsAbsence() {
        assertEquals("폴더블 아님", V0bReport.foldLine(Synth.header.copy(foldable = false), longRaws))
        assertEquals("접힘 상태 미상 (hinge 값 없음)", V0bReport.foldLine(Synth.header.copy(foldable = true), raws))
        assertEquals("-", V0bReport.foldLine(Synth.header, raws))
        assertEquals("", V0bReport.build(log).overall.cameraIdLine)
    }

    @Test
    fun theLongLogRoundTripsToTheSameSummary() {
        assertEquals(V0bReport.build(longLog), V0bReport.build(JsonlCodec.decode(JsonlCodec.encode(longLog))))
    }
}
