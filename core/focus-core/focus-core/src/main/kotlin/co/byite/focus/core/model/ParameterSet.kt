package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every threshold the v0 logic layer reads, bundled under one immutable [parameterSetId]
 * (spec 9장 header `parameter_set_id`). Defaults are the spec v0.2.0 initial values; fields marked
 * 초기값 were introduced by CHANGELOG v0.2.1 and are provisional until the first measurements.
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

    // ---- Face bands (v0.2.1 판정 12, 초기값)
    /** `face_detect_ratio` below this = G1 "얼굴 미검출" bucket. 초기값 0.2 (CHANGELOG v0.2.1). */
    @SerialName("face_missing_max_ratio") val faceMissingMaxRatio: Double = 0.2,
    /** `face_detect_ratio` at or above this = head pose usable for G2. Between the two bands = INVALID(face_unstable). 초기값 0.5. */
    @SerialName("face_present_min_ratio") val facePresentMinRatio: Double = 0.5,

    // ---- G1 (spec 3장)
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
    /** LIFTED or MOVING buckets needed to confirm a pickup; applies to the tilt and the variance branch alike (v0.2.1 판정 7). */
    @SerialName("pickup_confirm_ms") val pickupConfirmMs: Long = 3_000,
    @SerialName("redock_tilt_tolerance_deg") val redockTiltToleranceDeg: Double = 10.0,
    /** INVALID(redock_pending) from the first RESTING_OFF_DOCK bucket for this long; PHONE afterwards (v0.2.1 판정 6). */
    @SerialName("redock_pending_invalid_ms") val redockPendingInvalidMs: Long = 60_000,
    /** Consecutive stationary time within the mount posture that confirms a re-dock. 초기값 2000 (v0.2.1 판정 6). */
    @SerialName("redock_stationary_confirm_ms") val redockStationaryConfirmMs: Long = 2_000,
    /** INVALID(recalibration) after a confirmed re-dock. 초기값 20000 (v0.2.1 판정 6). */
    @SerialName("recalibration_ms") val recalibrationMs: Long = 20_000,

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
        require(faceMissingMaxRatio in 0.0..facePresentMinRatio) { "face_missing_max_ratio must be within 0..face_present_min_ratio" }
        require(pickupConfirmMs > 0 && redockStationaryConfirmMs > 0 && recalibrationMs >= 0 && redockPendingInvalidMs >= 0) { "phone gate durations must be positive" }
    }

    /** Duration to bucket count helpers (v0.2.1 판정 2: "N초 연속" = N buckets). */
    val absentConfirmBuckets: Int get() = FocusSchema.buckets(absentConfirmMs)
    val proneConfirmBuckets: Int get() = FocusSchema.buckets(proneConfirmMs)
    val pickupConfirmBuckets: Int get() = FocusSchema.buckets(pickupConfirmMs)
    val awayGraceBuckets: Int get() = FocusSchema.buckets(awayGraceMs)
    val autoPauseBuckets: Int get() = FocusSchema.buckets(autoPauseMs)
    val redockPendingBuckets: Int get() = FocusSchema.buckets(redockPendingInvalidMs)
    val redockStationaryConfirmBuckets: Int get() = FocusSchema.buckets(redockStationaryConfirmMs)
    val recalibrationBuckets: Int get() = FocusSchema.buckets(recalibrationMs)

    companion object {
        /** Spec v0.2.0 initial values plus the v0.2.1 초기값 (CHANGELOG v0.2.1). */
        val DEFAULT: ParameterSet = ParameterSet(parameterSetId = "ps-v0.2.1-default")
    }
}
