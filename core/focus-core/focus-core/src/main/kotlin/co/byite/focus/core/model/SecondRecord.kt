package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-second record, v0 subset of the spec 6장 schema.
 *
 * Every field is a scalar, enum or string: no image, landmark or array data (spec 9장 데이터 경계,
 * enforced by `SchemaBoundaryTest`). The record stamped `t_mono_ms = t` summarises
 * `[t, t + FocusSchema.RECORD_PERIOD_MS)`.
 *
 * [rawState] / [finalState] are null on a fresh feature record; the engine sets `raw_state`
 * and the [co.byite.focus.core.finalizer.StateFinalizer] sets `final_state`.
 */
@Serializable
data class SecondRecord(
    /** Monotonic time. Every duration and transition is computed from this (spec 9장). */
    @SerialName("t_mono_ms") val tMonoMs: Long,
    /** Wall clock, display and cross-session alignment only. */
    @SerialName("t_utc_ms") val tUtcMs: Long,
    /** What the app knew at this second (alerts, power state machine). */
    @SerialName("raw_state") val rawState: State? = null,
    /** State after backdating (ratios, aggregates). */
    @SerialName("final_state") val finalState: State? = null,

    /** Fraction of processed frames with a face detected, 0..1. */
    @SerialName("face_detect_ratio") val faceDetectRatio: Double,
    /** Pose torso detected and matching the calibration torso ROI (spec 3장 G1 상체 검출 조건). */
    @SerialName("torso_match") val torsoMatch: Boolean,
    /** Pose nose/ear landmark valid this second. */
    @SerialName("head_landmark_present") val headLandmarkPresent: Boolean,
    /** head_y > shoulder_line_y + margin (image y grows downward). Null when no head landmark. */
    @SerialName("head_below_shoulder") val headBelowShoulder: Boolean? = null,

    /** Head pose per-second means in degrees. Null when no face. */
    @SerialName("yaw_mean") val yawMean: Double? = null,
    @SerialName("pitch_mean") val pitchMean: Double? = null,
    @SerialName("roll_mean") val rollMean: Double? = null,
    /** Registered work zone id the head pose falls in; null = outside all zones or no head pose. */
    @SerialName("zone_id") val zoneId: Int? = null,

    /** Shoulder-centre displacement over 1 s ÷ shoulder width (spec 3장 저움직임 재료). Null without pose. */
    @SerialName("pose_motion") val poseMotion: Double? = null,
    /** Whole-frame Y-plane mean luma, 0..255 (spec 6장 scene proxy). */
    @SerialName("scene_luma") val sceneLuma: Double,
    /**
     * Fraction of calibration background tiles whose texture fell below 25 % of their baseline
     * (spec 6장 카메라 가림 proxy input). Null when the proxy is unavailable (smooth background).
     */
    @SerialName("bg_tile_texture_ratio") val bgTileTextureRatio: Double? = null,
    /** Rigid-residual jitter j (spec 6장). Null without a face. */
    @SerialName("jitter_j") val jitterJ: Double? = null,
    /** Face width in source pixels. Null without a face. */
    @SerialName("face_width_px") val faceWidthPx: Double? = null,

    @SerialName("imu_state") val imuState: ImuState,
    @SerialName("app_state") val appState: AppState,
    /** Frames actually processed this second (not the requested fps). */
    @SerialName("fps_actual") val fpsActual: Double,
    @SerialName("power_state") val powerState: PowerState,
)
