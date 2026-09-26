package co.byite.focus.core.aggregate

import co.byite.focus.core.Synth
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ScreenState
import co.byite.focus.core.model.SessionEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The normal stop order (정정 5 2번): inputs stop → fence → analysis idle → pose slot closed → pose idle → barrier drain →
 * finish → check → write. Modelled with an in-memory aggregation queue and a real [FeatureAggregator].
 */
class StopSequenceTest {
    private val t0 = Synth.T0
    private val device = DeviceSample(thermalStatus = 0, isInteractive = false, isDeviceIdle = false, screenState = ScreenState.OFF, appState = AppState.BACKGROUND)
    private fun ns(ms: Long) = ms * 1_000_000L

    /** Everything the pipelines post; only [drain] runs it, standing in for the aggregation thread. */
    private class Queue {
        val pending = ArrayList<() -> Unit>()
        fun post(r: () -> Unit) { pending.add(r) }
        fun drain(): Boolean { val copy = ArrayList(pending); pending.clear(); copy.forEach { it() }; return true }
    }

    private class Harness(val agg: FeatureAggregator, val queue: Queue) {
        val order = ArrayList<String>()
        var written: StopResult? = null
        var finishedRecords: List<AggregatedSecond> = emptyList()
    }

    private fun harness(): Harness {
        val agg = FeatureAggregator(t0, Synth.UTC0)
        val q = Queue()
        q.post { agg.onScene(SceneSample(ns(t0 + 5), 118.0, 4.0, 9.0)) }
        for (i in 0 until 24) {
            q.post { agg.onFrameRequested(ns(t0 + i * 41L)) }
            q.post { agg.onFrameReceived(ns(t0 + i * 41L)) }
            q.post { agg.onFrameProcessed(ProcessedFrame(ns(t0 + i * 41L), 15.0, FrameSample(ns(t0 + i * 41L), 1_000_000L, false))) }
        }
        q.drain()
        return Harness(agg, q)
    }

    private fun sequence(h: Harness, analysisInFlight: (() -> Unit)?, poseInFlight: (() -> Unit)?, poseFinishesInTime: Boolean, pendingPoseSlot: Boolean) = StopSequence(
        stopInputs = { h.order.add("stop"); t0 + 1500 },
        raiseFence = { fence -> h.order.add("fence"); h.queue.post { h.agg.stopInputs(fence) } },
        awaitAnalysisIdle = { _ -> h.order.add("analysis"); analysisInFlight?.invoke(); 0L },
        closeSlotScheduler = { h.order.add("close_slots"); 0L },
        closePoseSlot = { h.order.add("slot") },
        awaitPoseIdle = { _ ->
            h.order.add("pose")
            var cancelled = 0L
            if (pendingPoseSlot) cancelled++ // the waiting request is discarded, never run
            if (poseInFlight != null) {
                if (poseFinishesInTime) poseInFlight() else cancelled++
            }
            cancelled
        },
        drainAggregationQueue = { _ -> h.order.add("drain"); h.queue.drain() },
        finish = { fence ->
            h.order.add("finish")
            val out = StopFinalizer.finish(h.agg, fence, t0, Synth.UTC0, device, SessionEndReason.USER)
            h.finishedRecords = out.records
            out
        },
        writeEnd = { r -> h.order.add("write"); h.written = r },
    )

    @Test
    fun stepsRunInTheContractOrderAndTheLastFrameIsCountedBeforeFinish() {
        val h = harness()
        // A Face run still going at stop time: it posts its result while the sequence waits for the analysis thread.
        val lastFrame = {
            h.queue.post { h.agg.onFrameRequested(ns(t0 + 1000)) }
            h.queue.post { h.agg.onFrameReceived(ns(t0 + 1000)) }
            h.queue.post { h.agg.onFrameProcessed(ProcessedFrame(ns(t0 + 1000), 15.0, FrameSample(ns(t0 + 1000), 1_000_000L, false))) }
        }
        // A Pose run still going at stop time that finishes within the bound.
        h.queue.post { h.agg.onPoseRequested(ns(t0 + 900), 1.0) }
        val poseResult = { h.queue.post { h.agg.onPose(PoseSample(ns(t0 + 900), 40.0, detected = false, frameWidthPx = 720, frameHeightPx = 1280)) } }
        val r = sequence(h, analysisInFlight = lastFrame, poseInFlight = poseResult, poseFinishesInTime = true, pendingPoseSlot = false).run()
        assertEquals(listOf("stop", "fence", "analysis", "close_slots", "slot", "pose", "drain", "finish", "write"), h.order)
        assertEquals(StopSequence.ORDER, r.steps)
        assertTrue(r.queueDrained)
        assertEquals(0L, r.framesCancelledAtStop)
        assertEquals(0L, r.poseCancelledAtStop)
        assertEquals(emptyList(), r.mismatches, r.mismatches.toString())
        assertEquals(25L, r.totals.framesProcessed, "the frame that finished during step 1 is counted")
        assertEquals(1L, r.totals.poseApplied, "the pose that finished within the bound is applied")
        assertEquals(1, r.records.size, "one complete bucket before the fence at +1500")
        assertEquals(24, r.records[0].second.framesProcessed)
        assertEquals(1, r.records[0].raw.poseApplied)
        assertEquals(t0 + 1500, r.end.tMonoMs, "session_end is the fence")
        assertEquals(Synth.UTC0 + 1500, r.end.tUtcMs)
        assertEquals(false, r.end.stopIntegrityFailed, "a clean stop: no capture result after CLOSE, both drains complete")
        assertEquals(0L, r.end.captureResultsAfterClose)
        assertEquals(0L, r.end.captureResultsOutOfOrder)
        assertEquals(true, r.end.captureResultDrainComplete)
        assertEquals(true, r.end.aggregationQueueDrained)
        assertFalse(r.stopIntegrityFailed)
        assertEquals(emptyList(), r.stopIntegrity.reasons)
        assertEquals(r, h.written)
    }

    /** E2 2.2: an incomplete capture-result drain or aggregation-queue drain is a stop-integrity failure with its own recorded cause. */
    private fun drainFailure(captureResultsDrained: Boolean, queueDrained: Boolean): StopResult {
        val h = harness()
        return StopSequence(
            stopInputs = { t0 + 1500 },
            raiseFence = { fence -> h.queue.post { h.agg.stopInputs(fence) } },
            drainCaptureResults = { captureResultsDrained },
            awaitAnalysisIdle = { 0L },
            closePoseSlot = { },
            awaitPoseIdle = { 0L },
            drainAggregationQueue = { _ -> h.queue.drain(); queueDrained },
            finish = { fence -> StopFinalizer.finish(h.agg, fence, t0, Synth.UTC0, device, SessionEndReason.USER) },
            writeEnd = { },
        ).run()
    }

    @Test
    fun anIncompleteCaptureResultDrainIsAStopIntegrityFailureRecordedAsItsOwnCause() {
        val r = drainFailure(captureResultsDrained = false, queueDrained = true)
        assertFalse(r.captureResultDrainComplete)
        assertTrue(r.queueDrained)
        assertTrue(r.stopIntegrityFailed)
        assertEquals(listOf(StopIntegrity.REASON_DRAIN), r.stopIntegrity.reasons)
        assertEquals(false, r.end.captureResultDrainComplete)
        assertEquals(true, r.end.aggregationQueueDrained)
        assertEquals(0L, r.end.captureResultsAfterClose, "after_close stays 0: the cause is the drain, and the end line says so")
        assertEquals(true, r.end.stopIntegrityFailed)
        assertEquals(emptyList(), r.mismatches, "the conservation relations are a separate check")
    }

    @Test
    fun anIncompleteAggregationQueueDrainIsAStopIntegrityFailureRecordedAsItsOwnCause() {
        val r = drainFailure(captureResultsDrained = true, queueDrained = false)
        assertTrue(r.captureResultDrainComplete)
        assertFalse(r.queueDrained)
        assertTrue(r.stopIntegrityFailed)
        assertEquals(listOf(StopIntegrity.REASON_QUEUE), r.stopIntegrity.reasons)
        assertEquals(true, r.end.captureResultDrainComplete)
        assertEquals(false, r.end.aggregationQueueDrained)
        assertEquals(true, r.end.stopIntegrityFailed)
        val both = drainFailure(captureResultsDrained = false, queueDrained = false)
        assertEquals(listOf(StopIntegrity.REASON_DRAIN, StopIntegrity.REASON_QUEUE), both.stopIntegrity.reasons, "each failed cause is listed, never one bool for all")
    }

    /**
     * Code review item 2: a Pose run that outlives the 500 ms bound is cancelled by bumping its generation. Its result
     * must not be posted whether it arrives just before the barrier drain or after finish, and the relations must
     * hold with `pose_cancelled_at_stop` = 1 in both cases.
     */
    private fun lateResultScenario(arrivesBeforeDrain: Boolean) {
        val h = harness()
        val generation = WorkGeneration()
        val gen = generation.current // captured when the worker started the run
        h.queue.post { h.agg.onPoseRequested(ns(t0 + 800), 1.0) }
        // The worker's post is gated exactly like PoseWorker: a stale generation posts nothing.
        val lateResult = {
            if (generation.isCurrent(gen)) h.queue.post { h.agg.onPose(PoseSample(ns(t0 + 800), 40.0, detected = false, frameWidthPx = 720, frameHeightPx = 1280)) }
        }
        val seq = StopSequence(
            stopInputs = { t0 + 1500 },
            raiseFence = { fence -> h.queue.post { h.agg.stopInputs(fence) } },
            awaitAnalysisIdle = { 0L },
            closePoseSlot = { },
            awaitPoseIdle = { _ -> generation.bump(); 1L }, // timed out: this run is the cancelled one
            drainAggregationQueue = { _ -> if (arrivesBeforeDrain) lateResult(); h.queue.drain() },
            finish = { fence -> StopFinalizer.finish(h.agg, fence, t0, Synth.UTC0, device, SessionEndReason.USER) },
            writeEnd = { },
        )
        val r = seq.run()
        if (!arrivesBeforeDrain) lateResult()
        assertEquals(1L, r.poseCancelledAtStop)
        assertEquals(1L, r.totals.poseRequested)
        assertEquals(0L, r.totals.poseCompleted, "a cancelled run is never counted as completed")
        assertEquals(0L, r.totals.poseApplied)
        assertEquals(emptyList(), r.mismatches, r.mismatches.toString())
        assertTrue(h.queue.pending.isEmpty(), "the gated result never reached the queue")
    }

    @Test
    fun aLatePoseResultArrivingJustBeforeTheDrainIsGatedAndTheRelationsHold() = lateResultScenario(arrivesBeforeDrain = true)

    @Test
    fun aLatePoseResultArrivingAfterFinishIsGatedAndTheRelationsHold() = lateResultScenario(arrivesBeforeDrain = false)

    @Test
    fun anUngatedResultAfterFinishIsRejectedByTheAggregatorAndCounted() {
        val h = harness()
        h.queue.post { h.agg.onPoseRequested(ns(t0 + 800), 1.0) }
        val r = sequence(h, analysisInFlight = null, poseInFlight = { }, poseFinishesInTime = false, pendingPoseSlot = false).run()
        assertEquals(1L, r.poseCancelledAtStop)
        // Defence in depth: even without the gate, a post after finish is refused and counted outside the window.
        assertFalse(h.agg.onPose(PoseSample(ns(t0 + 800), 40.0, detected = false, frameWidthPx = 720, frameHeightPx = 1280)))
        assertEquals(1L, h.agg.inputsAfterFence)
        assertEquals(0L, h.agg.totals.poseCompleted)
        assertEquals(emptyList(), CounterConsistency.check(h.agg.totals, 0, 1))
    }

    @Test
    fun aPoseThatDoesNotFinishInTimeAndAWaitingSlotAreCountedAsCancelledAndTheRelationsStillHold() {
        val h = harness()
        h.queue.post { h.agg.onPoseRequested(ns(t0 + 800), 1.0) } // in flight, will not finish
        h.queue.post { h.agg.onPoseRequested(ns(t0 + 1100), 1.0) } // waiting in the slot
        val r = sequence(h, analysisInFlight = null, poseInFlight = { }, poseFinishesInTime = false, pendingPoseSlot = true).run()
        assertEquals(2L, r.poseCancelledAtStop)
        assertEquals(2L, r.totals.poseRequested)
        assertEquals(0L, r.totals.poseCompleted)
        assertEquals(emptyList(), r.mismatches, r.mismatches.toString())
    }

    @Test
    fun samplesWaitingInTheQueueAreAppliedByTheBarrierBeforeFinishAndInputsAfterTheFenceAreNot() {
        val h = harness()
        h.queue.post { h.agg.onFrameRequested(ns(t0 + 1300)) }
        h.queue.post { h.agg.onFrameReceived(ns(t0 + 1300)) }
        h.queue.post { h.agg.onFrameProcessed(ProcessedFrame(ns(t0 + 1300), 15.0, FrameSample(ns(t0 + 1300), 1_000_000L, false))) }
        // A capture result that arrives after stop for a frame captured at +1600, i.e. at or after the fence (+1500): posted behind the fence.
        val lateCaptureResult = { h.queue.post { h.agg.onFrameRequested(ns(t0 + 1600)) } }
        val r = sequence(h, analysisInFlight = lateCaptureResult, poseInFlight = null, poseFinishesInTime = true, pendingPoseSlot = false).run()
        assertEquals(25L, r.totals.framesRequested)
        assertEquals(25L, r.totals.framesProcessed)
        assertEquals(1L, r.totals.inputsAfterFence)
        assertEquals(emptyList(), r.mismatches)
        assertFalse(h.queue.pending.isNotEmpty(), "nothing is left in the queue after the barrier")
    }

    @Test
    fun aMissingCountShowsUpAsAMismatch() {
        val h = harness()
        h.queue.post { h.agg.onFrameRequested(ns(t0 + 1300)) }
        h.queue.post { h.agg.onFrameReceived(ns(t0 + 1300)) } // received but never processed, skipped or errored
        val r = sequence(h, analysisInFlight = null, poseInFlight = null, poseFinishesInTime = true, pendingPoseSlot = false).run()
        assertEquals(1, r.mismatches.size)
        assertTrue(r.mismatches[0].startsWith("frames_analyzer_received"), r.mismatches[0])
    }
}
