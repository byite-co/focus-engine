package co.byite.focus.core.model

/** Versions and constants shared by the record schema (spec 9장 세션 header). */
object FocusSchema {
    /** Design baseline this code implements. Frozen; changes go to CHANGELOG v0.2.x. */
    const val SPEC_VERSION: String = "0.2.0"

    /** Version of the per-second record layout ([SecondRecord] v0 subset). Bump on any field change. */
    const val FEATURE_SCHEMA_VERSION: String = "v0.1"

    /**
     * Nominal length of one per-second record. A record stamped `t_mono_ms = t` summarises the
     * half-open window `[t, t + RECORD_PERIOD_MS)`. The spec does not fix start-vs-end stamping;
     * this convention is an implementation assumption (see README).
     */
    const val RECORD_PERIOD_MS: Long = 1_000L
}
