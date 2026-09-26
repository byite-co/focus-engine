package co.byite.focus.core.aggregate

import co.byite.focus.core.model.SessionEnd

/**
 * Outcome of the normal stop order ([StopSequence]). [steps] lists the steps in the order they ran, for the
 * event log and the order test.
 */
data class StopResult(
    /** Accept fence (ms, monotonic): inputs with `captureTs >= fence` are not counted. */
    val fenceMonoMs: Long,
    /** Face / Scene work still running on the analysis thread that did not finish within the bound. */
    val framesCancelledAtStop: Long,
    /** Pose requests (waiting slot + in-flight run) that did not finish within the bound. */
    val poseCancelledAtStop: Long,
    /** False when the aggregation barrier did not come back within its bound (records may then be incomplete). */
    val queueDrained: Boolean,
    val records: List<AggregatedSecond>,
    val totals: CounterTotals,
    /** `session_end`, stamped at the fence by [StopFinalizer]. */
    val end: SessionEnd,
    /** Broken conservation relations ([CounterConsistency.check]); empty when everything adds up. */
    val mismatches: List<String>,
    val steps: List<String>,
    /** Processing slots still open at the scheduler CLOSE, terminated as missed (directive E 5장). */
    val slotsClosedAsMissed: Long = 0L,
) {
    /** A capture result reached the scheduler after CLOSE: the stop was not clean, the session is not comparable (directive E 2장). */
    val stopIntegrityFailed: Boolean get() = totals.stopIntegrityFailed
}

/**
 * Normal stop order (directive D 정정 5 2번 + 명확화, directive E 5장 slot gate), as a contract the device layer fills with
 * its threads:
 *
 * 1. the camera offset is frozen and the fence chosen (`stopFenceMonoNs`, `stopFenceRawTs = fence − offsetSnapshot`),
 *    camera and IMU stop accepting input; the fence is raised on the aggregation queue (and on the scheduler);
 *    the Face / Scene work already running on the analysis thread finishes (bounded) or is counted as cancelled —
 *    on a timeout the [WorkGeneration] of that work is bumped so a late outcome is never posted nor counted;
 * 2. the slot scheduler is CLOSEd on the analysis thread: every open slot becomes `missed`, and a capture result
 *    arriving afterwards is `capture_results_after_close` (0 at a clean stop, else `stop_integrity_failed`);
 * 3. the Pose waiting slot accepts nothing more;
 * 4. the in-flight Pose run finishes within [POSE_STOP_TIMEOUT_MS] or is counted as `pose_cancelled_at_stop`
 *    (its generation is bumped the same way; the worker closes the landmarker itself once the run returns);
 * 5. a barrier is posted to the aggregation queue and everything the pipelines posted before it is drained;
 * 6. [StopFinalizer.finish] at the fence (never at the wall clock);
 * 7. the counter conservation relations are checked (slot relation included);
 * 8. `session_end` (= the fence) and the summary are written.
 *
 * The lambdas run on the caller's thread; each is responsible for hopping to its own thread and for its own
 * time bound. Pure Kotlin so the order itself is unit-tested in focus-core.
 */
class StopSequence(
    /** Step 1: stop camera + IMU input; returns the fence (ms, monotonic) chosen at that moment. */
    private val stopInputs: () -> Long,
    /** Step 1: post `FeatureAggregator.stopInputs(fence, fenceRaw)` to the aggregation queue (and the raw fence to the scheduler). */
    private val raiseFence: (fenceMonoMs: Long) -> Unit,
    /** Step 1: wait for the analysis thread to finish its current frame; returns frames counted as cancelled (0 when it finished). */
    private val awaitAnalysisIdle: (timeoutMs: Long) -> Long,
    /** Step 3. */
    private val closePoseSlot: () -> Unit,
    /** Step 4: wait for the in-flight pose; returns pose requests counted as cancelled (waiting slot + unfinished run). */
    private val awaitPoseIdle: (timeoutMs: Long) -> Long,
    /** Step 5: barrier on the aggregation queue; true when everything posted before it ran. */
    private val drainAggregationQueue: (timeoutMs: Long) -> Boolean,
    /** Step 6: on the aggregation queue's thread — [StopFinalizer.finish] at the fence; returns every record, the totals and the end marker. */
    private val finish: (fenceMonoMs: Long) -> FinishOutcome,
    /** Step 8: `session_end` + summary. */
    private val writeEnd: (StopResult) -> Unit,
    /** Step 2: CLOSE the slot scheduler on the analysis thread; returns the open slots terminated as missed. */
    private val closeSlotScheduler: () -> Long = { 0L },
) {
    fun run(
        analysisTimeoutMs: Long = ANALYSIS_STOP_TIMEOUT_MS,
        poseTimeoutMs: Long = POSE_STOP_TIMEOUT_MS,
        drainTimeoutMs: Long = DRAIN_TIMEOUT_MS,
    ): StopResult {
        val steps = ArrayList<String>()
        val fence = stopInputs()
        steps.add(STEP_STOP_INPUTS)
        raiseFence(fence)
        steps.add(STEP_RAISE_FENCE)
        val framesCancelled = awaitAnalysisIdle(analysisTimeoutMs)
        steps.add(STEP_AWAIT_ANALYSIS)
        val slotsClosed = closeSlotScheduler()
        steps.add(STEP_CLOSE_SLOTS)
        closePoseSlot()
        steps.add(STEP_CLOSE_POSE_SLOT)
        val poseCancelled = awaitPoseIdle(poseTimeoutMs)
        steps.add(STEP_AWAIT_POSE)
        val drained = drainAggregationQueue(drainTimeoutMs)
        steps.add(STEP_DRAIN)
        val outcome = finish(fence)
        steps.add(STEP_FINISH)
        val mismatches = CounterConsistency.check(outcome.totals, framesCancelled, poseCancelled)
        steps.add(STEP_CHECK)
        val result = StopResult(fence, framesCancelled, poseCancelled, drained, outcome.records, outcome.totals, outcome.end, mismatches, steps, slotsClosed)
        writeEnd(result)
        steps.add(STEP_WRITE_END)
        return result
    }

    companion object {
        const val ANALYSIS_STOP_TIMEOUT_MS: Long = 500L
        /** 정정 5: 실행 중인 Pose 의 상한 시간 초기값. */
        const val POSE_STOP_TIMEOUT_MS: Long = 500L
        const val DRAIN_TIMEOUT_MS: Long = 3_000L

        const val STEP_STOP_INPUTS = "stop_inputs"
        const val STEP_RAISE_FENCE = "raise_fence"
        const val STEP_AWAIT_ANALYSIS = "await_analysis_idle"
        const val STEP_CLOSE_SLOTS = "close_slot_scheduler"
        const val STEP_CLOSE_POSE_SLOT = "close_pose_slot"
        const val STEP_AWAIT_POSE = "await_pose_idle"
        const val STEP_DRAIN = "drain_aggregation_queue"
        const val STEP_FINISH = "finish"
        const val STEP_CHECK = "check_counters"
        const val STEP_WRITE_END = "write_end"

        val ORDER: List<String> = listOf(
            STEP_STOP_INPUTS, STEP_RAISE_FENCE, STEP_AWAIT_ANALYSIS, STEP_CLOSE_SLOTS, STEP_CLOSE_POSE_SLOT, STEP_AWAIT_POSE,
            STEP_DRAIN, STEP_FINISH, STEP_CHECK, STEP_WRITE_END,
        )
    }
}
