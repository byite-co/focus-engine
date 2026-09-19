package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every threshold the v0 logic layer reads, bundled under one immutable [parameterSetId]
 * (spec 9장 header `parameter_set_id`). Defaults are the spec v0.2.0 initial values.
 * Weights for W1–W5 are out of v0 scope and intentionally absent.
 */
@Serializable
data class ParameterSet(
    @SerialName("parameter_set_id") val parameterSetId: String,

    // ---- StateFinalizer (spec 9장 소급, lifecycle gap)
    /** Records are finalised this long after their timestamp. */
    @SerialName("finalize_delay_ms") val finalizeDelayMs: Long = 30_000,
    /** Backdating never reaches further back than this from the confirming second. */
    @SerialName("max_backdate_ms") val maxBackdateMs: Long = 30_000,
    /** A lifecycle gap longer than this ends the session at the background-entry time. */
    @SerialName("lifecycle_gap_session_end_ms") val lifecycleGapSessionEndMs: Long = 600_000,

    // ---- G1 (spec 3장)
    /** A second counts as "face detected" when `face_detect_ratio` reaches this. Not in the spec; see README. */
    @SerialName("face_present_min_ratio") val facePresentMinRatio: Double = 0.5,
    @SerialName("absent_confirm_ms") val absentConfirmMs: Long = 3_000,
    @SerialName("absent_release_ms") val absentReleaseMs: Long = 1_000,
    @SerialName("prone_confirm_ms") val proneConfirmMs: Long = 30_000,
    @SerialName("prone_release_face_ms") val proneReleaseFaceMs: Long = 1_000,
    @SerialName("prone_release_motion_ms") val proneReleaseMotionMs: Long = 2_000,
    /** Low motion: pose motion < this × m0_pose. */
    @SerialName("low_motion_ratio") val lowMotionRatio: Double = 0.3,
    /** Head-below-shoulder margin as a fraction of shoulder width. */
    @SerialName("head_below_margin_ratio") val headBelowMarginRatio: Double = 0.15,
    @SerialName("shoulder_visibility_min") val shoulderVisibilityMin: Double = 0.6,
    @SerialName("torso_position_tolerance_ratio") val torsoPositionToleranceRatio: Double = 0.5,
    @SerialName("torso_size_ratio_min") val torsoSizeRatioMin: Double = 0.6,
    @SerialName("torso_size_ratio_max") val torsoSizeRatioMax: Double = 1.6,
    /** "카메라 위치를 조정해 주세요" alert after this much head-missing low motion. */
    @SerialName("head_missing_notify_ms") val headMissingNotifyMs: Long = 30_000,
    @SerialName("auto_pause_ms") val autoPauseMs: Long = 120_000,
    @SerialName("auto_resume_face_ms") val autoResumeFaceMs: Long = 3_000,

    // ---- G2 (spec 3장, 4장)
    @SerialName("away_grace_ms") val awayGraceMs: Long = 4_000,
    @SerialName("away_long_ms") val awayLongMs: Long = 15_000,
    @SerialName("zone_half_width_min_deg") val zoneHalfWidthMinDeg: Double = 12.0,
    @SerialName("zone_mad_multiplier") val zoneMadMultiplier: Double = 2.5,
    @SerialName("away_reregister_window_ms") val awayReregisterWindowMs: Long = 300_000,
    @SerialName("away_reregister_fraction") val awayReregisterFraction: Double = 0.5,

    // ---- Phone gate (spec 3장)
    @SerialName("pickup_tilt_deg") val pickupTiltDeg: Double = 15.0,
    @SerialName("pickup_accel_var_ratio") val pickupAccelVarRatio: Double = 3.0,
    @SerialName("pickup_confirm_ms") val pickupConfirmMs: Long = 3_000,
    @SerialName("redock_tilt_tolerance_deg") val redockTiltToleranceDeg: Double = 10.0,
    /** INVALID for this long after pickup until re-dock is confirmed; PHONE afterwards. */
    @SerialName("redock_pending_invalid_ms") val redockPendingInvalidMs: Long = 60_000,

    // ---- Scene / quality proxies (spec 6장)
    @SerialName("fps_min") val fpsMin: Double = 10.0,
    @SerialName("scene_luma_min") val sceneLumaMin: Double = 40.0,
    @SerialName("bg_tile_texture_collapse_ratio") val bgTileTextureCollapseRatio: Double = 0.25,
    @SerialName("bg_tile_collapsed_fraction") val bgTileCollapsedFraction: Double = 0.8,
    @SerialName("occlusion_min_ms") val occlusionMinMs: Long = 1_000,
    @SerialName("face_width_frame_invalid_px") val faceWidthFrameInvalidPx: Double = 80.0,
    @SerialName("jitter_frame_invalid_ratio") val jitterFrameInvalidRatio: Double = 3.0,
) {
    init {
        require(finalizeDelayMs > 0) { "finalize_delay_ms must be positive" }
        require(maxBackdateMs >= 0) { "max_backdate_ms must not be negative" }
        require(lifecycleGapSessionEndMs > 0) { "lifecycle_gap_session_end_ms must be positive" }
        require(facePresentMinRatio in 0.0..1.0) { "face_present_min_ratio must be within 0..1" }
    }

    companion object {
        /** Spec v0.2.0 initial values. */
        val DEFAULT: ParameterSet = ParameterSet(parameterSetId = "ps-v0.2.0-default")
    }
}
