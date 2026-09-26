package co.byite.focus.core.aggregate

/**
 * Session-wide input counters of the [FeatureAggregator] (directive D 정정 4·5, directive E 2장). Every accepted input
 * inside the session window (`sessionStartRawTs ≤ rawSensorTs < stopFenceRawTs`, raw camera timestamps; directive E 4장)
 * is counted here once, independently of the per-second buckets, so the conservation relations can be checked at a
 * normal stop even though the partial last bucket is never written.
 *
 * Relations (checked by [CounterConsistency]):
 * - `requested = backpressureDrops + analyzerReceived` (backpressure is the difference; must not be negative)
 * - `analyzerReceived = skippedIntentional + faceInferenceErrors + preFaceErrors + processed + framesCancelledAtStop`
 * - `processed = sampleEnqueued + postFaceFailed`, `sampleEnqueued = sampleApplied + sampleLateDropped`
 * - `poseRequested = poseSuperseded + poseCompleted + poseErrors + poseCancelledAtStop`
 * - `poseCompleted = poseApplied + poseLateDropped`
 * - `processingSlotsExpected = processingSlotsFilled + processingSlotsMissed` — three independent terminal counters
 *   (schema 0.2.4); a bug that loses a slot breaks this relation because `missed` is never derived from the other two.
 *
 * Diagnostics outside every relation: capture results stamped before the session start, at or after the raw stop
 * fence, and after the scheduler's CLOSE. The last one must be 0 at a normal stop: such a capture result is a real
 * pre-fence frame that is absent from `expected`, so the relations can pass while the stop was not clean
 * (`stop_integrity_failed`, directive E 2·5장).
 */
data class CounterTotals(
    val framesRequested: Long = 0,
    val framesAnalyzerReceived: Long = 0,
    val framesSkippedIntentional: Long = 0,
    val faceInferenceErrors: Long = 0,
    val preFaceErrors: Long = 0,
    val framesProcessed: Long = 0,
    val framesSampleApplied: Long = 0,
    val framesSampleLateDropped: Long = 0,
    val poseRequested: Long = 0,
    val poseSuperseded: Long = 0,
    val poseCompleted: Long = 0,
    val poseApplied: Long = 0,
    val poseLateDropped: Long = 0,
    val poseErrors: Long = 0,
    /** Scene / IMU samples that arrived for a closed bucket (dropped; not part of a conservation relation). */
    val otherLateInputs: Long = 0,
    /** Inputs of any kind stamped before the session start (dropped). */
    val inputsBeforeStart: Long = 0,
    /** Inputs of any kind stamped at or after the stop fence (dropped). */
    val inputsAfterFence: Long = 0,
    // ---- processing slots (schema 0.2.4, directive E 2장): independent terminal counters
    val processingSlotsExpected: Long = 0,
    val processingSlotsFilled: Long = 0,
    val processingSlotsMissed: Long = 0,
    // ---- diagnostics (schema 0.2.4): capture results outside the raw window / after the scheduler CLOSE
    val captureResultsBeforeStart: Long = 0,
    val captureResultsAfterFence: Long = 0,
    val captureResultsAfterClose: Long = 0,
) {
    val backpressureDrops: Long get() = framesRequested - framesAnalyzerReceived
    val framesSampleEnqueued: Long get() = framesSampleApplied + framesSampleLateDropped
    val framesPostFaceFailed: Long get() = framesProcessed - framesSampleEnqueued
    val framesUnprocessedUnexpected: Long get() = framesAnalyzerReceived - framesSkippedIntentional - framesProcessed

    /** `missed ÷ expected` (the counted `missed`, never `expected − filled`); null without expected slots. */
    val slotMissRatio: Double? get() = if (processingSlotsExpected > 0) processingSlotsMissed.toDouble() / processingSlotsExpected else null

    /** A capture result reached the scheduler after CLOSE: the stop was not clean (directive E 2장). */
    val stopIntegrityFailed: Boolean get() = captureResultsAfterClose > 0
}

/**
 * Conservation-relation check run at a normal stop (정정 4·5 4번, directive E 2장). Returns one line per broken relation,
 * `"<항목>: 좌변 ≠ 우변 (…)"`, empty when everything adds up. A recovered (process-death) summary skips the
 * check and says so instead.
 */
object CounterConsistency {
    /** Pending slot + one in-flight run: more than this many unfinished pose requests at stop cannot be explained by the depth-1 queue. */
    const val MAX_POSE_UNFINISHED_AT_STOP: Int = 2

    fun check(t: CounterTotals, framesCancelledAtStop: Long = 0, poseCancelledAtStop: Long = 0): List<String> {
        val out = ArrayList<String>()
        if (t.framesAnalyzerReceived > t.framesRequested) {
            out.add("frames_requested ${t.framesRequested} < frames_analyzer_received ${t.framesAnalyzerReceived} (백프레셔 드롭이 음수)")
        }
        val recvRhs = t.framesSkippedIntentional + t.faceInferenceErrors + t.preFaceErrors + t.framesProcessed + framesCancelledAtStop
        if (t.framesAnalyzerReceived != recvRhs) {
            out.add(
                "frames_analyzer_received ${t.framesAnalyzerReceived} ≠ skipped ${t.framesSkippedIntentional} + face_inference_errors ${t.faceInferenceErrors} + " +
                    "pre_face_errors ${t.preFaceErrors} + processed ${t.framesProcessed} + cancelled_at_stop $framesCancelledAtStop = $recvRhs",
            )
        }
        if (t.framesSampleEnqueued > t.framesProcessed) {
            out.add("frames_processed ${t.framesProcessed} < frames_sample_enqueued ${t.framesSampleEnqueued} (post_face_failed 가 음수)")
        }
        // sample_enqueued = applied + late_dropped holds by construction; listed so the table in the PR body is complete.
        if (t.framesSampleEnqueued != t.framesSampleApplied + t.framesSampleLateDropped) {
            out.add("frames_sample_enqueued ${t.framesSampleEnqueued} ≠ applied ${t.framesSampleApplied} + late_dropped ${t.framesSampleLateDropped}")
        }
        val poseRhs = t.poseSuperseded + t.poseCompleted + t.poseErrors + poseCancelledAtStop
        if (t.poseRequested != poseRhs) {
            out.add(
                "pose_requested ${t.poseRequested} ≠ superseded ${t.poseSuperseded} + completed ${t.poseCompleted} + errors ${t.poseErrors} + " +
                    "cancelled_at_stop $poseCancelledAtStop = $poseRhs",
            )
        }
        if (t.poseCompleted != t.poseApplied + t.poseLateDropped) {
            out.add("pose_completed ${t.poseCompleted} ≠ applied ${t.poseApplied} + late_dropped ${t.poseLateDropped}")
        }
        // Three independent counters (directive E 최종 정정 1): the relation is only informative because none is derived.
        val slotRhs = t.processingSlotsFilled + t.processingSlotsMissed
        if (t.processingSlotsExpected != slotRhs) {
            out.add("processing_slots_expected ${t.processingSlotsExpected} ≠ filled ${t.processingSlotsFilled} + missed ${t.processingSlotsMissed} = $slotRhs")
        }
        return out
    }
}
