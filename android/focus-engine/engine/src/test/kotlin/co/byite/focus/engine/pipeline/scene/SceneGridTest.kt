package co.byite.focus.engine.pipeline.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneGridTest {
    @Test
    fun uniformFrameHasItsValueAndZeroTexture() {
        val s = SceneGrid.compute(1280, 720) { _, _ -> 100 }
        assertEquals(100.0, s.lumaMean, 1e-9)
        assertEquals(0.0, s.tileTextureMin, 1e-9)
        assertEquals(0.0, s.tileTextureMedian, 1e-9)
    }

    @Test
    fun samples64x48CellCentresInsideTheFrame() {
        var n = 0
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        SceneGrid.compute(1280, 720) { x, y ->
            n++
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            0
        }
        assertEquals(64 * 48, n)
        assertEquals(10, minX)
        assertEquals(1270, maxX)
        assertEquals(7, minY)
        assertEquals(712, maxY)
    }

    @Test
    fun aCheckerboardTileHasTextureHalfRangeAndTheMedianSplitsTheTiles() {
        // left half of the frame: alternating 0/200 per sampled column (std-dev 100); right half flat
        var col = 0
        val s = SceneGrid.compute(64, 48) { x, _ ->
            col = x
            if (x < 32) (if ((x / 1) % 2 == 0) 0 else 200) else 50
        }
        assertTrue(col >= 0)
        assertEquals(0.0, s.tileTextureMin, 1e-9)
        // 8 textured tiles (100) and 8 flat tiles (0): median = (0 + 100) / 2
        assertEquals(50.0, s.tileTextureMedian, 1e-9)
        assertEquals((100.0 + 50.0) / 2, s.lumaMean, 1e-9)
    }

    @Test
    fun lumaFormulaIsBt601Ish() {
        assertEquals(0, SceneGrid.luma(0, 0, 0))
        assertEquals(255, SceneGrid.luma(255, 255, 255))
        assertEquals(149, SceneGrid.luma(0, 255, 0))
    }
}
