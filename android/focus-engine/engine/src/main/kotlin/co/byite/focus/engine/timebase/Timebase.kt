package co.byite.focus.engine.timebase

import android.hardware.camera2.CameraMetadata
import co.byite.focus.core.model.TimebaseRecord

/**
 * One monotonic reference for camera and IMU timestamps (v0-plan 2장 Timebase, spec 9장 시간 기준).
 *
 * - Camera: when `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME the capture timestamp already is
 *   `elapsedRealtimeNanos` and the offset is 0 (R1 result on SM-S948N). Otherwise the offset is estimated
 *   from the analyzer callbacks ([ClockOffsetEstimator], 100 frames) and re-measured every minute.
 * - IMU: sensor event timestamps are `elapsedRealtimeNanos` on most devices but not guaranteed, so the
 *   offset is always estimated (25 samples ≈ 5 s at 5 Hz) and re-measured every minute. Until the first
 *   window completes the running minimum is used; the bias is at most the delivery latency.
 *
 * Timestamp domains (directive E 4장): the raw camera timestamp is the frame identity and decides session membership;
 * the converted mono value is only the position. [cameraToMono] (analyzer frames, feeds the estimator) and
 * [cameraToMonoNoSample] (capture results, does not feed it) can give the same frame different mono values on an
 * UNKNOWN-source camera, which is why nothing is matched by the mono value. At the stop fence the offset is frozen
 * ([freezeCameraOffset]): every later conversion uses the snapshot and the estimator is not fed again.
 */
class Timebase(cameraTsSource: Int?) {
    val cameraSourceName: String = when (cameraTsSource) {
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
        null -> "null"
        else -> "other($cameraTsSource)"
    }
    val cameraIsRealtime: Boolean = cameraTsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    private val camera = ClockOffsetEstimator(windowSamples = 100)
    private val imu = ClockOffsetEstimator(windowSamples = 25)
    @Volatile private var frozenCameraOffsetNs: Long? = null

    val cameraOffsetNs: Long get() = frozenCameraOffsetNs ?: if (cameraIsRealtime) 0L else camera.currentOffsetNs
    val imuOffsetNs: Long get() = imu.currentOffsetNs

    /** True once [freezeCameraOffset] ran (stop fence). */
    val cameraOffsetFrozen: Boolean get() = frozenCameraOffsetNs != null

    /** Feed one analyzer callback (unless frozen); returns the frame's capture time on the monotonic clock. */
    fun cameraToMono(captureTsNs: Long, callbackNs: Long): Long {
        frozenCameraOffsetNs?.let { return captureTsNs + it }
        if (cameraIsRealtime) return captureTsNs
        camera.onSample(captureTsNs, callbackNs)
        return captureTsNs + camera.currentOffsetNs
    }

    /** Map a camera timestamp with the current (or frozen) offset without feeding the estimator (capture results). */
    fun cameraToMonoNoSample(captureTsNs: Long): Long = captureTsNs + cameraOffsetNs

    /**
     * Stop fence: fix the camera offset at its current estimate and stop feeding the estimator. Returns the snapshot,
     * so `stopFenceRawTs = stopFenceMonoNs − snapshot`. Idempotent.
     */
    fun freezeCameraOffset(): Long {
        frozenCameraOffsetNs?.let { return it }
        val snapshot = cameraOffsetNs
        frozenCameraOffsetNs = snapshot
        return snapshot
    }

    fun imuToMono(eventTsNs: Long, callbackNs: Long): Long {
        imu.onSample(eventTsNs, callbackNs)
        return eventTsNs + imu.currentOffsetNs
    }

    /** `timebase` line for the log. */
    fun record(tMonoMs: Long): TimebaseRecord = TimebaseRecord(
        tMonoMs = tMonoMs,
        cameraTsSource = cameraSourceName,
        cameraToMonoOffsetNs = cameraOffsetNs,
        imuToMonoOffsetNs = imuOffsetNs,
    )
}
