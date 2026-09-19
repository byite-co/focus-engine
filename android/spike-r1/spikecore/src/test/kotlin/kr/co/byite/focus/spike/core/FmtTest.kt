package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {
    @Test
    fun fixedRoundsHalfUp() {
        assertEquals("41.7", Fmt.fixed(41.666667, 1))
        assertEquals("0.0", Fmt.fixed(0.0, 1))
        assertEquals("24.00", Fmt.f2(23.996))
        assertEquals("3", Fmt.fixed(2.5, 0))
        assertEquals("2", Fmt.fixed(2.4, 0))
        assertEquals("0.028", Fmt.f3(0.0278))
    }

    @Test
    fun fixedHandlesSignAndLargeValues() {
        assertEquals("-0.040", Fmt.fixed(-0.04, 3))
        assertEquals("0.000", Fmt.fixed(-0.0001, 3)) // 0 으로 반올림되면 부호 없음
        assertEquals("123456789.5", Fmt.fixed(123456789.5, 1))
        assertEquals("-2500.0", Fmt.f1(-2500.0))
        assertEquals("NaN", Fmt.f1(Double.NaN))
    }

    @Test
    fun pct() {
        assertEquals("0.028%", Fmt.pct(0.000278))
        assertEquals("100.000%", Fmt.pct(1.0))
        assertEquals("0.000%", Fmt.pct(0.0))
    }

    @Test
    fun thermalNames() {
        assertEquals("NONE", ThermalStatus.name(0))
        assertEquals("LIGHT", ThermalStatus.name(1))
        assertEquals("SHUTDOWN", ThermalStatus.name(6))
        assertEquals("UNKNOWN(9)", ThermalStatus.name(9))
    }
}
