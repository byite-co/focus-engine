package co.byite.focus.engine

import co.byite.focus.core.aggregate.AggregatedSecond
import co.byite.focus.core.aggregate.DeviceSample
import co.byite.focus.core.aggregate.FeatureAggregator
import co.byite.focus.core.aggregate.FrameSample
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.ScreenState
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TaskMode
import co.byite.focus.core.model.TimebaseRecord
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureLoggerTest {
    private val direct = Executor { it.run() }
    private val header = SessionHeader(
        sessionId = "S", participantId = "dev", tStartMonoMs = 5_000_000L, tStartUtcMs = 1_800_000_000_000L,
        algorithmVersion = "abc", parameterSetId = ParameterSet.DEFAULT.parameterSetId, deviceModel = "test", osVersion = "16",
        cameraResolution = "1280x720", nominalFps = 24, calibrationId = "", calibrationSnapshotVersion = "", taskMode = TaskMode.VISUAL,
    )
    private val device = DeviceSample(thermalStatus = 0, isInteractive = false, isDeviceIdle = false, screenState = ScreenState.OFF, appState = AppState.BACKGROUND)

    private fun seconds(n: Int): List<AggregatedSecond> {
        val a = FeatureAggregator(header.tStartMonoMs, header.tStartUtcMs)
        for (i in 0 until n) a.onFrame(FrameSample((header.tStartMonoMs + i * 1000L + 10) * 1_000_000L, 1_000_000L, true, 1.0, -5.0, 0.0, 200.0, null, 12.0))
        return a.closeBuckets(header.tStartMonoMs + n * 1000L + 300, device)
    }

    private fun tempFile(): File = File(Files.createTempDirectory("focus-logger").toFile(), "session.jsonl")

    @Test
    fun writesADecodableSessionAndFlushesEveryThirtyRecords() {
        val file = tempFile()
        val logger = FeatureLogger(file, direct, flushEvery = 30)
        logger.writeHeader(header)
        logger.writeTimebase(TimebaseRecord(header.tStartMonoMs, "REALTIME", 0, 0))
        val secs = seconds(31)
        for (s in secs.take(29)) logger.append(s)
        val afterTwentyNine = file.readText().lines().filter { it.isNotBlank() }.size
        assertEquals(2, afterTwentyNine, "records are held back until the 30th")
        logger.append(secs[29])
        assertEquals(2 + 60, file.readText().lines().filter { it.isNotBlank() }.size)
        logger.append(secs[30])
        val end = SessionEnd(header.tStartMonoMs + 31_000, header.tStartUtcMs + 31_000, SessionEndReason.USER)
        logger.writeSessionEnd(end)
        logger.close()
        assertTrue(logger.awaitIdle(1000))
        val log = JsonlCodec.decode(file.readText())
        assertEquals(header, log.header)
        assertEquals(31, log.records.size)
        assertEquals(31, log.v0bRaw.size)
        assertEquals(end, log.sessionEnd)
        assertEquals(1, log.timebase.size)
        assertEquals(secs.map { it.second }, log.records)
        assertEquals(secs.map { it.raw }, log.v0bRaw)
    }

    @Test
    fun recoveryTakesATornLastLineAndClosesTheSession() {
        val file = tempFile()
        val logger = FeatureLogger(file, direct, flushEvery = 1)
        logger.writeHeader(header)
        seconds(3).forEach { logger.append(it) }
        logger.close()
        file.appendText("{\"type\":\"second\",\"t_mono_ms\":50030")
        val (log, dropped) = SessionRecovery.readTolerant(file.readText())
        assertTrue(dropped)
        assertEquals(3, log.records.size)
        assertEquals(header.tStartMonoMs + 2 * FocusSchema.RECORD_PERIOD_MS, log.records.last().tMonoMs)
    }
}
