package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RowTimelineTest {
    @Test
    fun steadyRowsHaveNoMissingSeconds() {
        val t = RowTimeline()
        var mono = 10_000L
        repeat(60) {
            assertEquals(0L, t.onRow(mono, 24))
            mono += 1000
        }
        assertEquals(60, t.rows)
        assertEquals(0, t.missingSeconds)
        assertEquals(0, t.zeroFrameRows)
        assertEquals(0, t.rowGaps)
        assertEquals(0, t.maxRowGapMs)
        assertEquals(60 * 24L, t.totalFrames)
        assertEquals(10_000L, t.firstTMonoMs)
        assertEquals(10_000L + 59_000L, t.lastTMonoMs)
    }

    @Test
    fun zeroFrameRowsCount() {
        val t = RowTimeline()
        t.onRow(1000, 24)
        t.onRow(2000, 0)
        t.onRow(3000, 0)
        t.onRow(4000, 3)
        assertEquals(2, t.zeroFrameRows)
        assertEquals(2, t.missingSeconds)
    }

    @Test
    fun rowGapRoundsToWholeSeconds() {
        fun gapOf(diff: Long): Long {
            val t = RowTimeline()
            t.onRow(0, 24)
            return t.onRow(diff, 24)
        }
        assertEquals(0, gapOf(1000))
        assertEquals(0, gapOf(1400))
        assertEquals(0, gapOf(1500)) // 임계 이하
        assertEquals(1, gapOf(1501))
        assertEquals(1, gapOf(2000))
        assertEquals(1, gapOf(2499))
        assertEquals(2, gapOf(2500))
        assertEquals(3, gapOf(3600))
        assertEquals(59, gapOf(60_000))
    }

    @Test
    fun missingIsZeroFrameRowsPlusRowGaps() {
        val t = RowTimeline()
        t.onRow(1000, 24)
        t.onRow(2000, 0) // 프레임 0
        t.onRow(5000, 24) // 3초 차이 → 2초 행 없음
        t.onRow(6000, 0) // 프레임 0
        t.onRow(8600, 24) // 2.6초 → 2초 행 없음
        assertEquals(2, t.zeroFrameRows)
        assertEquals(4, t.rowGapSeconds)
        assertEquals(2, t.rowGaps)
        assertEquals(3000, t.maxRowGapMs)
        assertEquals(6, t.missingSeconds)
        assertEquals(5, t.rows)
    }

    @Test
    fun customThreshold() {
        val t = RowTimeline(gapThresholdMs = 1000)
        t.onRow(0, 1)
        assertEquals(0, t.onRow(1000, 1))
        assertEquals(0, t.onRow(2001, 1)) // 1001ms: 임계 초과지만 반올림하면 1초 → 0 누락
        assertEquals(1, t.onRow(4001, 1))
    }
}
