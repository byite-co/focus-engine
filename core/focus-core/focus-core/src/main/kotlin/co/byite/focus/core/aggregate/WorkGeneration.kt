package co.byite.focus.core.aggregate

/**
 * Generation token for in-flight pipeline work (code review of PR #7, item 2). A worker captures
 * [current] when it starts a task; the stop path calls [bump] when its bounded wait times out, and a task
 * whose captured generation is no longer [isCurrent] must neither post its result to the aggregation
 * queue nor be counted as completed / processed — it is exactly the work counted as `*_cancelled_at_stop`.
 * Pure Kotlin so the same class serves the device layer and the stop-order tests.
 */
class WorkGeneration {
    @Volatile private var gen: Long = 0L

    val current: Long get() = gen

    /** Invalidate every task started before now; returns the new generation. */
    @Synchronized fun bump(): Long = ++gen

    fun isCurrent(generation: Long): Boolean = generation == gen
}
