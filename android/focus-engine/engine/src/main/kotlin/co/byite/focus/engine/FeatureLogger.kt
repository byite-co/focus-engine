package co.byite.focus.engine

import co.byite.focus.core.aggregate.AggregatedSecond
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TimebaseRecord
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Session JSONL writer (v0-plan 2장 FeatureLogger). Accepts focus-core log models only — this is the
 * data boundary of spec 9장: no frame, bitmap or landmark type can reach this class (DataBoundaryTest).
 *
 * Header and timebase lines are written at once. Closed per-second records are buffered and written in
 * batches of [flushEvery] records (30 = every 30 s at 1 Hz, spec 7장 "30–60초 배치"); the end marker
 * flushes everything. JSON encoding and file IO both run on [io] (directive D 정정 3: no serialisation on
 * the analysis or aggregation thread); the producer methods are meant for one thread (the aggregation thread).
 */
class FeatureLogger(
    val file: File,
    private val io: Executor,
    private val flushEvery: Int = DEFAULT_FLUSH_EVERY,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val pending = ArrayList<() -> String>()
    private var sinceFlush = 0
    private var writer: BufferedWriter? = null

    /** Records handed to [append] so far (for the live summary). */
    var appended: Long = 0L
        private set

    fun writeHeader(header: SessionHeader) = enqueue({ JsonlCodec.encodeHeader(header) }, flushNow = true)

    fun writeTimebase(record: TimebaseRecord) = enqueue({ JsonlCodec.encodeTimebase(record) }, flushNow = true)

    fun append(second: AggregatedSecond) {
        pending.add { JsonlCodec.encodeSecond(second.second) }
        pending.add { JsonlCodec.encodeV0bRaw(second.raw) }
        appended++
        if (++sinceFlush >= flushEvery) flush()
    }

    fun writeSessionEnd(end: SessionEnd) = enqueue({ JsonlCodec.encodeSessionEnd(end) }, flushNow = true)

    /** Hand the buffered records to the IO executor, which encodes and writes them and flushes the file. */
    fun flush() {
        if (pending.isEmpty()) return
        val lines = ArrayList(pending)
        pending.clear()
        sinceFlush = 0
        io.execute {
            try {
                val w = writer ?: openWriter().also { writer = it }
                for (l in lines) {
                    w.write(l())
                    w.newLine()
                }
                w.flush()
            } catch (e: Exception) {
                onError("write ${file.name}", e)
            }
        }
    }

    /** Block until every queued write has run (or [timeoutMs] passed). */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        io.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        flush()
        io.execute {
            try {
                writer?.close()
            } catch (e: Exception) {
                onError("close ${file.name}", e)
            } finally {
                writer = null
            }
        }
    }

    private fun enqueue(line: () -> String, flushNow: Boolean) {
        pending.add(line)
        if (flushNow) flush()
    }

    private fun openWriter(): BufferedWriter {
        file.parentFile?.mkdirs()
        return BufferedWriter(FileWriter(file, true))
    }

    companion object {
        const val DEFAULT_FLUSH_EVERY = 30
    }
}
