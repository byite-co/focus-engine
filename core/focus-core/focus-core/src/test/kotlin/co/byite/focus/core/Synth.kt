package co.byite.focus.core

import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.CalibrationSnapshot
import co.byite.focus.core.model.CalibrationZone
import co.byite.focus.core.model.Event
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.InvalidReason
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.PowerState
import co.byite.focus.core.model.ScreenState
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.State
import co.byite.focus.core.model.TaskMode
import co.byite.focus.core.model.TimebaseRecord
import co.byite.focus.core.model.ZoneStatus
import co.byite.focus.core.replay.ReplayResult

/** Synthetic log builders shared by the tests. Times are 1 Hz buckets relative to [T0]. */
object Synth {
    const val T0: Long = 1_000_000L
    const val UTC0: Long = 1_800_000_000_000L
    const val SEC: Long = 1_000L

    val params: ParameterSet = ParameterSet.DEFAULT

    val header: SessionHeader = SessionHeader(
        sessionId = "S-synth",
        participantId = "P-synth",
        tStartMonoMs = T0,
        tStartUtcMs = UTC0,
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
        reason: InvalidReason? = null,
        candidate: State? = null,
        candidateStartSec: Long? = null,
        events: List<Event> = emptyList(),
        imuState: ImuState = ImuState.DOCKED,
        screenState: ScreenState = ScreenState.OFF,
        appState: AppState = AppState.BACKGROUND,
        faceRatio: Double? = null,
    ): SecondRecord = SecondRecord(
        tMonoMs = mono(sec),
        tUtcMs = utc(sec),
        rawState = raw,
        finalState = final,
        invalidReason = reason,
        candidateState = candidate,
        candidateStartMonoMs = candidateStartSec?.let { mono(it) },
        events = events,
        faceDetectRatio = faceRatio ?: if (face) 1.0 else 0.0,
        shoulderVisibilityMin = if (face) 0.9 else null,
        torsoCenterOffsetRatio = if (face) 0.05 else null,
        torsoWidthRatio = if (face) 1.0 else null,
        headLandmarkPresent = face,
        headOffsetBelowShoulderRatio = if (face) -0.8 else null,
        yawMean = if (face) 0.0 else null,
        pitchMean = if (face) -5.0 else null,
        rollMean = if (face) 0.0 else null,
        zoneStatus = if (face) ZoneStatus.IN_ZONE else ZoneStatus.NO_HEAD_POSE,
        zoneId = if (face) 0 else null,
        poseMotion = if (face) 0.1 else null,
        sceneLuma = 120.0,
        bgTileTextureRatio = 0.0,
        jitterJ = if (face) 0.5 else null,
        faceWidthPx = if (face) 200.0 else null,
        imuState = imuState,
        screenState = screenState,
        appState = appState,
        framesRequested = 24,
        framesProcessed = 24,
        framesDropped = 0,
        maxFrameGapMs = 42,
        gapsOver80Ms = 0,
        powerState = if (face) PowerState.P0 else PowerState.P1_PRIME,
    )

    /** Segments of (seconds, facePresent), concatenated from second 0. */
    fun faceSegments(vararg segments: Pair<Int, Boolean>): List<SecondRecord> {
        val out = ArrayList<SecondRecord>()
        var sec = 0L
        for ((len, face) in segments) repeat(len) { out.add(record(sec++, face)) }
        return out
    }

    /** Records with raw/final states given per second, starting at [startSec]. INVALID gets `face_missing_unconfirmed`. */
    fun stated(startSec: Long, vararg states: State, raw: List<State>? = null): List<SecondRecord> =
        states.mapIndexed { i, s ->
            val r = raw?.get(i) ?: s
            record(startSec + i, face = s == State.PRESENT, raw = r, final = s, reason = if (r == State.INVALID) InvalidReason.FACE_MISSING_UNCONFIRMED else null)
        }

    fun calibration(sec: Long, id: String = "C-synth"): CalibrationSnapshot = CalibrationSnapshot(
        calibrationId = id,
        version = "1",
        tMonoMs = mono(sec),
        zones = listOf(CalibrationZone(0, 0.0, -8.0, 12.0, 12.0), CalibrationZone(1, 25.0, -12.0, 12.0, 12.0)),
        torsoCenterX = 0.5,
        torsoCenterY = 0.7,
        torsoWidth = 0.4,
        m0Pose = 0.05,
        poseJitterFloor = 0.01,
        m0 = 0.02,
        jitterFloor = 0.005,
        jitterJBaseline = 0.4,
        dockGravityVector = listOf(0.1, 7.2, 6.6),
        dockAccelVariance = 0.002,
        sceneLumaBaseline = 118.0,
        bgTileTextureBaseline = List(16) { 12.0 + it },
        bgTileMask = List(16) { it < 8 },
    )

    fun timebase(sec: Long): TimebaseRecord = TimebaseRecord(mono(sec), "REALTIME", 1_500_000, -2_000)

    fun log(
        records: List<SecondRecord>,
        intervals: List<IntervalRecord> = emptyList(),
        end: SessionEnd? = null,
        calibrations: List<CalibrationSnapshot> = emptyList(),
        timebase: List<TimebaseRecord> = emptyList(),
    ): SessionLog = SessionLog(header, records, intervals, end, calibrations, timebase)

    fun replayResult(records: List<SecondRecord>, engineId: String = "test", intervals: List<IntervalRecord> = emptyList(), end: SessionEnd? = null): ReplayResult =
        ReplayResult(header, engineId, params.parameterSetId, records, intervals, end, 0, emptyList())
}
