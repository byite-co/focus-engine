package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpikeSummaryTest {
    private fun base() = SpikeSummary(
        sessionId = "s", device = "d", camera = "c", timestampSource = "REALTIME(1)", batteryOptimizationIgnored = "true",
        reason = "user_stop", startLocal = "t0", lengthS = 1800.0, lastRecord = null,
        totalFrames = 43_200, avgFps = 24.0, gapsOver80 = 12, gapCount = 43_199, gapsOverLong = 0, gapsOverLongIsLowerBound = false,
        maxGapMs = 125.3, zeroFrameRows = 0, rowGapSeconds = 0, frameBucketMissing = 0, rows = 1800, screenOffRows = 1790, idleRows = 0,
        batteryStart = 87, batteryEnd = 79, maxThermal = 1, offsetMs = 31.24, lastDriftMs = 0.04, maxAbsDriftMs = 0.06, remeasureCount = 29,
        resultStreamLine = "capture result 스트림: 43205개", serviceSurvived = true,
    )

    @Test
    fun passesWhenAllCriteriaHold() {
        val s = base()
        assertTrue(s.pass)
        val text = s.render()
        assertTrue(text.startsWith("spike-r1 요약  세션 s"))
        assertTrue(text.contains("80ms 초과 갭: 12 / 43199 (0.028%)"))
        assertTrue(text.contains("누락된 초: 0 (프레임 0인 행 0 + 행 없는 초 0, 프레임 버킷 기준 0)"))
        assertTrue(text.contains("capture result 스트림: 43205개"))
        assertTrue(text.endsWith("[서비스 생존: OK (정상 정지)]"))
        assertFalse(text.contains("마지막 기록"))
    }

    @Test
    fun failsOnRowGapsEvenWithoutFrameGaps() {
        val s = base().copy(rowGapSeconds = 5)
        assertEquals(5, s.missingSeconds)
        assertFalse(s.passMissing)
        assertFalse(s.pass)
        assertTrue(s.render().contains("[누락된 초 0: FAIL]"))
    }

    @Test
    fun failsWhenServiceDidNotSurvive() {
        val s = base().copy(serviceSurvived = false, reason = "killed")
        assertTrue(s.passGapRatio && s.passLongGap && s.passMissing)
        assertFalse(s.pass)
        assertTrue(s.render().contains("[서비스 생존: FAIL (killed)]"))
    }

    @Test
    fun gapRatioThreshold() {
        assertTrue(base().copy(gapsOver80 = 431, gapCount = 43_199).passGapRatio) // 0.998%
        assertFalse(base().copy(gapsOver80 = 432, gapCount = 43_199).passGapRatio) // 1.0002%
        assertEquals(0.0, base().copy(totalFrames = 1, gapCount = null, gapsOver80 = 0).gapsOver80Ratio)
    }
}
