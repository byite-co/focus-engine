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
    /** Dev-app segment marker active at the bucket start (정면, 가만히, 숙임, 엎드림, 자리비움, 빈의자). Null = no marker. */
    @SerialName("segment_label") val segmentLabel: String? = null,

    // ---- Pose (last detected sample in the bucket; upright image frame, normalised 0..1)
    @SerialName("shoulder_center_x") val shoulderCenterX: Double? = null,
    @SerialName("shoulder_center_y") val shoulderCenterY: Double? = null,
    /** Shoulder-to-shoulder pixel distance ÷ upright image width. */
    @SerialName("shoulder_width") val shoulderWidth: Double? = null,
    /** Pose results applied to this bucket (detected or not); same count as `pose_applied`. */
    @SerialName("pose_samples") val poseSamples: Int,

    // ---- Scene (64×48 subsample, 4×4 tiles; texture = std-dev of Y inside a tile)
    @SerialName("tile_texture_min") val tileTextureMin: Double? = null,
    @SerialName("tile_texture_median") val tileTextureMedian: Double? = null,
    @SerialName("scene_samples") val sceneSamples: Int,

    // ---- Inference time (Face on the analysis thread, Pose on its worker)
    @SerialName("face_infer_ms_mean") val faceInferMsMean: Double? = null,
    /** Nearest-rank p95 of the per-frame Face Landmarker time inside this bucket. */
    @SerialName("face_infer_ms_p95") val faceInferMsP95: Double? = null,
    @SerialName("face_infer_ms_max") val faceInferMsMax: Double? = null,
    @SerialName("pose_infer_ms_mean") val poseInferMsMean: Double? = null,
    @SerialName("pose_infer_ms_max") val poseInferMsMax: Double? = null,
    /** Mean (analyzer callback − capture timestamp) of processed frames. */
    @SerialName("frame_latency_ms_mean") val frameLatencyMsMean: Double? = null,

    // ---- Stage timings on the analysis thread (schema 0.2.3, CHANGELOG v0.2.3; per processed frame unless noted)
    /** ImageProxy plane → RGBA frame (zero-copy check or row copy). */
    @SerialName("stage_wrap_ms_mean") val stageWrapMsMean: Double? = null,
    /** Landmarks / matrix → scalars (head pose, face width, jitter). */
    @SerialName("stage_face_post_ms_mean") val stageFacePostMsMean: Double? = null,
    /** SceneQuality run, per scene run (1 Hz). */
    @SerialName("stage_scene_ms_mean") val stageSceneMsMean: Double? = null,
    /** Deep copy of the frame for the Pose worker, per pose request. */
    @SerialName("pose_frame_copy_ms_mean") val poseFrameCopyMsMean: Double? = null,
    @SerialName("pose_frame_copy_ms_p95") val poseFrameCopyMsP95: Double? = null,
    @SerialName("pose_frame_copy_ms_max") val poseFrameCopyMsMax: Double? = null,
    /** Whole analyzer callback for one processed frame ("Face cycle": wrap + Face + post + scene + pose copy + post to the aggregation queue). */
    @SerialName("frame_total_ms_mean") val frameTotalMsMean: Double? = null,
    @SerialName("frame_total_ms_p95") val frameTotalMsP95: Double? = null,
    @SerialName("frame_total_ms_max") val frameTotalMsMax: Double? = null,
    /** Pose request → Pose inference start on the worker (queue wait), per completed pose. */
    @SerialName("pose_wait_ms_mean") val poseWaitMsMean: Double? = null,

    // ---- Pose worker counters (schema 0.2.3). Requests and supersessions are attributed by the frame's capture
    // timestamp; completions, applications, errors and late drops by the bucket the result belongs to, or by the oldest
    // open bucket when that one has already closed. Session totals obey
    // `pose_requested = pose_superseded + pose_completed + pose_errors + (unfinished at session end)` and
    // `pose_completed = pose_applied + pose_late_dropped` (checked by `CounterConsistency`).
    /** Frames handed to the Pose worker. */
    @SerialName("pose_requested") val poseRequested: Int = 0,
    /** Requests whose inference finished (applied or late). */
    @SerialName("pose_completed") val poseCompleted: Int = poseSamples,
    /** Results applied to their bucket (== `pose_samples`). */
    @SerialName("pose_applied") val poseApplied: Int = poseSamples,
    /** Requests replaced in the depth-1 queue by a newer frame before the worker took them. */
    @SerialName("pose_superseded") val poseSuperseded: Int = 0,
    /** Results that arrived after their bucket had closed and were discarded. */
    @SerialName("pose_late_dropped") val poseLateDropped: Int = 0,
    /** Requests whose inference threw. */
    @SerialName("pose_errors") val poseErrors: Int = 0,

    // ---- Unexpected non-processing (schema 0.2.3): the parts of `frames_unprocessed_unexpected`
    // (`analyzer_received = skipped_intentional + face_inference_errors + pre_face_errors + processed`)
    /** Face Landmarker calls that threw. */
    @SerialName("face_inference_errors") val faceInferenceErrors: Int = 0,
    /** Other errors before Face inference: non-monotonic timestamp rejects, frame wrap failures, pipelines not ready. */
    @SerialName("pre_face_errors") val preFaceErrors: Int = 0,

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
        require(poseRequested >= 0 && poseCompleted >= 0 && poseApplied >= 0 && poseSuperseded >= 0 && poseLateDropped >= 0 && poseErrors >= 0) { "pose counters must not be negative (t=$tMonoMs)" }
        require(poseApplied == poseSamples) { "pose_applied must equal pose_samples (t=$tMonoMs)" }
        require(faceInferenceErrors >= 0 && preFaceErrors >= 0) { "error counters must not be negative (t=$tMonoMs)" }
    }
}
