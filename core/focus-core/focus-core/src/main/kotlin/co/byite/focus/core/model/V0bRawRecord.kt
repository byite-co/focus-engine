package co.byite.focus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `v0b_raw` line: pre-calibration raw scalars for the V0-B stage (directive C). One line per
 * second next to the [SecondRecord] with the same `t_mono_ms`. Everything here is a scalar; the
 * data boundary of spec 9장 applies (no pixels, no landmarks, no arrays — `SchemaBoundaryTest`).
 *
 * These values feed the V0-C calibration design (shoulder ROI, scene texture baseline) and the
 * R4 budget check (inference time, thermal, power). They are not gate inputs.
 */
@Serializable
data class V0bRawRecord(
    /** Bucket start, same as the paired `second` line. */
    @SerialName("t_mono_ms") val tMonoMs: Long,
    /** Dev-app segment marker active at the bucket start (정면, 정지, 숙임, 엎드림, 자리비움, 빈의자). Null = no marker. */
    @SerialName("segment_label") val segmentLabel: String? = null,

    // ---- Pose (last detected sample in the bucket; upright image frame, normalised 0..1)
    @SerialName("shoulder_center_x") val shoulderCenterX: Double? = null,
    @SerialName("shoulder_center_y") val shoulderCenterY: Double? = null,
    /** Shoulder-to-shoulder pixel distance ÷ upright image width. */
    @SerialName("shoulder_width") val shoulderWidth: Double? = null,
    /** Pose Landmarker runs in this bucket (detected or not). */
    @SerialName("pose_samples") val poseSamples: Int,

    // ---- Scene (64×48 subsample, 4×4 tiles; texture = std-dev of Y inside a tile)
    @SerialName("tile_texture_min") val tileTextureMin: Double? = null,
    @SerialName("tile_texture_median") val tileTextureMedian: Double? = null,
    @SerialName("scene_samples") val sceneSamples: Int,

    // ---- Inference time on the single analysis thread
    @SerialName("face_infer_ms_mean") val faceInferMsMean: Double? = null,
    /** Nearest-rank p95 of the per-frame Face Landmarker time inside this bucket. */
    @SerialName("face_infer_ms_p95") val faceInferMsP95: Double? = null,
    @SerialName("face_infer_ms_max") val faceInferMsMax: Double? = null,
    @SerialName("pose_infer_ms_mean") val poseInferMsMean: Double? = null,
    @SerialName("pose_infer_ms_max") val poseInferMsMax: Double? = null,
    /** Mean (analyzer callback − capture timestamp) of processed frames. */
    @SerialName("frame_latency_ms_mean") val frameLatencyMsMean: Double? = null,

    // ---- IMU (accelerometer, m/s²)
    @SerialName("imu_samples") val imuSamples: Int,
    @SerialName("accel_x_mean") val accelXMean: Double? = null,
    @SerialName("accel_y_mean") val accelYMean: Double? = null,
    @SerialName("accel_z_mean") val accelZMean: Double? = null,
    /** Sum of the three per-axis population variances inside the bucket. */
    @SerialName("accel_variance") val accelVariance: Double? = null,

    // ---- Device
    /** Android PowerManager thermal status 0..6 (NONE … SHUTDOWN). */
    @SerialName("thermal_status") val thermalStatus: Int,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    /** BATTERY_PROPERTY_CURRENT_NOW as reported; sign and unit vary by vendor. */
    @SerialName("battery_current_ua") val batteryCurrentUa: Int? = null,
    @SerialName("battery_voltage_mv") val batteryVoltageMv: Int? = null,
    @SerialName("is_interactive") val isInteractive: Boolean,
    @SerialName("is_device_idle") val isDeviceIdle: Boolean,
) {
    init {
        require(poseSamples >= 0 && sceneSamples >= 0 && imuSamples >= 0) { "sample counters must not be negative (t=$tMonoMs)" }
    }
}
