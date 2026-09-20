package co.byite.focus.core.report

import co.byite.focus.core.Synth
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.V0bRawRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V0bReportTest {
    private fun raw(sec: Long, label: String?, face: Double = 12.0, thermal: Int = 0, battery: Int? = 80, current: Int? = -400_000, voltage: Int? = 4000, interactive: Boolean = false, idle: Boolean = false) =
        V0bRawRecord(
            tMonoMs = Synth.mono(sec), segmentLabel = label,
            shoulderCenterX = 0.5, shoulderCenterY = 0.55, shoulderWidth = 0.4, poseSamples = 1,
            tileTextureMin = 4.0, tileTextureMedian = 10.0, sceneSamples = 1,
            faceInferMsMean = face, faceInferMsP95 = face + 5, faceInferMsMax = face + 10,
            poseInferMsMean = 30.0, poseInferMsMax = 35.0, frameLatencyMsMean = 30.0,
            imuSamples = 5, accelXMean = 0.1, accelYMean = 7.2, accelZMean = 6.6, accelVariance = 0.002,
            thermalStatus = thermal, batteryPct = battery, batteryCurrentUa = current, batteryVoltageMv = voltage,
            isInteractive = interactive, isDeviceIdle = idle,
        )

    private val records = listOf(
        Synth.record(0, face = true).copy(yawMean = 2.0, pitchMean = -8.0, faceWidthPx = 210.0, jitterJ = 0.004),
        Synth.record(1, face = true).copy(yawMean = 4.0, pitchMean = -6.0, faceWidthPx = 190.0, jitterJ = 0.006, shoulderVisibilityMin = 0.5),
        Synth.record(2, face = false).copy(framesProcessed = 0, framesRequested = 24, framesDropped = 24),
        // second 3 is missing
        Synth.record(4, face = true).copy(faceDetectRatio = 0.5, yawMean = 0.0, pitchMean = -7.0, faceWidthPx = 200.0, jitterJ = 0.02),
    )
    private val raws = listOf(
        raw(0, "정면", face = 10.0, thermal = 1, battery = 90, interactive = true),
        raw(1, "정면", face = 20.0, battery = 89, idle = true),
        raw(2, "자리비움", face = 30.0, battery = 88),
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
        assertEquals(0.25, o.dropRatio)
        assertEquals(18.0, o.processedFps)
        assertEquals((10.0 * 24 + 20.0 * 24 + 40.0 * 24) / 72, o.faceInferMsMean!!, 1e-9)
        assertEquals(40.0, o.faceInferMsP95OfSecondMeans)
        assertEquals(50.0, o.faceInferMsMax)
        assertEquals(4L, o.poseRuns)
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
        assertNull(away.faceDetectRatio, "no processed frames")
        assertEquals(0.0, away.headLandmarkRatio)
        val none = s.segments[2]
        assertEquals(0.5, none.faceDetectRatio)
    }

    @Test
    fun renderIsDeterministicAndMentionsEverySegment() {
        val text = V0bReport.build(log, notes = listOf("테스트 노트")).render()
        assertEquals(text, V0bReport.build(log, notes = listOf("테스트 노트")).render())
        assertTrue(text.startsWith("focus-engine V0-A/B 요약  세션 S-synth"), text)
        assertTrue("[정면] 2s" in text && "[자리비움] 1s" in text && "[${V0bReport.NO_LABEL}] 1s" in text, text)
        assertTrue("누락된 초: 3 (레코드 없는 초 2 + 프레임 0인 초 1)" in text, text)
        assertTrue(text.endsWith("※ 테스트 노트"), text)
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
}
