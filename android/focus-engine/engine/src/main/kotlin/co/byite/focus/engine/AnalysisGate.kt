package co.byite.focus.engine

import co.byite.focus.core.aggregate.WorkGeneration

/**
 * Generation gate of the analysis thread's per-frame work (code review of PR #7, item 2). A frame calls
 * [begin] before posting `onFrameReceived` and [end] before posting its single outcome
 * (skipped / error / processed). The stop path calls [cancel] when its bounded wait for the analysis thread
 * times out: the generation is bumped, so an in-flight frame's [end] returns false and its outcome is never
 * posted nor counted; [cancel] returns 1 exactly when such a frame had already posted `received`
 * (that is the frame `frames_cancelled_at_stop` accounts for). A frame that reaches [begin] after the bump is
 * dropped before posting anything, so it appears in no counter. Pure Kotlin, unit-tested on the JVM.
 */
class AnalysisGate(private val generation: WorkGeneration = WorkGeneration()) {
    private val lock = Any()
    private var inFlightGeneration: Long? = null
    private var inFlightCaptureNs: Long? = null
    private var closed = false

    /** Suppressed outcomes so far (event log). */
    var suppressed: Long = 0L
        private set

    /** Analysis thread: returns the frame's generation, or null once the stop path closed the gate (post nothing, count nothing). */
    fun begin(captureNs: Long): Long? = synchronized(lock) {
        if (closed) return null
        val g = generation.current
        inFlightGeneration = g
        inFlightCaptureNs = captureNs
        g
    }

    /** Analysis thread: true when the outcome of the frame started with [begin] may be posted. */
    fun end(frameGeneration: Long): Boolean = synchronized(lock) {
        inFlightGeneration = null
        inFlightCaptureNs = null
        val ok = generation.isCurrent(frameGeneration)
        if (!ok) suppressed++
        ok
    }

    /**
     * Stop thread, after the bounded wait timed out: bumps the generation (the in-flight frame's outcome is
     * suppressed) and closes the gate for any later frame. Returns how many frames are counted as cancelled:
     * 1 when a frame had posted `received` and is still running, else 0.
     */
    fun cancel(): Long = synchronized(lock) {
        closed = true
        generation.bump()
        if (inFlightGeneration != null) 1L else 0L
    }

    val inFlight: Boolean get() = synchronized(lock) { inFlightGeneration != null }
}
