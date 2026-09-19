package kr.co.byite.focus.spike.core

/**
 * Y plane 을 cols x rows 격자로 샘플링해 평균 휘도 하나를 낸다.
 * 픽셀 접근은 [sample] 로 위임하고 값은 누적 합만 남긴다. 배열을 만들지 않는다.
 *
 * @param sample (x, y) 픽셀의 Y 값(0–255)
 */
object LumaGrid {
    const val DEFAULT_COLS = 16
    const val DEFAULT_ROWS = 12

    fun mean(
        width: Int,
        height: Int,
        cols: Int = DEFAULT_COLS,
        rows: Int = DEFAULT_ROWS,
        sample: (x: Int, y: Int) -> Int,
    ): Double {
        require(width > 0 && height > 0) { "empty plane" }
        require(cols > 0 && rows > 0) { "empty grid" }
        var sum = 0L
        var n = 0
        for (r in 0 until rows) {
            // 각 셀의 중심을 찍는다.
            val y = ((r * 2 + 1) * height / (rows * 2)).coerceIn(0, height - 1)
            for (c in 0 until cols) {
                val x = ((c * 2 + 1) * width / (cols * 2)).coerceIn(0, width - 1)
                sum += sample(x, y) and 0xFF
                n++
            }
        }
        return sum.toDouble() / n
    }
}
