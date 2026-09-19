package kr.co.byite.focus.spike.core

import kotlin.math.abs

/** 순수 Kotlin 숫자 포맷. 로케일·java.text 없이 고정 소수점만 만든다. */
object Fmt {
    fun fixed(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (abs(v) * scale + 0.5).toLong()
        val ip = scaled / scale
        val fp = scaled % scale
        val sign = if (v < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) "$sign$ip" else "$sign$ip." + fp.toString().padStart(decimals, '0')
    }

    fun f1(v: Double): String = fixed(v, 1)
    fun f2(v: Double): String = fixed(v, 2)
    fun f3(v: Double): String = fixed(v, 3)

    /** 0.0–1.0 비율을 "12.345%" 로. */
    fun pct(ratio: Double): String = fixed(ratio * 100, 3) + "%"
}

/** PowerManager thermal status 값(0–6)의 이름. Android 상수와 같은 순서다. */
object ThermalStatus {
    fun name(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
