package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-second record, schema 0.2.1 (spec 6장 스키마의 v0 부분집합 + CHANGELOG v0.2.1 필드).
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

    /** Frames requested from the camera in this bucket (nominal fps). */
    @SerialName("frames_requested") val framesRequested: Int,
    /** Frames actually run through the face pipeline. */
    @SerialName("frames_processed") val framesProcessed: Int,
    @SerialName("frames_dropped") val framesDropped: Int,
    /** Largest capture-timestamp gap between consecutive processed frames. Null when fewer than two frames. */
    @SerialName("max_frame_gap_ms") val maxFrameGapMs: Long? = null,
    /** Gaps between processed frames longer than 80 ms (v0-plan V0-A 통과 기준). */
    @SerialName("gaps_over_80ms") val gapsOver80Ms: Int,
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
    }

    /** Effective processed frame rate for this bucket. */
    val fpsActual: Double get() = framesProcessed * 1000.0 / FocusSchema.RECORD_PERIOD_MS
}
