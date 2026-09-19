package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One registered work zone in (yaw, pitch) degrees (spec 4장 작업영역: 중앙값 ± max(12°, 2.5·MAD)). */
@Serializable
data class CalibrationZone(
    @SerialName("zone_id") val zoneId: Int,
    @SerialName("yaw_center_deg") val yawCenterDeg: Double,
    @SerialName("pitch_center_deg") val pitchCenterDeg: Double,
    @SerialName("yaw_half_width_deg") val yawHalfWidthDeg: Double,
    @SerialName("pitch_half_width_deg") val pitchHalfWidthDeg: Double,
)

/**
 * Calibration snapshot, `calibration` line of a session JSONL (schema 0.2.1; spec 4장 수집 값, 9장 header).
 * Written at session start and after every re-dock recalibration so a log replays on its own.
 *
 * Data boundary: this is the only model allowed fixed-length numeric lists, and only the named
 * ones ([dockGravityVector] = 3, [bgTileTextureBaseline] and [bgTileMask] = 16, [zones] ≤ 3).
 * All values are derived numbers; no pixels, no landmarks.
 */
@Serializable
data class CalibrationSnapshot(
    @SerialName("calibration_id") val calibrationId: String,
    val version: String,
    @SerialName("t_mono_ms") val tMonoMs: Long,
    val zones: List<CalibrationZone>,
    /** Torso ROI in normalised image coordinates (0..1). */
    @SerialName("torso_center_x") val torsoCenterX: Double,
    @SerialName("torso_center_y") val torsoCenterY: Double,
    @SerialName("torso_width") val torsoWidth: Double,
    /** Shoulder-centre motion baseline and its jitter floor (spec 4장 m0_pose). */
    @SerialName("m0_pose") val m0Pose: Double,
    @SerialName("pose_jitter_floor") val poseJitterFloor: Double,
    /** Nose-tip motion baseline and floor (spec 4장 m0). */
    val m0: Double,
    @SerialName("jitter_floor") val jitterFloor: Double,
    /** Rigid-residual jitter j baseline (spec 6장 품질 proxy). */
    @SerialName("jitter_j_baseline") val jitterJBaseline: Double,
    /** Mount posture gravity vector (x, y, z) in m/s². */
    @SerialName("dock_gravity_vector") val dockGravityVector: List<Double>,
    @SerialName("dock_accel_variance") val dockAccelVariance: Double,
    @SerialName("scene_luma_baseline") val sceneLumaBaseline: Double,
    /** Texture baseline per 4×4 tile, row-major. */
    @SerialName("bg_tile_texture_baseline") val bgTileTextureBaseline: List<Double>,
    /** True for tiles outside the person ROI at calibration (the occlusion proxy uses only these). */
    @SerialName("bg_tile_mask") val bgTileMask: List<Boolean>,
) {
    init {
        require(zones.size <= MAX_ZONES) { "at most $MAX_ZONES zones, got ${zones.size}" }
        require(zones.map { it.zoneId }.toSet().size == zones.size) { "zone ids must be unique" }
        require(dockGravityVector.size == GRAVITY_DIMS) { "dock_gravity_vector needs $GRAVITY_DIMS values, got ${dockGravityVector.size}" }
        require(bgTileTextureBaseline.size == TILE_COUNT) { "bg_tile_texture_baseline needs $TILE_COUNT values, got ${bgTileTextureBaseline.size}" }
        require(bgTileMask.size == TILE_COUNT) { "bg_tile_mask needs $TILE_COUNT values, got ${bgTileMask.size}" }
    }

    companion object {
        const val MAX_ZONES: Int = 3
        const val GRAVITY_DIMS: Int = 3
        const val TILE_COUNT: Int = 16

        /** Serial names of the fixed-length numeric lists the data boundary allows on this model. */
        val FIXED_LENGTH_LISTS: Map<String, Int> = mapOf(
            "dock_gravity_vector" to GRAVITY_DIMS,
            "bg_tile_texture_baseline" to TILE_COUNT,
            "bg_tile_mask" to TILE_COUNT,
        )
    }
}
