package co.byite.focus.core.model

/** Versions and constants shared by the record schema (spec 9장 세션 header). */
object FocusSchema {
    /** Design baseline this code implements. Frozen; changes go to CHANGELOG v0.2.x. */
    const val SPEC_VERSION: String = "0.2.0"

    /** Version of the per-second record layout ([SecondRecord]). 0.2.1 = CHANGELOG v0.2.1 schema. */
    const val FEATURE_SCHEMA_VERSION: String = "0.2.1"

    /**
     * Length of one per-second bucket. A record stamped `t_mono_ms = t` summarises the half-open
     * bucket `[t, t + RECORD_PERIOD_MS)`; buckets are aligned to the session start and the record is
     * written when the bucket ends (v0.2.1 판정 1).
     */
    const val RECORD_PERIOD_MS: Long = 1_000L

    /** Number of whole buckets a duration covers, rounding up (3000 ms → 3 buckets). */
    fun buckets(durationMs: Long): Int = ((durationMs + RECORD_PERIOD_MS - 1) / RECORD_PERIOD_MS).toInt()
}
