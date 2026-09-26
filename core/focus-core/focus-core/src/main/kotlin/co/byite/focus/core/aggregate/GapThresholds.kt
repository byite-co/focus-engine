package co.byite.focus.core.aggregate

import kotlin.math.roundToLong

/**
 * Gap thresholds as a formula of the expected Face processing interval (directive E 2장, CHANGELOG v0.2.4 (a)),
 * not per-preset constants: `gap_threshold = interval × 1.5`, `long_gap_threshold = interval × 4.5`. "Over" is a
 * strict comparison. The factors sit between integer multiples of the interval on purpose: real capture timestamps
 * jitter by a few hundred µs, and a threshold exactly on a multiple flips the classification of a frame gap.
 * Computed in ns; only the summary formats ms.
 *
 * | expected interval | gap threshold | long-gap threshold |
 * |---|---|---|
 * | 24 fps, 41.7 ms | 62.5 ms | 187.5 ms |
 * | 15 Hz, 66.7 ms | 100 ms | 300 ms |
 * | 12 Hz, 83.3 ms | 125 ms | 375 ms |
 *
 * At 24 fps the classification is the same as the previous fixed 80 / 200 ms (one-frame gaps below, two-frame gaps
 * above; four-frame gaps below, five-frame gaps above), so sessions stay comparable. A single missed slot is a gap
 * over the threshold.
 */
object GapThresholds {
    /** gap_threshold = expected interval × 3 / 2. */
    fun gapThresholdNs(processPeriodNs: Long): Long {
        require(processPeriodNs > 0) { "processPeriodNs must be positive" }
        return processPeriodNs * 3 / 2
    }

    /** long_gap_threshold = expected interval × 9 / 2. */
    fun longGapThresholdNs(processPeriodNs: Long): Long {
        require(processPeriodNs > 0) { "processPeriodNs must be positive" }
        return processPeriodNs * 9 / 2
    }

    /** Expected interval of a processing rate: `1e9 / rateHz` rounded to ns (24 → 41 666 667, 15 → 66 666 667, 12 → 83 333 333). */
    fun periodNs(rateHz: Double): Long {
        require(rateHz > 0.0) { "rateHz must be positive" }
        return (1e9 / rateHz).roundToLong()
    }

    /** Camera frame interval at an integer nominal fps, same rounding as [periodNs]. */
    fun frameIntervalNs(nominalFps: Int): Long = periodNs(nominalFps.toDouble())
}
