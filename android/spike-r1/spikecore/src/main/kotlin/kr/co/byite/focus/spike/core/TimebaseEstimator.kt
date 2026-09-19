package kr.co.byite.focus.spike.core

/**
 * 프레임 timestamp → elapsedRealtime 환산 offset 과 drift. 순수 로직.
 *
 * - offset = 처음 [windowFrames] 프레임의 (콜백 elapsedRealtimeNanos − 프레임 ts) 최솟값.
 * - 이후 [remeasureIntervalNs] 마다 같은 방법으로 다시 재서 drift = offset_k − offset_0 을 남긴다.
 * - 재측정 창은 콜백 시각 기준으로 연다. 첫 offset 확정 시각 T0 뒤 T0 + k·interval 마다 창을 열고,
 *   창이 열린 뒤 [windowFrames] 개가 쌓이면 표본이 확정된다. 창을 놓치면(정지 등) 다음 경계로 건너뛴다.
 */
class TimebaseEstimator(
    private val windowFrames: Int = DEFAULT_WINDOW_FRAMES,
    private val remeasureIntervalNs: Long = DEFAULT_REMEASURE_INTERVAL_NS,
) {
    init {
        require(windowFrames > 0) { "windowFrames must be > 0" }
        require(remeasureIntervalNs > 0) { "remeasureIntervalNs must be > 0" }
    }

    private var initialOffsetNs: Long? = null
    private var windowMinNs = Long.MAX_VALUE
    private var windowCount = 0
    private var windowOpen = true
    private var nextWindowAtNs = 0L
    private var frames = 0L
    private val samples = ArrayList<TimebaseSample>()

    /** 확정된 초기 offset (ns). 아직 없으면 null. */
    val offsetNs: Long? get() = initialOffsetNs

    /** 확정된 drift 표본 (초기 offset 확정 표본 제외). */
    val driftSamples: List<TimebaseSample> get() = samples

    /**
     * 프레임 하나를 넣는다. 창이 닫히면서 표본이 확정되면 그 표본을 돌려준다.
     * 첫 표본은 초기 offset 확정(drift 0)이고 [driftSamples] 에는 넣지 않는다.
     */
    fun onFrame(captureTsNs: Long, callbackNs: Long): TimebaseSample? {
        frames++
        if (!windowOpen) {
            if (callbackNs < nextWindowAtNs) return null
            windowOpen = true
            windowMinNs = Long.MAX_VALUE
            windowCount = 0
        }
        val diff = callbackNs - captureTsNs
        if (diff < windowMinNs) windowMinNs = diff
        windowCount++
        if (windowCount < windowFrames) return null

        windowOpen = false
        val initial = initialOffsetNs
        if (initial == null) {
            nextWindowAtNs = callbackNs + remeasureIntervalNs
        } else {
            // 이번 창의 예정 시각 기준으로 다음 경계를 잡는다. 이미 지났으면 건너뛴다.
            nextWindowAtNs += remeasureIntervalNs
            while (nextWindowAtNs <= callbackNs) nextWindowAtNs += remeasureIntervalNs
        }
        return if (initial == null) {
            initialOffsetNs = windowMinNs
            TimebaseSample(atCallbackNs = callbackNs, offsetNs = windowMinNs, driftNs = 0L, frames = windowCount)
        } else {
            val s = TimebaseSample(
                atCallbackNs = callbackNs,
                offsetNs = windowMinNs,
                driftNs = windowMinNs - initial,
                frames = windowCount,
            )
            samples.add(s)
            s
        }
    }

    /**
     * 세션 종료 시 아직 창이 열려 있으면 부분 표본으로 마무리한다.
     * 초기 offset 도 없으면 부분 창의 최솟값을 초기 offset 으로 쓴다 (complete=false).
     */
    fun finish(atCallbackNs: Long): TimebaseSample? {
        if (!windowOpen || windowCount == 0) return null
        windowOpen = false
        val initial = initialOffsetNs
        return if (initial == null) {
            initialOffsetNs = windowMinNs
            TimebaseSample(atCallbackNs, windowMinNs, 0L, windowCount, complete = false)
        } else {
            val s = TimebaseSample(atCallbackNs, windowMinNs, windowMinNs - initial, windowCount, complete = false)
            samples.add(s)
            s
        }
    }

    fun summary(): TimebaseSummary {
        val initial = initialOffsetNs
        val last = samples.lastOrNull()
        return TimebaseSummary(
            offsetNs = initial,
            lastDriftNs = last?.driftNs,
            maxAbsDriftNs = samples.maxOfOrNull { kotlin.math.abs(it.driftNs) },
            remeasureCount = samples.size,
            frames = frames,
        )
    }

    companion object {
        const val DEFAULT_WINDOW_FRAMES = 100
        const val DEFAULT_REMEASURE_INTERVAL_NS = 60_000_000_000L
    }
}

data class TimebaseSample(
    /** 표본이 확정된 콜백 elapsedRealtimeNanos. */
    val atCallbackNs: Long,
    val offsetNs: Long,
    val driftNs: Long,
    val frames: Int,
    /** windowFrames 를 다 채우고 확정됐는지. */
    val complete: Boolean = true,
)

data class TimebaseSummary(
    val offsetNs: Long?,
    val lastDriftNs: Long?,
    val maxAbsDriftNs: Long?,
    val remeasureCount: Int,
    val frames: Long,
)
