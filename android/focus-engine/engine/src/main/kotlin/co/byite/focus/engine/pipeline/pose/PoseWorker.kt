package co.byite.focus.engine.pipeline.pose

import android.os.SystemClock
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.engine.pipeline.RgbaFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Asynchronous Pose worker (directive D 정정 2·3·5): its own thread `focus-pose`, a waiting slot of depth 1
 * and one run in flight. [submit] deep-copies the frame on the caller's (analysis) thread, keeping the capture
 * timestamp; a request still waiting when a newer one arrives is superseded. Results leave as [PoseSample]
 * scalars through [Sink] (called on the worker thread; the service posts them to the aggregation queue).
 * The two pixel buffers never leave this class and are freed by [release].
 *
 * Stop contract: [closeSlot] discards the waiting request and refuses new ones; [awaitIdle] waits for the run
 * in flight up to a bound and reports how many requests are counted as cancelled.
 */
class PoseWorker(private val pipeline: PosePipeline, private val sink: Sink) {
    interface Sink {
        fun onPose(sample: PoseSample)
        fun onPoseError(captureMonoNs: Long, error: Throwable)
        fun onPoseSuperseded(captureMonoNs: Long)
    }

    /** Result of one [submit]: copy time and whether a waiting request was replaced. */
    data class Submitted(val copyMs: Double, val supersededCaptureNs: Long?)

    private class Request(val buffer: ByteBuffer, val width: Int, val height: Int, val rotation: Int, val captureNs: Long, val tsMs: Long, val submittedNs: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var buffers: Array<ByteBuffer?> = arrayOfNulls(2)
    private var waiting: Request? = null
    private var inFlight: Request? = null
    private var closed = false
    private var released = false
    private var cancelledWaiting = 0L
    private val thread = Thread({ loop() }, "focus-pose")

    /** Requests handed in so far (analysis thread). */
    var submitted: Long = 0L
        private set

    fun start() {
        thread.start()
    }

    /**
     * Analysis thread: deep-copy [frame] into the waiting slot and wake the worker. Returns null when the slot is
     * closed (stop in progress). The copy runs under the lock, so a worker finishing at the same moment waits
     * at most one copy (~1 ms at 1280×720).
     */
    fun submit(frame: RgbaFrame, tsMs: Long): Submitted? {
        val t0 = SystemClock.elapsedRealtimeNanos()
        lock.withLock {
            if (closed) return null
            val superseded = waiting?.captureNs
            val target = waiting?.buffer ?: freeBuffer(frame.width * 4 * frame.height)
            val src = frame.pixels
            src.rewind()
            target.clear()
            target.put(src)
            target.rewind()
            src.rewind()
            waiting = Request(target, frame.width, frame.height, frame.rotationDegrees, frame.captureMonoNs, tsMs, t0)
            submitted++
            changed.signalAll()
            val copyMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            return Submitted(copyMs, superseded)
        }
    }

    /** True while a run is in flight or a request waits. */
    val isBusy: Boolean get() = lock.withLock { inFlight != null || waiting != null }

    /** Stop step 2: the waiting slot takes nothing more; a request still waiting is discarded and counted as cancelled. */
    fun closeSlot() {
        lock.withLock {
            closed = true
            if (waiting != null) {
                waiting = null
                cancelledWaiting++
            }
            changed.signalAll()
        }
    }

    /**
     * Stop step 3: wait up to [timeoutMs] for the run in flight. Returns the requests counted as
     * `pose_cancelled_at_stop`: the discarded waiting request plus the in-flight run when it did not finish in time
     * (its late result is rejected by the aggregator after finish).
     */
    fun awaitIdle(timeoutMs: Long): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        lock.withLock {
            while (inFlight != null) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                changed.await(left, TimeUnit.MILLISECONDS)
            }
            return cancelledWaiting + (if (inFlight != null) 1L else 0L)
        }
    }

    /** Ends the thread and closes the landmarker once the run in flight (if any) has returned. */
    fun release() {
        lock.withLock {
            closed = true
            released = true
            waiting = null
            changed.signalAll()
        }
        thread.join(RELEASE_JOIN_MS)
        if (!thread.isAlive) {
            pipeline.close()
        }
        lock.withLock { buffers = arrayOfNulls(2) }
    }

    private fun freeBuffer(capacity: Int): ByteBuffer {
        val busy = inFlight?.buffer
        for (i in buffers.indices) {
            val b = buffers[i]
            if (b != null && b !== busy && b.capacity() == capacity) return b
        }
        for (i in buffers.indices) {
            val b = buffers[i]
            if (b == null || (b !== busy && b.capacity() != capacity)) {
                val n = ByteBuffer.allocateDirect(capacity)
                buffers[i] = n
                return n
            }
        }
        // both buffers are in use with another size (cannot happen with one in-flight run): allocate a fresh one
        return ByteBuffer.allocateDirect(capacity)
    }

    private fun loop() {
        while (true) {
            val req: Request
            lock.withLock {
                while (waiting == null && !released) changed.await()
                if (waiting == null) return
                req = waiting!!
                waiting = null
                inFlight = req
            }
            try {
                val startNs = SystemClock.elapsedRealtimeNanos()
                val waitMs = ((startNs - req.submittedNs) / 1e6).coerceAtLeast(0.0)
                val frame = RgbaFrame(req.buffer, req.width, req.height, req.rotation, req.captureNs)
                val p = pipeline.process(frame, req.tsMs)
                val s = p.scalars
                val sample = if (p.detected && s != null) {
                    PoseSample(
                        captureMonoNs = req.captureNs, poseInferMs = p.inferMs, detected = true,
                        shoulderVisibilityMin = s.shoulderVisibilityMin,
                        shoulderCenterXPx = s.shoulderCenterXPx, shoulderCenterYPx = s.shoulderCenterYPx, shoulderWidthPx = s.shoulderWidthPx,
                        headLandmarkPresent = s.headLandmarkPresent, headOffsetBelowShoulderRatio = s.headOffsetBelowShoulderRatio,
                        frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx, waitMs = waitMs,
                    )
                } else {
                    PoseSample(captureMonoNs = req.captureNs, poseInferMs = p.inferMs, detected = false, frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx, waitMs = waitMs)
                }
                sink.onPose(sample)
            } catch (t: Throwable) {
                sink.onPoseError(req.captureNs, t)
            } finally {
                lock.withLock {
                    inFlight = null
                    changed.signalAll()
                }
            }
        }
    }

    companion object {
        const val RELEASE_JOIN_MS: Long = 5_000L
    }
}
