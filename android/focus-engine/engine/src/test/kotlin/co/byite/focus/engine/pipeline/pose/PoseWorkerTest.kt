package co.byite.focus.engine.pipeline.pose

import co.byite.focus.core.aggregate.CameraStamp
import co.byite.focus.core.aggregate.PoseSample
import co.byite.focus.engine.pipeline.RgbaFrame
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real [PoseWorker] thread with a fake inference (code review item 2): a run that outlives the stop bound is
 * cancelled — its result is never delivered, the landmarker is closed by the worker after the run returns, and
 * [PoseWorker.release] does not block on it.
 */
class PoseWorkerTest {
    private class FakeInference(private val block: CountDownLatch? = null) : PoseInference {
        val closed = AtomicInteger()
        @Volatile var closedOnThread: String? = null
        val runs = AtomicInteger()
        override fun process(frame: RgbaFrame, timestampMs: Long): PoseFeatures {
            runs.incrementAndGet()
            block?.await(5, TimeUnit.SECONDS)
            return PoseFeatures(detected = false, scalars = null, inferMs = 1.0, frameWidthPx = frame.height, frameHeightPx = frame.width)
        }
        override fun close() {
            closed.incrementAndGet()
            closedOnThread = Thread.currentThread().name
        }
    }

    private class RecordingSink : PoseWorker.Sink {
        val samples = ArrayList<PoseSample>()
        val errors = ArrayList<Long>()
        val superseded = ArrayList<Long>()
        val delivered = CountDownLatch(1)
        @Synchronized override fun onPose(sample: PoseSample) { samples.add(sample); delivered.countDown() }
        @Synchronized override fun onPoseError(stamp: CameraStamp, error: Throwable) { errors.add(stamp.captureMonoNs) }
        @Synchronized override fun onPoseSuperseded(stamp: CameraStamp) { superseded.add(stamp.captureMonoNs) }
    }

    /** Raw timestamp = mono − 7: the sample must carry the raw identity, not the mono position. */
    private fun frame(captureNs: Long, w: Int = 8, h: Int = 4): RgbaFrame {
        val buf = ByteBuffer.allocateDirect(w * 4 * h)
        for (i in 0 until buf.capacity()) buf.put((captureNs and 0xFF).toByte())
        buf.rewind()
        return RgbaFrame(buf, w, h, 90, captureNs, rawSensorTs = captureNs - 7)
    }

    @Test
    fun aRunThatFinishesInTimeIsDeliveredWithItsCaptureTimestamp() {
        val inf = FakeInference()
        val sink = RecordingSink()
        val w = PoseWorker(inf, sink, nowNs = System::nanoTime)
        w.start()
        val sub = assertNotNull(w.submit(frame(1_000L), 1L))
        assertNull(sub.superseded)
        assertTrue(sink.delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1_000L, sink.samples.single().captureMonoNs)
        assertEquals(993L, sink.samples.single().rawSensorTs, "raw identity travels with the sample")
        assertEquals(8, sink.samples.single().frameHeightPx, "upright size of a 90° buffer")
        w.closeSlot()
        assertEquals(0L, w.awaitIdle(500))
        w.release()
        assertEquals(1, inf.closed.get(), "closed at release when nothing is in flight")
    }

    @Test
    fun aWaitingRequestIsSupersededByTheNextSubmit() {
        val gate = CountDownLatch(1)
        val inf = FakeInference(gate)
        val sink = RecordingSink()
        val w = PoseWorker(inf, sink, nowNs = System::nanoTime)
        w.start()
        w.submit(frame(1L), 1L) // taken by the worker, blocked in inference
        Thread.sleep(50)
        assertNull(w.submit(frame(2L), 2L)!!.superseded)
        val third = assertNotNull(w.submit(frame(3L), 3L))
        assertEquals(CameraStamp(2L - 7, 2L), third.superseded, "the waiting request (2) is replaced by 3, reported with its raw + mono stamp")
        assertEquals(3L, w.submitted)
        gate.countDown()
        assertTrue(sink.delivered.await(2, TimeUnit.SECONDS))
        w.closeSlot()
        assertEquals(0L, w.awaitIdle(1_000))
        w.release()
        assertEquals(listOf(1L, 3L), sink.samples.map { it.captureMonoNs }.sorted())
    }

    @Test
    fun aRunThatOutlivesTheStopBoundIsCancelledNotDeliveredAndClosedByTheWorker() {
        val gate = CountDownLatch(1)
        val inf = FakeInference(gate)
        val sink = RecordingSink()
        val w = PoseWorker(inf, sink, nowNs = System::nanoTime)
        w.start()
        w.submit(frame(10L), 10L) // in flight, blocked
        Thread.sleep(50)
        w.submit(frame(20L), 20L) // waiting in the slot
        w.closeSlot() // step 2: the waiting request is discarded
        assertNull(w.submit(frame(30L), 30L), "the slot is closed")
        val t0 = System.nanoTime()
        assertEquals(2L, w.awaitIdle(100), "waiting request + the run that did not finish in time")
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "the wait is bounded")
        val t1 = System.nanoTime()
        w.release()
        assertTrue(System.nanoTime() - t1 < 1_000_000_000L, "release never blocks on the run in flight")
        assertEquals(0, inf.closed.get(), "the landmarker is not closed while inference runs")
        gate.countDown() // the late run returns now
        assertFalse(sink.delivered.await(300, TimeUnit.MILLISECONDS), "a cancelled run posts nothing")
        Thread.sleep(50)
        assertEquals(1L, w.suppressedResults)
        assertEquals(1, inf.closed.get(), "closed exactly once, by the worker, after the run returned")
        assertEquals("focus-pose", inf.closedOnThread)
        assertTrue(sink.samples.isEmpty() && sink.errors.isEmpty())
    }
}
