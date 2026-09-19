package co.byite.focus.core

import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.PowerState
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.State
import co.byite.focus.core.model.TaskMode
import co.byite.focus.core.replay.ReplayResult

/** Synthetic log builders shared by the tests. Times are 1 Hz, relative to [T0]. */
object Synth {
    const val T0: Long = 1_000_000L
    const val UTC0: Long = 1_800_000_000_000L
    const val SEC: Long = 1_000L

    val params: ParameterSet = ParameterSet.DEFAULT

    val header: SessionHeader = SessionHeader(
        sessionId = "S-synth",
        participantId = "P-synth",
        algorithmVersion = "test",
        parameterSetId = params.parameterSetId,
        deviceModel = "synthetic",
        osVersion = "0",
        cameraResolution = "640x480",
        nominalFps = 24,
        calibrationId = "C-synth",
        calibrationSnapshotVersion = "1",
        taskMode = TaskMode.VISUAL,
    )

    fun mono(sec: Long): Long = T0 + sec * SEC
    fun utc(sec: Long): Long = UTC0 + sec * SEC

    fun record(
        sec: Long,
        face: Boolean = true,
        raw: State? = null,
        final: State? = null,
        appState: AppState = AppState.SCREEN_OFF,
        imuState: ImuState = ImuState.DOCKED,
    ): SecondRecord = SecondRecord(
        tMonoMs = mono(sec),
        tUtcMs = utc(sec),
        rawState = raw,
        finalState = final,
        faceDetectRatio = if (face) 1.0 else 0.0,
        torsoMatch = face,
        headLandmarkPresent = face,
        headBelowShoulder = if (face) false else null,
        yawMean = if (face) 0.0 else null,
        pitchMean = if (face) -5.0 else null,
        rollMean = if (face) 0.0 else null,
        zoneId = if (face) 0 else null,
        poseMotion = if (face) 0.1 else null,
        sceneLuma = 120.0,
        bgTileTextureRatio = 0.0,
        jitterJ = if (face) 0.5 else null,
        faceWidthPx = if (face) 200.0 else null,
        imuState = imuState,
        appState = appState,
        fpsActual = 24.0,
        powerState = PowerState.P0,
    )

    /** Segments of (seconds, facePresent), concatenated from second 0. */
    fun faceSegments(vararg segments: Pair<Int, Boolean>): List<SecondRecord> {
        val out = ArrayList<SecondRecord>()
        var sec = 0L
        for ((len, face) in segments) repeat(len) { out.add(record(sec++, face)) }
        return out
    }

    /** Records with raw/final states given per second, starting at [startSec]. */
    fun stated(startSec: Long, vararg states: State, raw: List<State>? = null): List<SecondRecord> =
        states.mapIndexed { i, s -> record(startSec + i, face = s == State.PRESENT, raw = raw?.get(i) ?: s, final = s) }

    fun log(
        records: List<SecondRecord>,
        intervals: List<IntervalRecord> = emptyList(),
        end: SessionEnd? = null,
    ): SessionLog = SessionLog(header, records, intervals, end)

    fun replayResult(records: List<SecondRecord>, engineId: String = "test", intervals: List<IntervalRecord> = emptyList(), end: SessionEnd? = null): ReplayResult =
        ReplayResult(header, engineId, params.parameterSetId, records, intervals, end, 0, emptyList())
}
