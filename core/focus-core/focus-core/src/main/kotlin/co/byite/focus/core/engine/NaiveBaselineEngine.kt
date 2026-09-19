package co.byite.focus.core.engine

import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.State

/**
 * Comparison baseline from v0-plan 7장: "얼굴 미검출 3초 → ABSENT", everything else PRESENT.
 * No INVALID, no PRONE, no phone gate. Used to quantify what the staged G1/scene judgement buys
 * (false ABSENT, false INVALID, missed ABSENT seconds).
 *
 * A bucket is "face missing" when [FaceBand.of] is MISSING (`face_detect_ratio < face_missing_max_ratio`,
 * v0.2.1 판정 12). Three consecutive missing buckets confirm ABSENT at the third bucket with the
 * candidate start at the first (판정 2); the unconfirmed buckets carry `candidate_state = ABSENT`.
 */
class NaiveBaselineEngine(private val params: ParameterSet) : GateEngine {
    override val engineId: String = ENGINE_ID

    private var candidateStartMonoMs: Long? = null
    private var missingBuckets: Int = 0

    override fun reset() {
        candidateStartMonoMs = null
        missingBuckets = 0
    }

    override fun judge(record: SecondRecord): GateDecision {
        if (FaceBand.of(record.faceDetectRatio, params) != FaceBand.MISSING) {
            reset()
            return GateDecision(State.PRESENT)
        }
        val start = candidateStartMonoMs ?: record.tMonoMs.also { candidateStartMonoMs = it }
        missingBuckets++
        return if (missingBuckets >= params.absentConfirmBuckets) {
            GateDecision(State.ABSENT, candidateState = State.ABSENT, candidateStartMonoMs = start)
        } else {
            GateDecision(State.PRESENT, candidateState = State.ABSENT, candidateStartMonoMs = start)
        }
    }

    companion object {
        const val ENGINE_ID: String = "naive-baseline"
    }
}
