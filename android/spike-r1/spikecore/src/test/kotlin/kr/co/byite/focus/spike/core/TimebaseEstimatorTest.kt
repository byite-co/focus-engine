package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimebaseEstimatorTest {
    private val minute = 60_000_000_000L
    private val frameNs = 41_666_667L

    /** diffs[i] = 콜백 − 프레임 ts. 프레임 ts 는 startTs 부터 24fps 로 증가. 마지막 콜백 시각을 돌려준다. */
    private fun feed(est: TimebaseEstimator, diffs: List<Long>, startTs: Long, sink: MutableList<TimebaseSample>): Long {
        var ts = startTs
        var cb = 0L
        for (d in diffs) {
            cb = ts + d
            est.onFrame(ts, cb)?.let { sink.add(it) }
            ts += frameNs
        }
        return cb
    }

    @Test
    fun initialOffsetIsMinOfFirstWindow() {
        val est = TimebaseEstimator(windowFrames = 5)
        val out = ArrayList<TimebaseSample>()
        assertNull(est.offsetNs)
        feed(est, listOf(50, 40, 45, 60, 42), 1_000_000_000L, out)
        assertEquals(40L, est.offsetNs)
        assertEquals(1, out.size)
        assertEquals(0L, out[0].driftNs)
        assertEquals(40L, out[0].offsetNs)
        assertEquals(5, out[0].frames)
        assertTrue(out[0].complete)
        assertTrue(est.driftSamples.isEmpty())
    }

    @Test
    fun noSampleBeforeWindowFills() {
        val est = TimebaseEstimator(windowFrames = 100)
        val out = ArrayList<TimebaseSample>()
        feed(est, List(99) { 30L }, 0L, out)
        assertTrue(out.isEmpty())
        assertNull(est.offsetNs)
        feed(est, listOf(30L), 99 * frameNs, out)
        assertEquals(1, out.size)
        assertEquals(30L, est.offsetNs)
    }

    @Test
    fun remeasuresAfterIntervalAndRecordsDrift() {
        val est = TimebaseEstimator(windowFrames = 5, remeasureIntervalNs = minute)
        val out = ArrayList<TimebaseSample>()
        var ts = 1_000_000_000L
        val lastCb = feed(est, listOf(50, 40, 45, 60, 42), ts, out)
        ts += 5 * frameNs
        // 1분이 되기 전의 프레임은 무시된다.
        val before = ArrayList<TimebaseSample>()
        while (ts + 10 < lastCb + minute - 5 * frameNs) {
            est.onFrame(ts, ts + 10)?.let { before.add(it) }
            ts += frameNs
        }
        assertTrue(before.isEmpty(), "재측정 창이 열리기 전에는 표본이 없다")
        assertEquals(40L, est.offsetNs)
        // 1분 경과 뒤 다음 5 프레임 → offset 41, drift +1
        ts = lastCb + minute
        feed(est, listOf(43, 41, 47, 44, 49), ts, out)
        assertEquals(2, out.size)
        assertEquals(41L, out[1].offsetNs)
        assertEquals(1L, out[1].driftNs)
        assertEquals(1, est.driftSamples.size)
        assertEquals(40L, est.offsetNs, "초기 offset 은 바뀌지 않는다")

        val s = est.summary()
        assertEquals(40L, s.offsetNs)
        assertEquals(1L, s.lastDriftNs)
        assertEquals(1L, s.maxAbsDriftNs)
        assertEquals(1, s.remeasureCount)
    }

    @Test
    fun remeasureCadenceIsAnchoredToFirstClose() {
        val est = TimebaseEstimator(windowFrames = 5, remeasureIntervalNs = minute)
        val out = ArrayList<TimebaseSample>()
        val t0 = feed(est, listOf(50, 40, 45, 60, 42), 0L, out) // 첫 창 닫힘 시각 T0
        // 두 번째 창: T0 + 60s 에 열리고 5프레임 뒤 닫힌다 (닫힘 ≈ T0 + 60s + 4프레임)
        feed(est, listOf(41, 41, 41, 41, 41), t0 + minute, out)
        assertEquals(2, out.size)
        // 세 번째 창은 두 번째 창의 닫힘 시각 + 60s 가 아니라 T0 + 120s 에 열려야 한다.
        val early = t0 + 2 * minute - 10 * frameNs
        assertNull(est.onFrame(early, early + 41), "T0+120s 전에는 창이 열리지 않는다")
        feed(est, listOf(42, 42, 42, 42, 42), t0 + 2 * minute, out)
        assertEquals(3, out.size)
        assertEquals(2L, out[2].driftNs)
        // 창을 여러 개 놓치면(정지 등) 다음 경계로 건너뛴다.
        feed(est, listOf(43, 43, 43, 43, 43), t0 + 10 * minute + 1, out)
        assertEquals(4, out.size)
        val late = t0 + 10 * minute + 1 + 5 * frameNs
        assertNull(est.onFrame(late, late + 43), "건너뛴 창을 몰아서 열지 않는다")
        feed(est, listOf(44, 44, 44, 44, 44), t0 + 11 * minute, out)
        assertEquals(5, out.size)
        assertEquals(4L, out[4].driftNs)
    }

    @Test
    fun negativeDriftAndMaxAbs() {
        val est = TimebaseEstimator(windowFrames = 2, remeasureIntervalNs = 1_000L)
        val out = ArrayList<TimebaseSample>()
        // 창 1: min 100
        est.onFrame(0L, 100L); est.onFrame(10L, 130L)
        // 창 2 (콜백 ≥ 130+1000): min 95 → drift -5
        est.onFrame(2_000L, 2_095L); est.onFrame(2_010L, 2_110L)?.let { out.add(it) }
        // 창 3: min 103 → drift +3
        est.onFrame(4_000L, 4_103L); est.onFrame(4_010L, 4_120L)?.let { out.add(it) }
        assertEquals(listOf(-5L, 3L), out.map { it.driftNs })
        val s = est.summary()
        assertEquals(3L, s.lastDriftNs)
        assertEquals(5L, s.maxAbsDriftNs)
        assertEquals(2, s.remeasureCount)
        assertEquals(6L, s.frames)
    }

    @Test
    fun finishClosesPartialWindow() {
        val est = TimebaseEstimator(windowFrames = 5, remeasureIntervalNs = minute)
        val out = ArrayList<TimebaseSample>()
        val lastCb = feed(est, listOf(50, 40, 45, 60, 42), 0L, out)
        val ts = lastCb + minute
        est.onFrame(ts, ts + 39L)
        est.onFrame(ts + frameNs, ts + frameNs + 44L)
        val partial = est.finish(ts + 2 * frameNs)
        assertNotNull(partial)
        assertFalse(partial.complete)
        assertEquals(39L, partial.offsetNs)
        assertEquals(-1L, partial.driftNs)
        assertEquals(2, partial.frames)
        assertEquals(1, est.driftSamples.size)
        // 닫힌 뒤 다시 finish 하면 null
        assertNull(est.finish(ts + 3 * frameNs))
    }

    @Test
    fun finishWithoutInitialOffsetUsesPartialWindow() {
        val est = TimebaseEstimator(windowFrames = 100)
        est.onFrame(0L, 70L)
        est.onFrame(frameNs, frameNs + 65L)
        val s = est.finish(frameNs + 100L)
        assertNotNull(s)
        assertFalse(s.complete)
        assertEquals(65L, est.offsetNs)
        assertEquals(0L, s.driftNs)
        assertTrue(est.driftSamples.isEmpty())
    }

    @Test
    fun finishWithNoFramesIsNull() {
        val est = TimebaseEstimator()
        assertNull(est.finish(0L))
        val s = est.summary()
        assertNull(s.offsetNs)
        assertNull(s.lastDriftNs)
        assertNull(s.maxAbsDriftNs)
        assertEquals(0, s.remeasureCount)
    }

    @Test
    fun worksWhenCameraClockIsNotRealtime() {
        // 카메라 ts 가 완전히 다른 기준(예: 0 부터)이어도 offset 은 큰 상수로 잡힌다.
        val est = TimebaseEstimator(windowFrames = 3)
        val base = 123_456_789_000_000L
        est.onFrame(0L, base + 30L)
        est.onFrame(frameNs, base + frameNs + 25L)
        val s = est.onFrame(2 * frameNs, base + 2 * frameNs + 40L)
        assertNotNull(s)
        assertEquals(base + 25L, s.offsetNs)
    }
}
