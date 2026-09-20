package co.byite.focus.engine.pipeline.scene

import kotlin.math.sqrt

/** Whole-frame luma mean and the 4×4 tile texture summary (spec 6장 scene proxy). */
data class SceneStats(val lumaMean: Double, val tileTextureMin: Double, val tileTextureMedian: Double)

/**
 * Samples the frame on a [COLS]×[ROWS] grid (cell centres), splits the grid into [TILES]×[TILES] tiles
 * and reports the texture (population std-dev of Y) minimum and median over the 16 tiles plus the
 * global mean. Pixel access is delegated to [compute]'s sampler; nothing but running sums is kept.
 */
object SceneGrid {
    const val COLS = 64
    const val ROWS = 48
    const val TILES = 4

    fun compute(width: Int, height: Int, sample: (x: Int, y: Int) -> Int): SceneStats {
        require(width > 0 && height > 0) { "empty frame" }
        val tileCols = COLS / TILES
        val tileRows = ROWS / TILES
        val sum = DoubleArray(TILES * TILES)
        val sumSq = DoubleArray(TILES * TILES)
        var total = 0.0
        for (r in 0 until ROWS) {
            val y = ((r * 2 + 1) * height / (ROWS * 2)).coerceIn(0, height - 1)
            val tr = r / tileRows
            for (c in 0 until COLS) {
                val x = ((c * 2 + 1) * width / (COLS * 2)).coerceIn(0, width - 1)
                val v = (sample(x, y) and 0xFF).toDouble()
                val t = tr * TILES + c / tileCols
                sum[t] += v
                sumSq[t] += v * v
                total += v
            }
        }
        val n = (tileCols * tileRows).toDouble()
        val textures = DoubleArray(TILES * TILES) { t ->
            val mean = sum[t] / n
            sqrt((sumSq[t] / n - mean * mean).coerceAtLeast(0.0))
        }
        textures.sort()
        val median = (textures[7] + textures[8]) / 2.0
        return SceneStats(lumaMean = total / (COLS * ROWS), tileTextureMin = textures[0], tileTextureMedian = median)
    }

    /** BT.601 luma from 8-bit RGB, integer arithmetic. */
    fun luma(r: Int, g: Int, b: Int): Int = (77 * (r and 0xFF) + 150 * (g and 0xFF) + 29 * (b and 0xFF)) shr 8
}
