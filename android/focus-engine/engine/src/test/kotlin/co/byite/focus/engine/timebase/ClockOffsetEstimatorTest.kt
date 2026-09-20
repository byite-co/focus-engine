package co.byite.focus.engine.timebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClockOffsetEstimatorTest {
    @Test
    fun offsetIsTheMinimumLatencyOfTheFirstWindow() {
        val e = ClockOffsetEstimator(windowSamples = 3, remeasureIntervalNs = 1_000L)
        assertEquals(0L, e.currentOffsetNs)
        assertNull(e.onSample(100, 130))
        assertEquals(30L, e.provisionalNs)
        assertNull(e.onSample(200, 215))
        assertEquals(15L, e.currentOffsetNs)
        val s = e.onSample(300, 340)!!
        assertEquals(15L, s.offsetNs)
        assertEquals(0L, s.driftNs)
        assertEquals(3, s.samples)
        assertEquals(15L, e.offsetNs)
        assertNull(e.provisionalNs)
    }

    @Test
    fun remeasuresAfterTheIntervalAndReportsDrift() {
        val e = ClockOffsetEstimator(windowSamples = 2, remeasureIntervalNs = 1_000L)
        e.onSample(0, 10)
        assertNotNull(e.onSample(100, 110))
        assertNull(e.onSample(500, 520), "window closed until the next interval")
        assertNull(e.onSample(1_200, 1_225))
        val s = e.onSample(1_300, 1_330)!!
        assertEquals(25L, s.offsetNs)
        assertEquals(15L, s.driftNs)
        assertEquals(25L, e.currentOffsetNs)
    }

    @Test
    fun finishClosesAPartialWindow() {
        val e = ClockOffsetEstimator(windowSamples = 5, remeasureIntervalNs = 1_000L)
        e.onSample(0, 40)
        val s = e.finish(50)!!
        assertEquals(40L, s.offsetNs)
        assertEquals(false, s.complete)
        assertNull(e.finish(60))
    }
}
