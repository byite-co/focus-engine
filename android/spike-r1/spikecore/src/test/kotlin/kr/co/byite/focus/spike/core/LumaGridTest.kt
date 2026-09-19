package kr.co.byite.focus.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LumaGridTest {
    @Test
    fun uniformPlaneGivesThatValue() {
        val m = LumaGrid.mean(1280, 720) { _, _ -> 100 }
        assertEquals(100.0, m, 1e-9)
    }

    @Test
    fun samples192PointsInsideBounds() {
        var n = 0
        var maxX = -1
        var maxY = -1
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        LumaGrid.mean(1280, 720) { x, y ->
            n++
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (x < minX) minX = x
            if (y < minY) minY = y
            0
        }
        assertEquals(16 * 12, n)
        assertTrue(minX >= 0 && maxX < 1280)
        assertTrue(minY >= 0 && maxY < 720)
        // 셀 중심: 첫 x = 1280/32 = 40, 마지막 x = 31*1280/32 = 1240
        assertEquals(40, minX)
        assertEquals(1240, maxX)
        assertEquals(30, minY)
        assertEquals(690, maxY)
    }

    @Test
    fun treatsSampleAsUnsignedByte() {
        // Byte.toInt() 가 음수를 주는 경우(≥128)를 0–255 로 본다.
        val m = LumaGrid.mean(16, 12) { _, _ -> (-56).toByte().toInt() } // 200
        assertEquals(200.0, m, 1e-9)
    }

    @Test
    fun averagesHalfAndHalf() {
        val m = LumaGrid.mean(1280, 720) { x, _ -> if (x < 640) 0 else 200 }
        assertEquals(100.0, m, 1e-9)
    }

    @Test
    fun tinyPlaneClampsToBounds() {
        var bad = false
        LumaGrid.mean(1, 1) { x, y -> if (x != 0 || y != 0) bad = true; 7 }
        assertTrue(!bad)
    }

    @Test
    fun rejectsEmptyInput() {
        assertFailsWith<IllegalArgumentException> { LumaGrid.mean(0, 720) { _, _ -> 0 } }
        assertFailsWith<IllegalArgumentException> { LumaGrid.mean(1280, 720, cols = 0) { _, _ -> 0 } }
    }
}
