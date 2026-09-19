package co.byite.focus.core.util

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/** Small deterministic statistics helpers; pure Kotlin so the module stays multiplatform-ready. */
object Stats {
    /** Median of [values] (average of the two middle values for even counts). Null for empty input. */
    fun median(values: List<Long>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Nearest-rank percentile: the value at rank `ceil(p·n)` of the sorted list. Null for empty input. */
    fun percentileNearestRank(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        require(p > 0.0 && p <= 1.0) { "p must be in (0, 1]" }
        val s = values.sorted()
        val rank = ceil(p * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    /** Safe ratio; null when the denominator is zero. */
    fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    /** Fixed-decimal formatting without platform formatters. */
    fun fmt(x: Double?, decimals: Int): String {
        if (x == null) return "-"
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = round(abs(x) * scale).toLong()
        val intPart = scaled / scale
        val fracPart = scaled % scale
        val sign = if (x < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) "$sign$intPart" else "$sign$intPart.${fracPart.toString().padStart(decimals, '0')}"
    }

    /** Percent with [decimals] digits, e.g. 0.9512 → "95.1%". */
    fun pct(x: Double?, decimals: Int = 1): String = if (x == null) "-" else fmt(x * 100, decimals) + "%"
}
