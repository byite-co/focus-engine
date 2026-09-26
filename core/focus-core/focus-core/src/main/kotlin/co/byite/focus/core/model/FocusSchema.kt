package co.byite.focus.core.model

/** Versions and constants shared by the record schema (spec 9장 세션 header). */
object FocusSchema {
    /** Design baseline this code implements. Frozen; changes go to CHANGELOG v0.2.x. */
    const val SPEC_VERSION: String = "0.2.0"

    /**
     * Version of the per-second record layout ([SecondRecord]). 0.2.1 = CHANGELOG v0.2.1 schema;
     * 0.2.2 = CHANGELOG v0.2.2 (`zone_status.uncalibrated`, `v0b_raw` line); 0.2.3 = CHANGELOG v0.2.3
     * (frame counters `frames_analyzer_received` / `frames_skipped_intentional` / `gaps_over_threshold`,
     * capture preset header fields, stage timings and pose counters on the `v0b_raw` line); 0.2.4 = CHANGELOG v0.2.4
     * (processing-slot counters and capture-interval statistics on `v0b_raw`, camera cadence / Face schedule /
     * ns gap-threshold header fields, stop diagnostics on `session_end`).
     * Additive only: 0.2.1, 0.2.2 and 0.2.3 logs still decode (missing fields take the documented defaults).
     */
    const val FEATURE_SCHEMA_VERSION: String = "0.2.4"

    /**
     * Length of one per-second bucket. A record stamped `t_mono_ms = t` summarises the half-open
     * bucket `[t, t + RECORD_PERIOD_MS)`; buckets are aligned to the session start and the record is
     * written when the bucket ends (v0.2.1 판정 1).
     */
    const val RECORD_PERIOD_MS: Long = 1_000L

    /**
     * Warm-up: the first 60 s of a session. The summary's comparison rows exclude them (CHANGELOG v0.2.3 (b)), and a
     * session without a fixed AE request learns its diagnostic expected interval from their capture-result intervals (E2 1장).
     */
    const val WARMUP_MS: Long = 60_000L

    /** Number of whole buckets a duration covers, rounding up (3000 ms → 3 buckets). */
    fun buckets(durationMs: Long): Int = ((durationMs + RECORD_PERIOD_MS - 1) / RECORD_PERIOD_MS).toInt()
}
