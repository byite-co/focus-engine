package co.byite.focus.engine.pipeline.pose

import android.os.SystemClock
import co.byite.focus.core.aggregate.CameraStamp
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.core.aggregate.WorkGeneration
import co.byite.focus.engine.pipeline.RgbaFrame
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One Pose run as the worker sees it; [PosePipeline] is the real one, tests use a fake. */
interface PoseInference {
    fun process(frame: RgbaFrame, timestampMs: Long): PoseFeatures
    fun close()
}

/**
 * Asynchronous Pose worker (directive D 정정 2·3·5): its own thread `focus-pose`, a waiting slot of depth 1
 * and one run in flight. [submit] deep-copies the frame on the caller's (analysis) thread, keeping the capture
 * timestamp; a request still waiting when a newer one arrives is superseded. Results leave as [PoseSample]
 * scalars through [Sink] (called on the worker thread; the service posts them to the aggregation queue).
 * The two pixel buffers never leave this class and are freed by [release].
 *
 * Stop contract (code review of PR #7, item 2): [closeSlot] discards the waiting request and refuses new ones;
 * [awaitIdle] waits for the run in flight up to a bound and, on a timeout, bumps the run's [WorkGeneration] so
 * its result is neither posted nor counted — that run is exactly what `pose_cancelled_at_stop` counts.
 * [release] never blocks on a run in flight: the worker closes the landmarker itself once the run returns.
 */
class PoseWorker(
    private val inference: PoseInference,
    private val sink: Sink,
    /** Monotonic clock in ns; `SystemClock.elapsedRealtimeNanos` on Android, `System.nanoTime` in JVM tests. */
    private val nowNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    interface Sink {
        fun onPose(sample: PoseSample)
        fun onPoseError(stamp: CameraStamp, error: Throwable)
        fun onPoseSuperseded(stamp: CameraStamp)
    }

    /** Result of one [submit]: copy time and, when a waiting request was replaced, that request's stamp. */
    data class Submitted(val copyMs: Double, val superseded: CameraStamp?)

    private class Request(val buffer: ByteBuffer, val width: Int, val height: Int, val rotation: Int, val captureNs: Long, val rawTs: Long, val tsMs: Long, val submittedNs: Long, val generation: Long) {
        val stamp: CameraStamp get() = CameraStamp(rawTs, captureNs)
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val generation = WorkGeneration()
    private var buffers: Array<ByteBuffer?> = arrayOfNulls(2)
    private var waiting: Request? = null
    private var inFlight: Request? = null
    private var closed = false
    private var released = false
    private var pipelineClosed = false
    private var cancelledWaiting = 0L
    private val thread = Thread({ loop() }, "focus-pose")

    /** Requests handed in so far (analysis thread). */
    var submitted: Long = 0L
        private set

    /** Results dropped because their run had been cancelled at stop (event log). */
    @Volatile var suppressedResults: Long = 0L
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
        val t0 = nowNs()
        lock.withLock {
            if (closed) return null
            val superseded = waiting?.stamp
            val target = waiting?.buffer ?: freeBuffer(frame.width * 4 * frame.height)
            val src = frame.pixels
            src.rewind()
            target.clear()
            target.put(src)
            target.rewind()
            src.rewind()
            waiting = Request(target, frame.width, frame.height, frame.rotationDegrees, frame.captureMonoNs, frame.rawSensorTs, tsMs, t0, generation.current)
            submitted++
            changed.signalAll()
            val copyMs = (nowNs() - t0) / 1e6
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
     * Stop step 3: wait up to [timeoutMs] for the run in flight. On a timeout the run's generation is bumped so its
     * result is dropped when it returns. Returns the requests counted as `pose_cancelled_at_stop`: the discarded
     * waiting request plus the run that did not finish in time.
     */
    fun awaitIdle(timeoutMs: Long): Long {
        val deadlineNs = nowNs() + timeoutMs * 1_000_000L
        lock.withLock {
            while (inFlight != null) {
                val leftNs = deadlineNs - nowNs()
                if (leftNs <= 0) break
                changed.await(leftNs, TimeUnit.NANOSECONDS)
            }
            var cancelled = cancelledWaiting
            if (inFlight != null) {
                generation.bump()
                cancelled++
            }
            return cancelled
        }
    }

    /**
     * Ends the thread and closes the landmarker. Never blocks on a run in flight: when one is still running, the
     * worker closes the landmarker itself right after that run returns (no close during inference).
     */
    fun release() {
        val closeNow: Boolean
        lock.withLock {
            closed = true
            released = true
            waiting = null
            closeNow = inFlight == null && !pipelineClosed
            if (closeNow) pipelineClosed = true
            changed.signalAll()
        }
        if (closeNow) inference.close()
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
            var sample: PoseSample? = null
            var error: Throwable? = null
            try {
                val startNs = nowNs()
                val waitMs = ((startNs - req.submittedNs) / 1e6).coerceAtLeast(0.0)
                val frame = RgbaFrame(req.buffer, req.width, req.height, req.rotation, req.captureNs, req.rawTs)
                val p = inference.process(frame, req.tsMs)
                val s = p.scalars
                sample = if (p.detected && s != null) {
                    PoseSample(
                        captureMonoNs = req.captureNs, poseInferMs = p.inferMs, detected = true,
                        shoulderVisibilityMin = s.shoulderVisibilityMin,
                        shoulderCenterXPx = s.shoulderCenterXPx, shoulderCenterYPx = s.shoulderCenterYPx, shoulderWidthPx = s.shoulderWidthPx,
                        headLandmarkPresent = s.headLandmarkPresent, headOffsetBelowShoulderRatio = s.headOffsetBelowShoulderRatio,
                        frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx, waitMs = waitMs, rawSensorTs = req.rawTs,
                    )
                } else {
                    PoseSample(captureMonoNs = req.captureNs, poseInferMs = p.inferMs, detected = false, frameWidthPx = p.frameWidthPx, frameHeightPx = p.frameHeightPx, waitMs = waitMs, rawSensorTs = req.rawTs)
                }
            } catch (t: Throwable) {
                error = t
            }
            // The gate: a run whose generation was bumped by awaitIdle posts nothing (it is pose_cancelled_at_stop).
            val current: Boolean
            val closeAfter: Boolean
            lock.withLock {
                current = generation.isCurrent(req.generation)
                inFlight = null
                closeAfter = released && !pipelineClosed
                if (closeAfter) pipelineClosed = true
                changed.signalAll()
            }
            if (current) {
                if (error != null) sink.onPoseError(req.stamp, error) else sample?.let { sink.onPose(it) }
            } else {
                suppressedResults++
            }
            if (closeAfter) {
                try {
                    inference.close()
                } catch (_: Throwable) {
                    // best effort on the way out
                }
            }
        }
    }
}
