package co.byite.focus.core.gt

import co.byite.focus.core.log.FocusJson
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Ground-truth timeline file (v0-plan 5장 파일 형식). Times are relative to the session start cue. */
@Serializable
data class GtFile(
    @SerialName("session_id") val sessionId: String,
    @SerialName("scenario_id") val scenarioId: String,
    /** "scripted" (대본 GT) or "observed" (관찰 GT). Only scripted GT feeds the pass line. */
    @SerialName("gt_type") val gtType: String = GT_TYPE_SCRIPTED,
    /** `t_mono_ms` of the session start cue; every `t_*_ms` below is relative to it. */
    @SerialName("start_cue_t_mono_ms") val startCueTMonoMs: Long,
    val intervals: List<GtInterval>,
    val void: List<GtVoid> = emptyList(),
) {
    val isScripted: Boolean get() = gtType == GT_TYPE_SCRIPTED

    companion object {
        const val GT_TYPE_SCRIPTED: String = "scripted"
        const val GT_TYPE_OBSERVED: String = "observed"
    }
}

/** A GT snapped to the session's bucket grid, with the rounding warnings for observed GT. */
data class AlignedGt(val file: GtFile, val warnings: List<String>)

/**
 * One scripted behaviour. Its start is the cue time. [expectedState] is optional and informational:
 * the tool derives the expected state from [behavior] (v0-plan 5장: 손으로 적지 않는다) and warns when
 * an explicit value disagrees. It is only used as-is for behaviours the catalog does not know.
 */
@Serializable
data class GtInterval(
    @SerialName("t_start_ms") val tStartMs: Long,
    @SerialName("t_end_ms") val tEndMs: Long,
    val behavior: String,
    @SerialName("expected_state") val expectedState: State? = null,
) {
    val durationMs: Long get() = tEndMs - tStartMs
}

/** Span excluded from scoring by the observer (missed cue, unscripted behaviour). */
@Serializable
data class GtVoid(
    @SerialName("t_start_ms") val tStartMs: Long,
    @SerialName("t_end_ms") val tEndMs: Long,
    val reason: String = "",
)

/** Thrown when a GT file is malformed. */
class GtFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object GtParser {
    fun parse(text: String): GtFile {
        val gt = try {
            FocusJson.compact.decodeFromString(GtFile.serializer(), text)
        } catch (e: SerializationException) {
            throw GtFormatException("invalid GT JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw GtFormatException("invalid GT JSON: ${e.message}", e)
        }
        validate(gt)
        return gt
    }

    fun validate(gt: GtFile) {
        if (gt.intervals.isEmpty()) throw GtFormatException("GT has no intervals")
        gt.intervals.forEachIndexed { i, iv ->
            if (iv.tStartMs < 0) throw GtFormatException("interval $i starts before the start cue (${iv.tStartMs})")
            if (iv.tEndMs <= iv.tStartMs) throw GtFormatException("interval $i has non-positive duration (${iv.tStartMs}..${iv.tEndMs})")
            if (iv.behavior.isBlank()) throw GtFormatException("interval $i has an empty behavior")
            if (i > 0) {
                val prev = gt.intervals[i - 1]
                if (iv.tStartMs < prev.tEndMs) {
                    throw GtFormatException("interval $i (${iv.tStartMs}) overlaps or precedes interval ${i - 1} (ends ${prev.tEndMs}); intervals must be sorted and disjoint")
                }
            }
        }
        gt.void.forEachIndexed { i, v ->
            if (v.tEndMs <= v.tStartMs) throw GtFormatException("void $i has non-positive duration (${v.tStartMs}..${v.tEndMs})")
        }
        if (gt.isScripted) {
            gt.intervals.forEachIndexed { i, iv ->
                if (iv.tStartMs % FocusSchema.RECORD_PERIOD_MS != 0L || iv.tEndMs % FocusSchema.RECORD_PERIOD_MS != 0L) {
                    throw GtFormatException("scripted GT interval $i (${iv.behavior}) is not on the bucket grid: ${iv.tStartMs}..${iv.tEndMs} ms must be multiples of ${FocusSchema.RECORD_PERIOD_MS}")
                }
            }
        }
    }

    /**
     * Align the GT to the session's bucket grid (v0.2.1 판정 7, 2차). Scripted GT must start at
     * `header.t_start_mono_ms` with every cue on a bucket boundary, else [GtFormatException].
     * Observed GT is rounded to the nearest boundary with a warning per moved value.
     */
    fun alignToSession(gt: GtFile, sessionStartMonoMs: Long): AlignedGt {
        validate(gt)
        val period = FocusSchema.RECORD_PERIOD_MS
        if (gt.isScripted) {
            if (gt.startCueTMonoMs != sessionStartMonoMs) {
                throw GtFormatException("scripted GT start_cue_t_mono_ms ${gt.startCueTMonoMs} must equal header.t_start_mono_ms $sessionStartMonoMs")
            }
            return AlignedGt(gt, emptyList())
        }
        val warnings = ArrayList<String>()
        fun nearest(abs: Long): Long = sessionStartMonoMs + floorDiv(abs - sessionStartMonoMs + period / 2, period) * period
        val cue = nearest(gt.startCueTMonoMs)
        if (cue != gt.startCueTMonoMs) warnings.add("observed GT: start cue ${gt.startCueTMonoMs} rounded to bucket boundary $cue")
        val intervals = gt.intervals.mapIndexed { i, iv ->
            val start = nearest(gt.startCueTMonoMs + iv.tStartMs) - cue
            val end = nearest(gt.startCueTMonoMs + iv.tEndMs) - cue
            if (start != iv.tStartMs || end != iv.tEndMs) warnings.add("observed GT: interval $i (${iv.behavior}) ${iv.tStartMs}..${iv.tEndMs} rounded to $start..$end")
            iv.copy(tStartMs = start, tEndMs = end)
        }
        val void = gt.void.map { v ->
            val start = nearest(gt.startCueTMonoMs + v.tStartMs) - cue
            val end = nearest(gt.startCueTMonoMs + v.tEndMs) - cue
            if (start != v.tStartMs || end != v.tEndMs) warnings.add("observed GT: void ${v.tStartMs}..${v.tEndMs} rounded to $start..$end")
            v.copy(tStartMs = start, tEndMs = end)
        }
        val aligned = gt.copy(startCueTMonoMs = cue, intervals = intervals, void = void)
        try {
            validate(aligned)
        } catch (e: GtFormatException) {
            throw GtFormatException("observed GT is invalid after rounding to the bucket grid: ${e.message}", e)
        }
        return AlignedGt(aligned, warnings)
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    /**
     * Non-fatal script checks that need the [ParameterSet] (v0.2.1 판정 6): a
     * `phone_redock_recalibrating` interval must cover the stationary confirmation plus the
     * recalibration (`redock_stationary_confirm_ms + recalibration_ms`).
     */
    fun lint(gt: GtFile, params: ParameterSet): List<String> {
        val warnings = ArrayList<String>()
        val minRecal = params.redockStationaryConfirmMs + params.recalibrationMs
        gt.intervals.forEachIndexed { i, iv ->
            if (iv.behavior == BehaviorCatalog.PHONE_REDOCK_RECALIBRATING && iv.durationMs < minRecal) {
                warnings.add("interval $i (${iv.behavior}): ${iv.durationMs} ms is shorter than redock_stationary_confirm_ms + recalibration_ms = $minRecal ms")
            }
        }
        return warnings
    }
}
