package co.byite.focus.core.aggregate

import co.byite.focus.core.Synth
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ScreenState
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.report.V0bReport
import co.byite.focus.core.log.SessionLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Code review item 1: the session ends at the fence, however late `finish` runs and whatever the wall clock says. */
class StopFinalizerTest {
    private val t0 = Synth.T0
    private val device = DeviceSample(thermalStatus = 0, batteryCurrentUa = -400_000, batteryVoltageMv = 4000, isInteractive = false, isDeviceIdle = false, screenState = ScreenState.OFF, appState = AppState.BACKGROUND)
    private fun ns(ms: Long) = ms * 1_000_000L

    private fun session(): FeatureAggregator {
        val a = FeatureAggregator(t0, Synth.UTC0)
        a.onScene(SceneSample(ns(t0 + 5), 118.0, 4.0, 9.0))
        for (i in 0 until 24 * 4) { // 4 s of frames, exactly 24 per bucket
            val t = ns(t0 + i * 1000L / 24)
            a.onFrameRequested(t)
            a.onFrameReceived(t)
            a.onFrameProcessed(ProcessedFrame(t, 15.0, FrameSample(t, 1_000_000L, false)))
        }
        return a
    }

    @Test
    fun finishClosesOnlyBucketsThatEndAtOrBeforeTheFenceEvenWhenCalledMuchLater() {
        val a = session()
        val earlier = a.closeBuckets(t0 + 1300, device) // bucket 0 closed by the 1 Hz tick
        assertEquals(1, earlier.size)
        val fence = t0 + 2500
        // In the real service the finish step runs on the aggregation thread some hundreds of ms after the fence;
        // it never passes its own clock, only the fence.
        val out = StopFinalizer.finish(a, fence, t0, Synth.UTC0, device, SessionEndReason.USER, earlier)
        assertEquals(listOf(t0, t0 + 1000), out.records.map { it.second.tMonoMs }, "bucket 2 ends after the fence and is dropped")
        assertEquals(fence, out.end.tMonoMs)
        assertEquals(Synth.UTC0 + 2500, out.end.tUtcMs)
        assertEquals(SessionEndReason.USER, out.end.reason)
        assertEquals(fence, a.stopFenceMonoMs, "the fence is raised even if the raise_fence post was lost")
        assertTrue(a.finish(t0 + 60_000, device).isEmpty(), "a later finish at a later clock adds nothing")
        assertTrue(a.closeBuckets(t0 + 60_000, device).isEmpty(), "nor does a late tick")
        // inputs captured after the fence are outside the window
        a.onFrameRequested(ns(t0 + 2600))
        assertEquals(1L, a.inputsAfterFence)
        assertEquals(24L * 4, a.totals.framesProcessed, "the frames of the dropped partial bucket stay in the totals")
    }

    @Test
    fun theSummaryMeasuresTheSessionUpToTheFence() {
        val a = session()
        val out = StopFinalizer.finish(a, t0 + 3000, t0, Synth.UTC0, device, SessionEndReason.USER)
        val log = SessionLog(Synth.header, out.records.map { it.second }, sessionEnd = out.end, v0bRaw = out.records.map { it.raw })
        val s = V0bReport.build(log)
        assertEquals(3.0, s.overall.sessionLengthS)
        assertEquals(3, s.overall.records)
        assertEquals(0L, s.overall.missingRecords)
        assertEquals(24.0, s.overall.processedFps!!, 1e-9)
        assertEquals(listOf(0L to 3L), s.screenSegments.map { it.startS to it.endS })
        assertEquals(1600.0, s.overall.meanPowerMw!!, 1e-9)
    }

    @Test
    fun onceTheFenceIsRaisedTheTickNeverClosesABucketEndingAfterIt() {
        val a = session()
        a.stopInputs(t0 + 2500)
        val closed = a.closeBuckets(t0 + 9_000, device) // a tick that runs long after the fence (the drain step)
        assertEquals(listOf(t0, t0 + 1000), closed.map { it.second.tMonoMs })
        assertTrue(StopFinalizer.finish(a, t0 + 2500, t0, Synth.UTC0, device, SessionEndReason.USER).records.isEmpty())
    }

    @Test
    fun aFenceBeforeTheSessionStartIsRejected() {
        val a = session()
        assertFailsWith<IllegalArgumentException> { StopFinalizer.finish(a, t0 - 1, t0, Synth.UTC0, device, SessionEndReason.USER) }
    }

    @Test
    fun generationTokensInvalidateOnlyWorkStartedBeforeTheBump() {
        val g = WorkGeneration()
        val before = g.current
        assertTrue(g.isCurrent(before))
        assertEquals(1L, g.bump())
        assertTrue(!g.isCurrent(before))
        val after = g.current
        assertTrue(g.isCurrent(after))
        assertEquals(2L, g.bump())
        assertTrue(!g.isCurrent(after))
    }
}
