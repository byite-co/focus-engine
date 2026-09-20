package co.byite.focus.core.aggregate

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
    /** Broken conservation relations ([CounterConsistency.check]); empty when everything adds up. */
    val mismatches: List<String>,
    val steps: List<String>,
)

/**
 * Normal stop order (directive D 정정 5 2번 + 명확화), as a contract the device layer fills with its threads:
 *
 * 1. camera and IMU stop accepting input; the accept fence (capture timestamp) is raised on the aggregation queue;
 *    the Face / Scene work already running on the analysis thread finishes (bounded) or is counted as cancelled;
 * 2. the Pose waiting slot accepts nothing more;
 * 3. the in-flight Pose run finishes within [POSE_STOP_TIMEOUT_MS] or is counted as `pose_cancelled_at_stop`;
 * 4. a barrier is posted to the aggregation queue and everything the pipelines posted before it is drained;
 * 5. `FeatureAggregator.finish()`;
 * 6. the counter conservation relations are checked;
 * 7. `session_end` and the summary are written.
 *
 * The lambdas run on the caller's thread; each is responsible for hopping to its own thread and for its own
 * time bound. Pure Kotlin so the order itself is unit-tested in focus-core.
 */
class StopSequence(
    /** Step 1: stop camera + IMU input; returns the fence (ms, monotonic) chosen at that moment. */
    private val stopInputs: () -> Long,
    /** Step 1: post `FeatureAggregator.stopInputs(fence)` to the aggregation queue. */
    private val raiseFence: (fenceMonoMs: Long) -> Unit,
    /** Step 1: wait for the analysis thread to finish its current frame; returns frames counted as cancelled (0 when it finished). */
    private val awaitAnalysisIdle: (timeoutMs: Long) -> Long,
    /** Step 2. */
    private val closePoseSlot: () -> Unit,
    /** Step 3: wait for the in-flight pose; returns pose requests counted as cancelled (waiting slot + unfinished run). */
    private val awaitPoseIdle: (timeoutMs: Long) -> Long,
    /** Step 4: barrier on the aggregation queue; true when everything posted before it ran. */
    private val drainAggregationQueue: (timeoutMs: Long) -> Boolean,
    /** Step 5: on the aggregation queue's thread — `finish()`; returns the closed records and the session totals. */
    private val finish: (fenceMonoMs: Long) -> Pair<List<AggregatedSecond>, CounterTotals>,
    /** Step 7: `session_end` + summary. */
    private val writeEnd: (StopResult) -> Unit,
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
        closePoseSlot()
        steps.add(STEP_CLOSE_POSE_SLOT)
        val poseCancelled = awaitPoseIdle(poseTimeoutMs)
        steps.add(STEP_AWAIT_POSE)
        val drained = drainAggregationQueue(drainTimeoutMs)
        steps.add(STEP_DRAIN)
        val (records, totals) = finish(fence)
        steps.add(STEP_FINISH)
        val mismatches = CounterConsistency.check(totals, framesCancelled, poseCancelled)
        steps.add(STEP_CHECK)
        val result = StopResult(fence, framesCancelled, poseCancelled, drained, records, totals, mismatches, steps)
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
        const val STEP_CLOSE_POSE_SLOT = "close_pose_slot"
        const val STEP_AWAIT_POSE = "await_pose_idle"
        const val STEP_DRAIN = "drain_aggregation_queue"
        const val STEP_FINISH = "finish"
        const val STEP_CHECK = "check_counters"
        const val STEP_WRITE_END = "write_end"

        val ORDER: List<String> = listOf(
            STEP_STOP_INPUTS, STEP_RAISE_FENCE, STEP_AWAIT_ANALYSIS, STEP_CLOSE_POSE_SLOT, STEP_AWAIT_POSE,
            STEP_DRAIN, STEP_FINISH, STEP_CHECK, STEP_WRITE_END,
        )
    }
}
