package co.byite.focus.core.aggregate

import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ScreenState

/**
 * Inputs of the [FeatureAggregator]: what the device layer (CameraPipeline, FacePipeline,
 * PosePipeline, SceneQuality, MotionPipeline) hands over per frame or per sensor sample.
 * Scalars only — the pixels and landmarks never leave those modules (spec 9장 데이터 경계).
 *
 * All times are on the session's monotonic clock (Android `elapsedRealtime`), already converted
 * by the device layer's Timebase from the camera / IMU clocks. Frame values are stamped with the
 * *capture* timestamp, never with the callback arrival time (spec 9장 시간 기준).
 *
 * Threading (directive D, 정정 3): every sample is produced on a pipeline thread and *posted* to the
 * aggregation queue; only that queue's thread touches the aggregator.
 */
data class FrameSample(
    /** Capture timestamp (ns, monotonic). */
    val captureMonoNs: Long,
    /** Analyzer callback time − capture time (ns). Processing latency, for `frame_latency_ms_mean`. */
    val latencyNs: Long,
    val faceDetected: Boolean,
    /** Head pose in degrees (sign convention documented in the engine README). Null without a face. */
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    /** Face width in source pixels. Null without a face. */
    val faceWidthPx: Double? = null,
    /** Rigid-residual jitter j against the previous face frame (spec 6장). Null without a face or without a previous frame. */
    val jitterJ: Double? = null,
    /** Stage timings of this frame on the analysis thread (ms). The Face Landmarker time itself travels with [FeatureAggregator.onFrameProcessed]. */
    val wrapMs: Double = 0.0,
    val facePostMs: Double = 0.0,
    /** Whole analyzer callback for this frame (entry → image closed), the "Face cycle" a PerformanceHintManager session is told about. */
    val totalMs: Double = 0.0,
) {
    init {
        if (!faceDetected) {
            require(yawDeg == null && pitchDeg == null && rollDeg == null && faceWidthPx == null && jitterJ == null) {
                "face scalars need a detected face (t=$captureMonoNs)"
            }
        }
        require(latencyNs >= 0L && wrapMs >= 0.0 && facePostMs >= 0.0 && totalMs >= 0.0) { "timings must not be negative (t=$captureMonoNs)" }
    }
}

/**
 * One Pose Landmarker run. Geometry is in the *upright* image frame (rotation already applied)
 * in pixels; the aggregator normalises with [frameWidthPx]/[frameHeightPx] for the record.
 */
data class PoseSample(
    val captureMonoNs: Long,
    val poseInferMs: Double,
    /** False when the model returned no pose; the geometry fields are then null. */
    val detected: Boolean,
    /** min(left, right) shoulder visibility. */
    val shoulderVisibilityMin: Double? = null,
    /** Shoulder centre in upright pixels. */
    val shoulderCenterXPx: Double? = null,
    val shoulderCenterYPx: Double? = null,
    /** Shoulder-to-shoulder distance in upright pixels. */
    val shoulderWidthPx: Double? = null,
    /** Nose / ear landmark visible. */
    val headLandmarkPresent: Boolean = false,
    /** (head_y − shoulder_line_y) ÷ shoulder width, positive = below. Null without a head landmark. */
    val headOffsetBelowShoulderRatio: Double? = null,
    /** Upright frame size in pixels. */
    val frameWidthPx: Int,
    val frameHeightPx: Int,
    /** Pose request (frame copied on the analysis thread) → inference start on the worker (ms). */
    val waitMs: Double = 0.0,
) {
    init {
        require(frameWidthPx > 0 && frameHeightPx > 0) { "frame size must be positive (t=$captureMonoNs)" }
        if (!detected) {
            require(shoulderVisibilityMin == null && shoulderCenterXPx == null && shoulderCenterYPx == null && shoulderWidthPx == null) {
                "pose geometry needs a detected pose (t=$captureMonoNs)"
            }
            require(!headLandmarkPresent && headOffsetBelowShoulderRatio == null) { "head fields need a detected pose (t=$captureMonoNs)" }
        }
        require(headLandmarkPresent || headOffsetBelowShoulderRatio == null) { "head_offset needs a head landmark (t=$captureMonoNs)" }
        require((shoulderCenterXPx == null) == (shoulderCenterYPx == null) && (shoulderCenterXPx == null) == (shoulderWidthPx == null)) {
            "shoulder centre and width go together (t=$captureMonoNs)"
        }
        shoulderWidthPx?.let { require(it > 0.0) { "shoulder width must be positive (t=$captureMonoNs)" } }
        require(poseInferMs >= 0.0 && waitMs >= 0.0) { "timings must not be negative (t=$captureMonoNs)" }
    }

    val hasShoulders: Boolean get() = detected && shoulderCenterXPx != null
}

/** One SceneQuality run (spec 6장 scene proxy): whole-frame luma and the 4×4 tile texture summary. */
data class SceneSample(
    val captureMonoNs: Long,
    /** Whole-frame Y mean, 0..255. */
    val lumaMean: Double,
    /** Minimum and median of the 16 tile textures (std-dev of Y inside a tile). */
    val tileTextureMin: Double,
    val tileTextureMedian: Double,
    /** SceneQuality wall time on the analysis thread (ms). */
    val computeMs: Double = 0.0,
) {
    init {
        require(computeMs >= 0.0) { "timings must not be negative (t=$captureMonoNs)" }
    }
}

/** One accelerometer sample (m/s²) on the monotonic clock. */
data class ImuSample(
    val tMonoNs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
)

/** Device status read once per bucket close. */
data class DeviceSample(
    val thermalStatus: Int,
    val batteryPct: Int? = null,
    val batteryCurrentUa: Int? = null,
    val batteryVoltageMv: Int? = null,
    val isInteractive: Boolean,
    val isDeviceIdle: Boolean,
    val screenState: ScreenState,
    val appState: AppState,
)
