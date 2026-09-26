package co.byite.focus.core.aggregate

/**
 * Processing-slot scheduler (directive E 3장) plus the per-frame counter glue of the analysis thread, as pure logic
 * so the engine and the tests run the same code. One instance lives on the analysis thread; every method is called
 * there, in the order the camera-origin events reach that thread. Emits the camera counters through [sink]
 * ([FeatureAggregator] directly in tests, the posting listener in the engine).
 *
 * **Input stream.** The raw `SENSOR_TIMESTAMP` stream of the capture results ([onCaptureResult]), never the
 * ImageAnalysis arrival order. An analyzer frame ([onFrameReceived]) whose capture result has not arrived yet is fed
 * into the same stream by its raw timestamp (the frame proves the capture happened); the capture result that follows
 * is then a duplicate of an already evaluated timestamp. All times and thresholds are raw ns.
 *
 * **Rule.** `anchor` = the first raw timestamp inside the session window ([onSessionStart]); `next_due = anchor`.
 * A capture result `ts` is selected as a processing opportunity when it is the first to satisfy
 * `ts ≥ next_due − cameraFrameInterval / 2`. After a selection `next_due` is **not** re-anchored to `ts`: it advances
 * by [processPeriodNs], and when a capture gap left it behind it catches up in whole periods until `next_due > ts`.
 * The target phase is therefore kept: 24 fps → 15 Hz selects 0, 83.3, 125, 208.3, 250, 333.3 … (intervals 41.7 / 83.3,
 * mean 66.7 ms) and a measured cadence that differs from the nominal one does not accumulate phase. With
 * [processPeriodNs] = null (every-frame presets) every capture result inside the window is a slot.
 *
 * **Slot life.** `expected` when selected; `filled` when Face succeeded on that raw timestamp ([onFaceSucceeded]);
 * `missed` when a later analyzer frame arrives while the slot is still open (backpressure: the frame never reached the
 * analyzer), on a pre-Face or Face error of its frame, or at [close] (stop step 4) for every slot still open. The three
 * are emitted as independent events; nothing is derived.
 *
 * **Window.** Capture results before [onSessionStart] are kept (raw + mono) and replayed once the first analyzer frame
 * fixes `sessionStartRawTs`: the aggregator counts the ones before the start, the ones inside the window enter the
 * stream in raw order together with the first frame itself, so the capture result of the first frame is the first
 * processing opportunity. After [fence] (`stopFenceRawTs`) capture results and analyzer frames with `raw ≥ fence` are
 * neither slots nor accepted by the analyzer. After [close] a capture result is `capture_results_after_close`
 * (normal stops have none: `stop_integrity_failed`).
 */
class FrameScheduler(
    private val sink: CameraCounterSink,
    /** Face processing period (ns); null = every capture result is a processing opportunity. */
    val processPeriodNs: Long?,
    /** Camera frame interval at the nominal cadence (ns); the selection tolerance is half of it. */
    val cameraFrameIntervalNs: Long,
    /** Capture results held before the session start; the oldest are dropped beyond this. */
    private val pendingMax: Int = DEFAULT_PENDING_MAX,
) {
    init {
        processPeriodNs?.let { require(it > 0) { "processPeriodNs must be positive" } }
        require(cameraFrameIntervalNs > 0) { "cameraFrameIntervalNs must be positive" }
        require(pendingMax > 0) { "pendingMax must be positive" }
    }

    /** What happened to one capture result in the scheduler (event log / tests). */
    enum class CaptureDecision {
        /** Held until the session start is known. */
        PENDING,
        /** Raw timestamp before the session start: counted by the aggregator as before-start, never a slot. */
        BEFORE_START,
        /** Raw timestamp at or after the raw fence: counted after-fence, never a slot. */
        AFTER_FENCE,
        /** Arrived after CLOSE: `capture_results_after_close`. */
        AFTER_CLOSE,
        /** A new processing opportunity (`expected` +1). */
        SELECTED,
        NOT_SELECTED,
        /** Timestamp already evaluated (the analyzer frame came first, or a repeated result). */
        DUPLICATE,
        /** Older than the newest evaluated timestamp and not seen before: cannot be selected. */
        OUT_OF_ORDER,
    }

    /** What the analysis thread does with a received frame. */
    enum class FrameOutcome {
        /** Process it: it is the frame of a selected slot. */
        SLOT,
        /** Skip it (`frames_skipped_intentional`): inside the window but not a slot. */
        NOT_SLOT,
        /** Reject it: `raw ≥ stopFenceRawTs` (posted as received so the aggregator counts it after the fence). */
        AFTER_FENCE,
        /** Its raw timestamp is not newer than an earlier frame: a pre-Face error for the analyzer. */
        OUT_OF_ORDER,
    }

    private class Slot(val stamp: CameraStamp)

    private val pending = ArrayList<CameraStamp>()
    private var startRaw: Long? = null
    private var fenceRaw: Long? = null
    private var closed = false
    private var anchor: Long? = null
    private var nextDue = 0L
    private var lastEvaluatedRaw = Long.MIN_VALUE
    private var lastReceivedRaw = Long.MIN_VALUE
    private val open = ArrayList<Slot>()
    /** Recently evaluated raw timestamps → selected, for duplicates (capture results running ahead of their frames). */
    private val recent = ArrayDeque<Pair<Long, Boolean>>()

    // diagnostics (event log)
    var slotsExpected: Long = 0L; private set
    var slotsFilled: Long = 0L; private set
    var slotsMissed: Long = 0L; private set
    var slotsMissedByBackpressure: Long = 0L; private set
    var slotsMissedAtClose: Long = 0L; private set
    var captureResultsAfterClose: Long = 0L; private set
    var captureResultsOutOfOrder: Long = 0L; private set
    var framesRejectedAfterFence: Long = 0L; private set
    var pendingDropped: Long = 0L; private set
    var replayedBeforeStart: Long = 0L; private set

    /** `sessionStartRawTs`, null before the first analyzer frame. */
    val sessionStartRawTs: Long? get() = startRaw

    /** Anchor of the slot phase (first raw timestamp in the window), null before it. */
    val anchorRawTs: Long? get() = anchor

    /** Next slot target (raw ns); meaningful in slot mode after the anchor. */
    val nextDueRawTs: Long get() = nextDue

    /** Selected slots not yet filled or missed. */
    val openSlots: Int get() = open.size

    val isClosed: Boolean get() = closed

    /** `stopFenceRawTs` once [fence] was called. */
    val fenceRawTs: Long? get() = fenceRaw

    // ---- capture results

    /** One `CaptureResult` (raw `SENSOR_TIMESTAMP` + its mono conversion). */
    fun onCaptureResult(stamp: CameraStamp): CaptureDecision {
        if (closed) {
            sink.onFrameRequested(stamp)
            sink.onCaptureResultAfterClose(stamp)
            captureResultsAfterClose++
            return CaptureDecision.AFTER_CLOSE
        }
        val start = startRaw
        if (start == null) {
            if (pending.size >= pendingMax) {
                pending.removeAt(0)
                pendingDropped++
            }
            pending.add(stamp)
            return CaptureDecision.PENDING
        }
        sink.onFrameRequested(stamp)
        if (stamp.rawSensorTs < start) return CaptureDecision.BEFORE_START
        fenceRaw?.let { if (stamp.rawSensorTs >= it) return CaptureDecision.AFTER_FENCE }
        return evaluate(stamp)
    }

    /**
     * The first analyzer frame: `sessionStartRawTs` = its raw timestamp. Pending capture results are handed to the sink
     * in raw order (the aggregator counts the ones before the start); the ones inside the window and the first frame
     * itself enter the scheduler in raw order, so the frame's own capture result is the first processing opportunity.
     * Returns the decisions of the replayed capture results, in raw order.
     */
    fun onSessionStart(first: CameraStamp): List<CaptureDecision> {
        check(startRaw == null) { "session already started" }
        startRaw = first.rawSensorTs
        val replay = pending.sortedBy { it.rawSensorTs }
        pending.clear()
        val stream = ArrayList<CameraStamp>()
        for (p in replay) {
            sink.onFrameRequested(p)
            if (p.rawSensorTs < first.rawSensorTs) replayedBeforeStart++ else stream.add(p)
        }
        // the frame itself proves its capture: it belongs to the stream even when its capture result is still on the way
        if (stream.none { it.rawSensorTs == first.rawSensorTs }) stream.add(first)
        stream.sortBy { it.rawSensorTs }
        val decided = HashMap<Long, CaptureDecision>()
        for (st in stream) decided[st.rawSensorTs] = evaluate(st)
        return replay.map { if (it.rawSensorTs < first.rawSensorTs) CaptureDecision.BEFORE_START else decided.getValue(it.rawSensorTs) }
    }

    // ---- analyzer frames

    /**
     * The ImageAnalysis callback received a frame. Posts `received`, resolves the open slots older than this frame as
     * missed (their frames never reached the analyzer), evaluates the frame's timestamp when it is new to the stream
     * and says whether the analysis thread must process it.
     */
    fun onFrameReceived(stamp: CameraStamp): FrameOutcome {
        checkNotNull(startRaw) { "onSessionStart must precede the first frame" }
        val raw = stamp.rawSensorTs
        val f = fenceRaw
        if (f != null && raw >= f) {
            framesRejectedAfterFence++
            sink.onFrameReceived(stamp) // counted after the fence by the aggregator, never as received
            return FrameOutcome.AFTER_FENCE
        }
        sink.onFrameReceived(stamp)
        if (raw <= lastReceivedRaw) return FrameOutcome.OUT_OF_ORDER
        lastReceivedRaw = raw
        // Frames reach the analyzer in capture order: an open slot older than this frame lost its frame to backpressure.
        var i = 0
        while (i < open.size) {
            val s = open[i]
            if (s.stamp.rawSensorTs < raw) {
                open.removeAt(i)
                slotsMissed++
                slotsMissedByBackpressure++
                sink.onSlotMissed(s.stamp)
            } else {
                i++
            }
        }
        return when (evaluate(stamp)) {
            CaptureDecision.SELECTED -> FrameOutcome.SLOT
            CaptureDecision.DUPLICATE -> if (open.any { it.stamp.rawSensorTs == raw }) FrameOutcome.SLOT else FrameOutcome.NOT_SLOT
            CaptureDecision.OUT_OF_ORDER -> FrameOutcome.OUT_OF_ORDER
            else -> FrameOutcome.NOT_SLOT
        }
    }

    /** The received frame is not a slot: `frames_skipped_intentional`. */
    fun onFrameSkipped(stamp: CameraStamp) = sink.onFrameSkipped(stamp)

    /** Pre-Face error on the received frame; its slot, if any, is missed. */
    fun onPreFaceError(stamp: CameraStamp) {
        sink.onPreFaceError(stamp)
        resolve(stamp, filled = false)
    }

    /** The Face Landmarker threw on the received frame; its slot, if any, is missed. */
    fun onFaceInferenceError(stamp: CameraStamp) {
        sink.onFaceInferenceError(stamp)
        resolve(stamp, filled = false)
    }

    /** Face inference succeeded on the received frame: its slot is filled. Returns false when no slot was open for it. */
    fun onFaceSucceeded(stamp: CameraStamp): Boolean = resolve(stamp, filled = true)

    // ---- stop

    /** Stop step 1: `stopFenceRawTs`. Capture results and frames at or after it are neither slots nor accepted. */
    fun fence(fenceRawTs: Long) {
        if (fenceRaw == null) fenceRaw = fenceRawTs
    }

    /** Stop step 4 (CLOSE): every open slot is missed; later capture results are `capture_results_after_close`. Returns the slots closed as missed. */
    fun close(): Long {
        if (closed) return 0L
        closed = true
        var n = 0L
        for (s in open) {
            slotsMissed++
            slotsMissedAtClose++
            sink.onSlotMissed(s.stamp)
            n++
        }
        open.clear()
        return n
    }

    /** One-line diagnostics for the event log. */
    fun stats(): String =
        "slots expected=$slotsExpected filled=$slotsFilled missed=$slotsMissed (backpressure=$slotsMissedByBackpressure at_close=$slotsMissedAtClose) open=${open.size} " +
            "capture_after_close=$captureResultsAfterClose capture_out_of_order=$captureResultsOutOfOrder frames_rejected_after_fence=$framesRejectedAfterFence " +
            "pending_dropped=$pendingDropped replayed_before_start=$replayedBeforeStart anchor=${anchor ?: "-"} next_due=$nextDue"

    // ---- internals

    private fun resolve(stamp: CameraStamp, filled: Boolean): Boolean {
        val raw = stamp.rawSensorTs
        for (i in open.indices) {
            if (open[i].stamp.rawSensorTs == raw) {
                open.removeAt(i)
                if (filled) {
                    slotsFilled++
                    sink.onSlotFilled(stamp)
                } else {
                    slotsMissed++
                    sink.onSlotMissed(stamp)
                }
                return true
            }
        }
        return false
    }

    /** The slot rule on one new raw timestamp of the stream. */
    private fun evaluate(stamp: CameraStamp): CaptureDecision {
        val raw = stamp.rawSensorTs
        if (recent.any { it.first == raw }) return CaptureDecision.DUPLICATE
        if (raw <= lastEvaluatedRaw) {
            captureResultsOutOfOrder++
            remember(raw, false)
            return CaptureDecision.OUT_OF_ORDER
        }
        lastEvaluatedRaw = raw
        if (anchor == null) {
            anchor = raw
            nextDue = raw
        }
        val period = processPeriodNs
        val selected: Boolean
        if (period == null) {
            selected = true
        } else if (raw >= nextDue - cameraFrameIntervalNs / 2) {
            selected = true
            nextDue += period
            while (nextDue <= raw) nextDue += period // catch-up after a capture gap keeps the target phase
        } else {
            selected = false
        }
        remember(raw, selected)
        if (selected) {
            open.add(Slot(stamp))
            slotsExpected++
            sink.onSlotExpected(stamp)
            return CaptureDecision.SELECTED
        }
        return CaptureDecision.NOT_SELECTED
    }

    private fun remember(raw: Long, selected: Boolean) {
        recent.addLast(raw to selected)
        while (recent.size > RECENT_MAX) recent.removeFirst()
    }

    companion object {
        const val DEFAULT_PENDING_MAX: Int = 1_000
        /** Capture results can run this many frames ahead of their analyzer frames before a duplicate is no longer recognised. */
        const val RECENT_MAX: Int = 128
    }
}
