package co.byite.focus.core.aggregate

/**
 * Sink of the camera-origin counter events (directive E 2·3장). The [FrameScheduler] on the analysis thread emits
 * them; [FeatureAggregator] implements the sink, and the device layer's listener implementation forwards each call
 * to the aggregation queue (directive D 정정 3: nothing but that queue's thread touches the aggregator). Every event
 * carries a [CameraStamp]: the raw sensor timestamp decides membership, the mono value decides the bucket.
 *
 * Frame counters (`frames_*`, schema 0.2.3, meaning unchanged) and processing-slot counters (schema 0.2.4) are
 * independent: a slot is counted `expected` when the scheduler selects a processing opportunity, `filled` when
 * Face inference succeeds on that raw timestamp, `missed` when the slot terminates without a Face success
 * (backpressure, pre-Face error, Face error, unresolved at CLOSE). Nothing derives one from the others.
 */
interface CameraCounterSink {
    /** A capture result (`CaptureResult.SENSOR_TIMESTAMP`): `frames_requested`. */
    fun onFrameRequested(stamp: CameraStamp)

    /** The ImageAnalysis callback received a frame (before any skip / processing decision): `frames_analyzer_received`. */
    fun onFrameReceived(stamp: CameraStamp)

    /** A received frame that is not a processing slot (presets E / E15): `frames_skipped_intentional`. */
    fun onFrameSkipped(stamp: CameraStamp)

    /** The Face Landmarker threw on this frame. */
    fun onFaceInferenceError(stamp: CameraStamp)

    /** Another error before Face inference (non-monotonic timestamp, wrap failure, pipelines not ready). */
    fun onPreFaceError(stamp: CameraStamp)

    /** The scheduler selected this capture result as a processing opportunity: `processing_slots_expected` +1. */
    fun onSlotExpected(stamp: CameraStamp)

    /** Face inference succeeded on the frame of a selected slot: `processing_slots_filled` +1. */
    fun onSlotFilled(stamp: CameraStamp)

    /** A selected slot terminated without a Face success: `processing_slots_missed` +1. */
    fun onSlotMissed(stamp: CameraStamp)

    /** A capture result that reached the scheduler after its CLOSE (stop step 4): `capture_results_after_close` +1; normal stops have none. */
    fun onCaptureResultAfterClose(stamp: CameraStamp)
}
