package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameStatsTest {
    private val frameNs = 41_666_667L // 24fps
    private val latencyNs = 30_000_000L

    private fun feedSteady(stats: FrameStats, count: Int, startTs: Long = 1_000_000_000L): Long {
        var ts = startTs
        repeat(count) {
            stats.onFrame(ts, ts + latencyNs)
            ts += frameNs
        }
        return ts - frameNs // 마지막 프레임 ts
    }

    @Test
    fun steady24fpsHasNoGaps() {
        val stats = FrameStats()
        feedSteady(stats, 241) // 10초
        val s = stats.summary()
        assertEquals(241, s.totalFrames)
        assertEquals(240, s.gapCount)
        assertEquals(0, s.gapsOverThreshold)
        assertEquals(0, s.gapsOverLong)
        assertEquals(0, s.missingSeconds)
        assertEquals(41.666667, s.maxGapMs, 0.001)
        assertEquals(24.0, s.avgFps, 0.01)
        assertEquals(0.0, s.gapsOverThresholdRatio)
        assertEquals(0, s.nonMonotonic)
    }

    @Test
    fun singleGapOver80msIsCounted() {
        val stats = FrameStats()
        val last = feedSteady(stats, 10)
        stats.onFrame(last + 120_000_000L, last + 120_000_000L + latencyNs)
        feedSteady(stats, 5, last + 120_000_000L + frameNs)
        val s = stats.summary()
        assertEquals(16, s.totalFrames)
        assertEquals(1, s.gapsOverThreshold)
        assertEquals(0, s.gapsOverLong)
        assertEquals(120.0, s.maxGapMs, 0.001)
        assertEquals(1.0 / 15.0, s.gapsOverThresholdRatio, 1e-9)
    }

    @Test
    fun stallProducesLongGapAndMissingSeconds() {
        val stats = FrameStats()
        val start = 5_000_000_000L
        // 0.0s ~ 0.95s 까지 프레임, 그 뒤 3.45s 에 재개 → 1초, 2초 버킷이 빈다.
        var ts = start
        while (ts < start + 1_000_000_000L) {
            stats.onFrame(ts, ts + latencyNs)
            ts += 50_000_000L
        }
        val resume = start + 3_450_000_000L
        stats.onFrame(resume, resume + latencyNs)
        stats.onFrame(resume + frameNs, resume + frameNs + latencyNs)
        val s = stats.summary()
        assertEquals(1, s.gapsOverLong)
        assertEquals(1, s.gapsOverThreshold)
        assertEquals(2, s.missingSeconds)
        assertEquals(2500.0, s.maxGapMs, 0.001)
    }

    @Test
    fun missingSecondsCountsEveryEmptyBucket() {
        val stats = FrameStats()
        val start = 0L
        stats.onFrame(start, start + latencyNs)
        stats.onFrame(start + 10 * FrameStats.NS_PER_S, start + 10 * FrameStats.NS_PER_S + latencyNs) // 버킷 10
        assertEquals(9, stats.summary().missingSeconds)
        stats.onFrame(start + 10 * FrameStats.NS_PER_S + frameNs, 0L) // 같은 버킷
        assertEquals(9, stats.summary().missingSeconds)
        stats.onFrame(start + 12 * FrameStats.NS_PER_S, 0L) // 버킷 12 → 11 이 빈다
        assertEquals(10, stats.summary().missingSeconds)
    }

    @Test
    fun takeIntervalReturnsAndResets() {
        val stats = FrameStats()
        val last = feedSteady(stats, 24)
        val a = stats.takeInterval()
        assertEquals(24, a.frames)
        assertEquals(0, a.gapsOverThreshold)
        assertEquals(41.666667, a.maxGapMs, 0.001)
        assertNotNull(a.callbackLatencyMeanMs)
        assertEquals(30.0, a.callbackLatencyMeanMs, 1e-9)

        val empty = stats.takeInterval()
        assertEquals(0, empty.frames)
        assertEquals(0.0, empty.maxGapMs)
        assertNull(empty.callbackLatencyMeanMs)

        // 구간 경계를 넘는 갭도 다음 구간에 잡힌다.
        stats.onFrame(last + 200_000_000L, last + 200_000_000L + latencyNs)
        val b = stats.takeInterval()
        assertEquals(1, b.frames)
        assertEquals(1, b.gapsOverThreshold)
        assertEquals(200.0, b.maxGapMs, 0.001)
    }

    @Test
    fun callbackLatencyMeanUsesCallbackMinusCapture() {
        val stats = FrameStats()
        stats.onFrame(1_000_000_000L, 1_020_000_000L)
        stats.onFrame(1_041_666_667L, 1_081_666_667L)
        val iv = stats.takeInterval()
        assertEquals(30.0, iv.callbackLatencyMeanMs!!, 1e-9)
    }

    @Test
    fun nonMonotonicTimestampIsNotAGap() {
        val stats = FrameStats()
        stats.onFrame(2_000_000_000L, 0L)
        stats.onFrame(1_000_000_000L, 0L) // 역행
        stats.onFrame(2_041_666_667L, 0L)
        val s = stats.summary()
        assertEquals(3, s.totalFrames)
        assertEquals(1, s.nonMonotonic)
        assertEquals(1, s.gapCount)
        assertEquals(0, s.gapsOverThreshold)
        assertEquals(41.666667, s.maxGapMs, 0.001)
    }

    @Test
    fun onFrameReturnsGapNs() {
        val stats = FrameStats()
        assertEquals(-1L, stats.onFrame(1_000_000_000L, 0L))
        assertEquals(frameNs, stats.onFrame(1_000_000_000L + frameNs, 0L))
        assertEquals(-1L, stats.onFrame(1_000_000_000L, 0L)) // 역행
        assertEquals(2_000_000_000L, stats.onFrame(3_000_000_000L + frameNs, 0L))
    }

    @Test
    fun emptySummaryIsSafe() {
        val s = FrameStats().summary()
        assertEquals(0, s.totalFrames)
        assertEquals(0.0, s.avgFps)
        assertEquals(0.0, s.gapsOverThresholdRatio)
        assertEquals(0.0, s.maxGapMs)
        assertEquals(0, s.missingSeconds)
    }

    @Test
    fun customThresholds() {
        val stats = FrameStats(gapThresholdNs = 50_000_000L, longGapThresholdNs = 100_000_000L)
        stats.onFrame(0L, 0L)
        stats.onFrame(60_000_000L, 0L)
        stats.onFrame(200_000_000L, 0L)
        val s = stats.summary()
        assertEquals(2, s.gapsOverThreshold)
        assertEquals(1, s.gapsOverLong)
        assertTrue(s.gapsOverThresholdRatio == 1.0)
    }
}
