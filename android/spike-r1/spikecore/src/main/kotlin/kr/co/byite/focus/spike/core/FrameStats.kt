package kr.co.byite.focus.spike.core

/**
 * 프레임 capture timestamp 로 갭 통계를 낸다. 순수 로직, Android 의존 없음.
 *
 * - 갭은 연속한 두 프레임의 capture timestamp 차이다. 콜백 도착 시각은 쓰지 않는다.
 * - "누락된 초" 는 첫 프레임 기준 1초 버킷 중 프레임이 하나도 없는 버킷 수다.
 * - 구간(interval) 통계는 [takeInterval] 로 꺼내고 초기화한다. 초당 CSV 행에 쓴다.
 */
class FrameStats(
    private val gapThresholdNs: Long = DEFAULT_GAP_THRESHOLD_NS,
    private val longGapThresholdNs: Long = DEFAULT_LONG_GAP_THRESHOLD_NS,
) {
    // 세션 누적
    private var totalFrames = 0L
    private var firstTsNs = 0L
    private var lastTsNs = 0L
    private var lastBucket = 0L
    private var missingSeconds = 0L
    private var gapsOverThreshold = 0L
    private var gapsOverLong = 0L
    private var maxGapNs = 0L
    private var gapCount = 0L
    private var nonMonotonic = 0L

    // 현재 구간 누적
    private var intFrames = 0L
    private var intMaxGapNs = 0L
    private var intGapsOverThreshold = 0L
    private var intLatencySumNs = 0L

    /**
     * 프레임 하나를 넣는다. 직전 프레임과의 갭(ns)을 돌려준다.
     * 첫 프레임이거나 timestamp 가 역행하면 -1.
     */
    fun onFrame(captureTsNs: Long, callbackNs: Long): Long {
        var gapOut = -1L
        if (totalFrames == 0L) {
            firstTsNs = captureTsNs
            lastBucket = 0L
        } else {
            val gap = captureTsNs - lastTsNs
            if (gap < 0L) {
                nonMonotonic++
            } else {
                gapOut = gap
                gapCount++
                if (gap > maxGapNs) maxGapNs = gap
                if (gap > gapThresholdNs) gapsOverThreshold++
                if (gap > longGapThresholdNs) gapsOverLong++
                if (gap > intMaxGapNs) intMaxGapNs = gap
                if (gap > gapThresholdNs) intGapsOverThreshold++
            }
            val bucket = (captureTsNs - firstTsNs) / NS_PER_S
            if (bucket > lastBucket + 1) missingSeconds += bucket - lastBucket - 1
            if (bucket > lastBucket) lastBucket = bucket
        }
        if (captureTsNs > lastTsNs) lastTsNs = captureTsNs
        totalFrames++
        intFrames++
        intLatencySumNs += callbackNs - captureTsNs
        return gapOut
    }

    /** 현재 구간 통계를 돌려주고 구간 누적을 비운다. */
    fun takeInterval(): IntervalStats {
        val out = IntervalStats(
            frames = intFrames,
            maxGapMs = intMaxGapNs / NS_PER_MS_D,
            gapsOverThreshold = intGapsOverThreshold,
            callbackLatencyMeanMs = if (intFrames > 0) intLatencySumNs / intFrames / NS_PER_MS_D else null,
        )
        intFrames = 0
        intMaxGapNs = 0
        intGapsOverThreshold = 0
        intLatencySumNs = 0
        return out
    }

    fun summary(): FrameSummary {
        val spanNs = if (totalFrames > 1) lastTsNs - firstTsNs else 0L
        val avgFps = if (spanNs > 0) (totalFrames - 1) * NS_PER_S_D / spanNs else 0.0
        return FrameSummary(
            totalFrames = totalFrames,
            gapCount = gapCount,
            gapsOverThreshold = gapsOverThreshold,
            gapsOverLong = gapsOverLong,
            maxGapMs = maxGapNs / NS_PER_MS_D,
            missingSeconds = missingSeconds,
            avgFps = avgFps,
            spanMs = spanNs / NS_PER_MS_D,
            nonMonotonic = nonMonotonic,
        )
    }

    companion object {
        const val NS_PER_S = 1_000_000_000L
        const val NS_PER_S_D = 1_000_000_000.0
        const val NS_PER_MS_D = 1_000_000.0
        const val DEFAULT_GAP_THRESHOLD_NS = 80_000_000L
        const val DEFAULT_LONG_GAP_THRESHOLD_NS = 1_000_000_000L
    }
}

/** 초당 CSV 행에 들어가는 구간 통계. */
data class IntervalStats(
    val frames: Long,
    val maxGapMs: Double,
    val gapsOverThreshold: Long,
    /** 구간 내 (콜백 elapsedRealtimeNanos − 프레임 ts) 평균. 프레임이 없으면 null. */
    val callbackLatencyMeanMs: Double?,
)

/** 세션 요약. */
data class FrameSummary(
    val totalFrames: Long,
    val gapCount: Long,
    val gapsOverThreshold: Long,
    val gapsOverLong: Long,
    val maxGapMs: Double,
    val missingSeconds: Long,
    val avgFps: Double,
    val spanMs: Double,
    val nonMonotonic: Long,
) {
    /** 80ms 초과 갭 비율 (0.0–1.0). 갭이 없으면 0. */
    val gapsOverThresholdRatio: Double
        get() = if (gapCount > 0) gapsOverThreshold.toDouble() / gapCount else 0.0
}
