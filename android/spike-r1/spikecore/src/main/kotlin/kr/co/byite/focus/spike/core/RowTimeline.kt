package kr.co.byite.focus.spike.core

/**
 * 초당 행(t_mono_ms, frames) 기준으로 "누락된 초"를 센다.
 *
 * 누락된 초 = 프레임이 0인 행 수 + 행 자체가 없는 초 수.
 * 행이 없는 초는 연속한 두 행의 t_mono 차이가 [gapThresholdMs] 를 넘을 때
 * round(차이/1000) − 1 로 센다 (2.0초 차이 → 1초 누락, 2.6초 → 2초 누락).
 * 프로세스가 멈춰 행도 프레임도 없던 구간을 잡기 위한 것이다.
 *
 * 실시간 기록과 CSV 재계산이 같은 정의를 쓴다.
 */
class RowTimeline(private val gapThresholdMs: Long = DEFAULT_GAP_THRESHOLD_MS) {
    var rows = 0L
        private set
    var zeroFrameRows = 0L
        private set
    var rowGaps = 0L
        private set
    var rowGapSeconds = 0L
        private set
    var maxRowGapMs = 0L
        private set
    var firstTMonoMs: Long? = null
        private set
    var lastTMonoMs: Long? = null
        private set
    var totalFrames = 0L
        private set

    val missingSeconds: Long get() = zeroFrameRows + rowGapSeconds

    /** 행 하나를 넣는다. 이 행 앞에서 행이 없던 초 수를 돌려준다 (없으면 0). */
    fun onRow(tMonoMs: Long, frames: Long): Long {
        var missedBefore = 0L
        val last = lastTMonoMs
        if (last == null) {
            firstTMonoMs = tMonoMs
        } else {
            val diff = tMonoMs - last
            if (diff > gapThresholdMs) {
                rowGaps++
                missedBefore = (diff + 500) / 1000 - 1
                rowGapSeconds += missedBefore
                if (diff > maxRowGapMs) maxRowGapMs = diff
            }
        }
        lastTMonoMs = tMonoMs
        rows++
        totalFrames += frames
        if (frames == 0L) zeroFrameRows++
        return missedBefore
    }

    companion object {
        const val DEFAULT_GAP_THRESHOLD_MS = 1500L
    }
}
