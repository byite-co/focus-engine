package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-second record, schema 0.2.3 (spec 6장 스키마의 v0 부분집합 + CHANGELOG v0.2.1·v0.2.2·v0.2.3 필드).
 *
 * Every field is a scalar, enum or string, plus the [events] list of scalar objects: no image,
 * landmark or numeric-array data (spec 9장 데이터 경계, enforced by `SchemaBoundaryTest`).
 * The record stamped `t_mono_ms = t` summarises the bucket `[t, t + FocusSchema.RECORD_PERIOD_MS)`.
 *
 * [rawState] / [finalState] are null on a fresh feature record; the engine sets `raw_state`,
 * `invalid_reason`, `candidate_*` and gate events, the [co.byite.focus.core.finalizer.StateFinalizer]
 * sets `final_state`.
 */
@Serializable
data class SecondRecord(
    /** Monotonic bucket start. Every duration and transition is computed from this (spec 9장). */
    @SerialName("t_mono_ms") val tMonoMs: Long,
    /** Wall clock, display and cross-session alignment only. */
    @SerialName("t_utc_ms") val tUtcMs: Long,
    /** What the app knew at this second (alerts, power state machine). */
    @SerialName("raw_state") val rawState: State? = null,
    /** State after backdating (ratios, aggregates). */
    @SerialName("final_state") val finalState: State? = null,
    /** Why `raw_state` is INVALID; null otherwise. */
    @SerialName("invalid_reason") val invalidReason: InvalidReason? = null,
    /** Candidate being accumulated (ABSENT, PRONE, PHONE pickup, AWAY grace, PAUSED counter) and its first bucket. */
    @SerialName("candidate_state") val candidateState: State? = null,
    @SerialName("candidate_start_mono_ms") val candidateStartMonoMs: Long? = null,
    /** Gate and device events that fell into this bucket. */
    val events: List<Event> = emptyList(),

    /** Fraction of processed frames with a face detected, 0..1 (bands: `face_missing_max_ratio`, `face_present_min_ratio`). */
    @SerialName("face_detect_ratio") val faceDetectRatio: Double,
    /** min(left, right) shoulder visibility. Null without a Pose result. */
    @SerialName("shoulder_visibility_min") val shoulderVisibilityMin: Double? = null,
    /** |shoulder centre − calibration torso centre| ÷ calibration shoulder width. Null without Pose. */
    @SerialName("torso_center_offset_ratio") val torsoCenterOffsetRatio: Double? = null,
    /** shoulder width ÷ calibration shoulder width. Null without Pose. */
    @SerialName("torso_width_ratio") val torsoWidthRatio: Double? = null,
    /** Pose nose/ear landmark valid this second. */
    @SerialName("head_landmark_present") val headLandmarkPresent: Boolean,
    /** (head_y − shoulder_line_y) ÷ shoulder width; positive = below (image y grows downward). Null without a head landmark. */
    @SerialName("head_offset_below_shoulder_ratio") val headOffsetBelowShoulderRatio: Double? = null,

    /** Head pose per-second means in degrees. Null when no face. */
    @SerialName("yaw_mean") val yawMean: Double? = null,
    @SerialName("pitch_mean") val pitchMean: Double? = null,
    @SerialName("roll_mean") val rollMean: Double? = null,
    @SerialName("zone_status") val zoneStatus: ZoneStatus,
    /** Registered work zone id; set exactly when `zone_status == in_zone`. */
    @SerialName("zone_id") val zoneId: Int? = null,

    /** Shoulder-centre displacement over 1 s ÷ shoulder width (spec 3장 저움직임 재료). Null without pose. */
    @SerialName("pose_motion") val poseMotion: Double? = null,
    /** Whole-frame Y-plane mean luma, 0..255 (spec 6장 scene proxy). */
    @SerialName("scene_luma") val sceneLuma: Double,
    /** Fraction of calibration background tiles whose texture fell below 25 % of baseline. Null when the proxy is unavailable. */
    @SerialName("bg_tile_texture_ratio") val bgTileTextureRatio: Double? = null,
    /** Rigid-residual jitter j (spec 6장). Null without a face. */
    @SerialName("jitter_j") val jitterJ: Double? = null,
    /** Face width in source pixels. Null without a face. */
    @SerialName("face_width_px") val faceWidthPx: Double? = null,

    @SerialName("imu_state") val imuState: ImuState,
    @SerialName("screen_state") val screenState: ScreenState,
    @SerialName("app_state") val appState: AppState,

    /** Frames the camera produced in this bucket (Camera2 capture results, by capture timestamp). */
    @SerialName("frames_requested") val framesRequested: Int,
    /** Frames on which Face inference actually ran (fixed right after the inference succeeded; schema 0.2.3, CHANGELOG v0.2.3). */
    @SerialName("frames_processed") val framesProcessed: Int,
    /**
     * Frames the ImageAnalysis callback received (schema 0.2.3). Logs older than 0.2.3 have no such field;
     * they decode as `frames_processed` so that every drop they recorded stays a backpressure drop.
     */
    @SerialName("frames_analyzer_received") val framesAnalyzerReceived: Int = framesProcessed,
    /** Received frames skipped on purpose (every-other-frame presets, power-state skips; schema 0.2.3). 0 in older logs. */
    @SerialName("frames_skipped_intentional") val framesSkippedIntentional: Int = 0,
    /** Processed frames whose [co.byite.focus.core.aggregate.FrameSample] was applied to this bucket (schema 0.2.3). Older logs: `frames_processed`. */
    @SerialName("frames_sample_applied") val framesSampleApplied: Int = framesProcessed,
    /** Samples that reached the aggregation queue after their bucket had closed and were discarded; counted in the oldest open bucket (schema 0.2.3). */
    @SerialName("frames_sample_late_dropped") val framesSampleLateDropped: Int = 0,
    /**
     * Frames meant to be processed that yielded no applied sample (schema 0.2.3, CHANGELOG v0.2.3):
     * `backpressureDrops + framesUnprocessedUnexpected + framesPostFaceFailed + framesSampleLateDropped`
     * (equals `max(0, requested − processed)` in older logs).
     */
    @SerialName("frames_dropped") val framesDropped: Int,
    /** Largest capture-timestamp gap between consecutive processed frames. Null when fewer than two frames. */
    @SerialName("max_frame_gap_ms") val maxFrameGapMs: Long? = null,
    /** Gaps between processed frames longer than 80 ms (v0-plan V0-A 통과 기준), whatever the preset. */
    @SerialName("gaps_over_80ms") val gapsOver80Ms: Int,
    /**
     * Gaps between processed frames longer than the session's `frame_gap_threshold_ms` (header; 80 ms for
     * every-frame presets, 2 × expected interval otherwise). Older logs decode as [gapsOver80Ms].
     */
    @SerialName("gaps_over_threshold") val gapsOverThreshold: Int = gapsOver80Ms,
    /**
     * Gaps longer than the session's `frame_long_gap_threshold_ms` (header; 200 ms for every-frame presets, 5 × expected
     * interval otherwise). The pass mark requires 0 (원래 지시문 D 1번). Older logs decode as 0 (unknown).
     */
    @SerialName("gaps_over_long_threshold") val gapsOverLongThreshold: Int = 0,
    @SerialName("power_state") val powerState: PowerState,
) {
    init {
        rawState?.let { raw ->
            require((raw == State.INVALID) == (invalidReason != null)) {
                "invalid_reason must be set exactly when raw_state == INVALID (t=$tMonoMs, raw=$raw, reason=$invalidReason)"
            }
        }
        require((zoneId != null) == (zoneStatus == ZoneStatus.IN_ZONE)) { "zone_id is set exactly when zone_status == in_zone (t=$tMonoMs)" }
        require((candidateState == null) == (candidateStartMonoMs == null)) { "candidate_state and candidate_start_mono_ms go together (t=$tMonoMs)" }
        require(headLandmarkPresent || headOffsetBelowShoulderRatio == null) { "head_offset_below_shoulder_ratio needs a head landmark (t=$tMonoMs)" }
        require(framesRequested >= 0 && framesProcessed >= 0 && framesDropped >= 0 && gapsOver80Ms >= 0) { "frame counters must not be negative (t=$tMonoMs)" }
        require(framesAnalyzerReceived >= 0 && framesSkippedIntentional >= 0 && framesSampleApplied >= 0 && framesSampleLateDropped >= 0 && gapsOverThreshold >= 0 && gapsOverLongThreshold >= 0) {
            "frame counters must not be negative (t=$tMonoMs)"
        }
        // The per-second parts below are clamped at 0: a counter that reaches the aggregator after its bucket closed is
        // attributed to the oldest open bucket, so a single bucket may see a processed frame without its received one.
        // The session-wide relations are checked on CounterTotals instead.
        require(framesDropped == backpressureDrops + framesUnprocessedUnexpected + framesPostFaceFailed + framesSampleLateDropped) {
            "frames_dropped must be backpressure ($backpressureDrops) + unprocessed unexpected ($framesUnprocessedUnexpected) + post-face failed ($framesPostFaceFailed) + late-dropped samples ($framesSampleLateDropped), got $framesDropped (t=$tMonoMs)"
        }
        if (events.any { it.type == EventType.REDOCK_CONFIRMED && it.by == RedockBy.TAP }) {
            require(events.any { it.type == EventType.USER_REDOCK_TAP }) { "redock_confirmed{by: tap} needs a user_redock_tap input event in the same bucket (t=$tMonoMs)" }
        }
    }

    /** Events the user or device produced (replay input). */
    val inputEvents: List<Event> get() = events.filter { it.isInput }

    /** Events the gates produced (recomputed on replay). */
    val outputEvents: List<Event> get() = events.filter { it.isOutput }

    /** Effective processed frame rate for this bucket. */
    val fpsActual: Double get() = framesProcessed * 1000.0 / FocusSchema.RECORD_PERIOD_MS

    /** Frames the camera produced but the analyzer never received (KEEP_ONLY_LATEST backpressure); clamped at 0 per bucket. */
    val backpressureDrops: Int get() = (framesRequested - framesAnalyzerReceived).coerceAtLeast(0)

    /** Received frames that were neither skipped on purpose nor processed (Face inference errors, other pre-Face errors, non-monotonic rejects); clamped at 0 per bucket. */
    val framesUnprocessedUnexpected: Int get() = (framesAnalyzerReceived - framesSkippedIntentional - framesProcessed).coerceAtLeast(0)

    /** Samples that reached the aggregation queue: applied + late-dropped (schema 0.2.3 `frames_sample_enqueued`, derived). */
    val framesSampleEnqueued: Int get() = framesSampleApplied + framesSampleLateDropped

    /** Processed frames whose sample never reached the aggregation queue (post-processing / scene / pose-copy errors after a successful Face run); clamped at 0 per bucket. */
    val framesPostFaceFailed: Int get() = (framesProcessed - framesSampleEnqueued).coerceAtLeast(0)

    /** Denominator of the drop ratio (CHANGELOG v0.2.3): frames that were meant to be processed. */
    val framesTargeted: Int get() = framesRequested - framesSkippedIntentional
}
