package co.byite.focus.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsTest {
    @Test
    fun medianAndPercentile() {
        assertNull(Stats.median(emptyList()))
        assertEquals(3.0, Stats.median(listOf(5, 1, 3)))
        assertEquals(2.5, Stats.median(listOf(4, 1, 3, 2)))
        assertNull(Stats.percentileNearestRank(emptyList(), 0.95))
        assertEquals(19, Stats.percentileNearestRank((1L..20L).toList(), 0.95))
        assertEquals(95, Stats.percentileNearestRank((1L..100L).toList(), 0.95))
        assertEquals(96, Stats.percentileNearestRank((1L..100L).toList(), 0.951))
        assertEquals(7, Stats.percentileNearestRank(listOf(7), 0.5))
    }

    @Test
    fun formatting() {
        assertEquals("0.50", Stats.fmt(0.5, 2))
        assertEquals("-1.2", Stats.fmt(-1.25, 1))
        assertEquals("3", Stats.fmt(2.6, 0))
        assertEquals("0.00", Stats.fmt(-0.001, 2))
        assertEquals("-", Stats.fmt(null, 2))
        assertEquals("95.1%", Stats.pct(0.9512))
        assertEquals("100.0%", Stats.pct(1.0))
    }
}
