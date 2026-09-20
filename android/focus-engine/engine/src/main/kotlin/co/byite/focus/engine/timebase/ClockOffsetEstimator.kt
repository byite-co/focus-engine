package co.byite.focus.engine.timebase

/**
 * Offset that maps a sensor clock (camera `SENSOR_TIMESTAMP`, IMU event timestamp) onto
 * `elapsedRealtimeNanos` (spec 9장 시간 기준, R3). Ported from spike-r1 `TimebaseEstimator`.
 *
 * - offset = min over the first [windowSamples] samples of (callback − event timestamp). The minimum is
 *   the sample with the least delivery latency, so the estimate is biased by at most that latency.
 * - Re-measured every [remeasureIntervalNs] (callback clock); each completed window yields a
 *   [Sample] with its drift against the initial offset. A missed window (no samples) is skipped.
 * - Until the first window completes, [provisionalNs] is the running minimum.
 */
class ClockOffsetEstimator(
    private val windowSamples: Int,
    private val remeasureIntervalNs: Long = DEFAULT_REMEASURE_INTERVAL_NS,
) {
    init {
        require(windowSamples > 0) { "windowSamples must be > 0" }
        require(remeasureIntervalNs > 0) { "remeasureIntervalNs must be > 0" }
    }

    data class Sample(val atCallbackNs: Long, val offsetNs: Long, val driftNs: Long, val samples: Int, val complete: Boolean = true)

    private var initialOffsetNs: Long? = null
    private var windowMinNs = Long.MAX_VALUE
    private var windowCount = 0
    private var windowOpen = true
    private var nextWindowAtNs = 0L
    private var latestNs: Long? = null

    /** Initial offset once the first window completed. */
    val offsetNs: Long? get() = initialOffsetNs

    /** Running minimum of the open first window; null before any sample. */
    val provisionalNs: Long? get() = if (initialOffsetNs == null && windowCount > 0) windowMinNs else null

    /** Most recent completed window's offset (initial one until re-measured). */
    val latestOffsetNs: Long? get() = latestNs

    /** Best current estimate: latest completed window, else the provisional minimum, else 0. */
    val currentOffsetNs: Long get() = latestNs ?: provisionalNs ?: 0L

    /** Feed one (event timestamp, callback time) pair. Returns a sample when a window completes. */
    fun onSample(eventTsNs: Long, callbackNs: Long): Sample? {
        if (!windowOpen) {
            if (callbackNs < nextWindowAtNs) return null
            windowOpen = true
            windowMinNs = Long.MAX_VALUE
            windowCount = 0
        }
        val diff = callbackNs - eventTsNs
        if (diff < windowMinNs) windowMinNs = diff
        windowCount++
        if (windowCount < windowSamples) return null

        windowOpen = false
        val initial = initialOffsetNs
        if (initial == null) {
            nextWindowAtNs = callbackNs + remeasureIntervalNs
        } else {
            nextWindowAtNs += remeasureIntervalNs
            while (nextWindowAtNs <= callbackNs) nextWindowAtNs += remeasureIntervalNs
        }
        latestNs = windowMinNs
        return if (initial == null) {
            initialOffsetNs = windowMinNs
            Sample(callbackNs, windowMinNs, 0L, windowCount)
        } else {
            Sample(callbackNs, windowMinNs, windowMinNs - initial, windowCount)
        }
    }

    /** Close an open partial window at session end. */
    fun finish(callbackNs: Long): Sample? {
        if (!windowOpen || windowCount == 0) return null
        windowOpen = false
        val initial = initialOffsetNs
        latestNs = windowMinNs
        return if (initial == null) {
            initialOffsetNs = windowMinNs
            Sample(callbackNs, windowMinNs, 0L, windowCount, complete = false)
        } else {
            Sample(callbackNs, windowMinNs, windowMinNs - initial, windowCount, complete = false)
        }
    }

    companion object {
        const val DEFAULT_REMEASURE_INTERVAL_NS: Long = 60_000_000_000L
    }
}
