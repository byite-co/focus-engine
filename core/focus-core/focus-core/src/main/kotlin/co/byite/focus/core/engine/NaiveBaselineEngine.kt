package co.byite.focus.core.engine

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.State

/**
 * Comparison baseline from v0-plan 7장: "얼굴 미검출 3초 → ABSENT", everything else PRESENT.
 * No INVALID, no PRONE, no phone gate. Used to quantify what the staged G1/scene judgement buys
 * (false ABSENT, false INVALID, missed ABSENT seconds).
 *
 * A second is "face not detected" when `face_detect_ratio < face_present_min_ratio`. The
 * condition has held for `(t_now - candidate_start) + RECORD_PERIOD_MS` when evaluated at record
 * `t_now`, so three consecutive no-face records confirm ABSENT with `absent_confirm_ms = 3000`.
 */
class NaiveBaselineEngine(private val params: ParameterSet) : GateEngine {
    override val engineId: String = ENGINE_ID

    private var candidateStartMonoMs: Long? = null

    override fun reset() {
        candidateStartMonoMs = null
    }

    override fun judge(record: SecondRecord): GateDecision {
        val faceDetected = record.faceDetectRatio >= params.facePresentMinRatio
        if (faceDetected) {
            candidateStartMonoMs = null
            return GateDecision(State.PRESENT)
        }
        val start = candidateStartMonoMs ?: record.tMonoMs.also { candidateStartMonoMs = it }
        val covered = (record.tMonoMs - start) + FocusSchema.RECORD_PERIOD_MS
        return if (covered >= params.absentConfirmMs) {
            GateDecision(State.ABSENT, candidateStartMonoMs = start)
        } else {
            GateDecision(State.PRESENT)
        }
    }

    companion object {
        const val ENGINE_ID: String = "naive-baseline"
    }
}
