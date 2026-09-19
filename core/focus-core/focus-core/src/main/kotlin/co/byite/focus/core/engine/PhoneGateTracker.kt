package co.byite.focus.core.engine

import co.byite.focus.core.model.Event
import co.byite.focus.core.model.EventType
import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ImuState
import co.byite.focus.core.model.InvalidReason
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.RedockBy
import co.byite.focus.core.model.State

/**
 * The phone gate's view of one bucket. [state] is PHONE, INVALID (with [invalidReason]) or null when
 * the IMU side has nothing to say and the camera gates decide.
 */
data class PhoneGateVerdict(
    val state: State?,
    val invalidReason: InvalidReason? = null,
    val candidateState: State? = null,
    val candidateStartMonoMs: Long? = null,
    val events: List<Event> = emptyList(),
) {
    init {
        require((state == State.INVALID) == (invalidReason != null)) { "invalidReason is set exactly when state == INVALID" }
    }
}

/**
 * IMU-side phone gate, bucket by bucket (spec 3장 폰 사용 게이트; v0.2.1 판정 6·7). Pure state
 * machine over [ImuState] plus the optional '재거치 완료' tap; the composing [GateEngine] merges its
 * verdict with the camera gates and never resets it on environmental INVALID (판정 3).
 *
 * - Pickup: `pickup_confirm_ms` worth of consecutive LIFTED or MOVING buckets confirm PHONE at the
 *   last of them, candidate start = the first. Shorter motion leaves those buckets INVALID(phone_shake)
 *   and raises a `shake` event when it ends (판정 7).
 * - RESTING_OFF_DOCK starts the re-dock wait whether or not a pickup was confirmed: INVALID(redock_pending)
 *   from that first bucket for `redock_pending_invalid_ms`, then PHONE with a `redock_pending_timeout`
 *   event (no backdate). Motion returns to the pickup rule; the wait restarts at the next rest (판정 6).
 * - DOCKED (still, within ±10° of the mount posture) for `redock_stationary_confirm_ms`, or a
 *   `user_redock_tap` input event, confirms the re-dock: `redock_confirmed{by}`, then
 *   INVALID(recalibration) for `recalibration_ms` bracketed by `recalibration_start` and
 *   `recalibration_end` (판정 6). Buckets waiting for the stationary confirmation are
 *   INVALID(redock_pending) (구현 가정 1, 확정). `redock_confirmed{by: tap}` is raised only from a
 *   `user_redock_tap` input event (판정 5, 2차).
 * - Motion or an off-dock rest during recalibration raises `recalibration_aborted` and returns to
 *   the pickup rule; [finish] aborts a recalibration still open at session end, so every
 *   `recalibration_start` pairs with `recalibration_end` or `recalibration_aborted` (판정 3, 2차).
 * - UNKNOWN (no IMU samples) holds the current phase: it neither advances bucket counters nor ends
 *   a run; time-based waits keep running (구현 가정 2, 확정).
 */
class PhoneGateTracker(private val params: ParameterSet) {
    private sealed interface Phase {
        data object Idle : Phase
        data class Motion(val startMs: Long, val buckets: Int) : Phase
        data class PickedUp(val startMs: Long) : Phase
        data class RedockPending(val restStartMs: Long) : Phase
        data class RedockConfirming(val startMs: Long, val buckets: Int) : Phase
        data class Recalibrating(val startMs: Long) : Phase
    }

    private var phase: Phase = Phase.Idle

    fun reset() {
        phase = Phase.Idle
    }

    /** True while a pickup is being accumulated or confirmed (the IMU candidate that environmental INVALID must not reset). */
    val hasPickupCandidate: Boolean get() = phase is Phase.Motion || phase is Phase.PickedUp

    /**
     * Judge one bucket. [inputEvents] are the bucket's input events (`user_redock_tap`, `zone_added`);
     * output events in the list are ignored, so a logged `redock_confirmed{by: tap}` never confirms anything.
     */
    fun judge(tMonoMs: Long, imu: ImuState, inputEvents: List<Event> = emptyList()): PhoneGateVerdict {
        val redockTap = inputEvents.any { it.type == EventType.USER_REDOCK_TAP }
        val moving = imu == ImuState.LIFTED || imu == ImuState.MOVING
        val events = ArrayList<Event>(3)
        val verdict: PhoneGateVerdict = when (val p = phase) {
            Phase.Idle -> when {
                moving -> startMotion(tMonoMs, events)
                imu == ImuState.RESTING_OFF_DOCK -> startPending(tMonoMs)
                else -> NONE
            }
            is Phase.Motion -> when {
                moving -> {
                    val n = p.buckets + 1
                    if (n >= params.pickupConfirmBuckets) {
                        phase = Phase.PickedUp(p.startMs)
                        events.add(Event(EventType.PICKUP_CONFIRMED, tMonoMs))
                        PhoneGateVerdict(State.PHONE, candidateState = State.PHONE, candidateStartMonoMs = p.startMs)
                    } else {
                        phase = Phase.Motion(p.startMs, n)
                        shake(p.startMs)
                    }
                }
                imu == ImuState.UNKNOWN -> shake(p.startMs)
                imu == ImuState.RESTING_OFF_DOCK -> {
                    events.add(Event(EventType.SHAKE, tMonoMs))
                    startPending(tMonoMs)
                }
                else -> {
                    events.add(Event(EventType.SHAKE, tMonoMs))
                    phase = Phase.Idle
                    NONE
                }
            }
            is Phase.PickedUp -> when {
                redockTap -> confirmRedock(tMonoMs, RedockBy.TAP, events)
                moving || imu == ImuState.UNKNOWN -> PhoneGateVerdict(State.PHONE)
                imu == ImuState.RESTING_OFF_DOCK -> startPending(tMonoMs)
                else -> startConfirming(tMonoMs, events)
            }
            is Phase.RedockPending -> when {
                redockTap -> confirmRedock(tMonoMs, RedockBy.TAP, events)
                moving -> startMotion(tMonoMs, events)
                imu == ImuState.DOCKED -> startConfirming(tMonoMs, events)
                else -> pendingVerdict(p, tMonoMs, events)
            }
            is Phase.RedockConfirming -> when {
                redockTap -> confirmRedock(tMonoMs, RedockBy.TAP, events)
                moving -> startMotion(tMonoMs, events)
                imu == ImuState.RESTING_OFF_DOCK -> startPending(tMonoMs)
                imu == ImuState.UNKNOWN -> REDOCK_PENDING
                else -> {
                    val n = p.buckets + 1
                    if (n >= params.redockStationaryConfirmBuckets) {
                        confirmRedock(tMonoMs, RedockBy.ORIENTATION, events)
                    } else {
                        phase = Phase.RedockConfirming(p.startMs, n)
                        REDOCK_PENDING
                    }
                }
            }
            is Phase.Recalibrating -> when {
                moving -> {
                    events.add(Event(EventType.RECALIBRATION_ABORTED, tMonoMs))
                    startMotion(tMonoMs, events)
                }
                imu == ImuState.RESTING_OFF_DOCK -> {
                    events.add(Event(EventType.RECALIBRATION_ABORTED, tMonoMs))
                    startPending(tMonoMs)
                }
                else -> {
                    val idx = (tMonoMs - p.startMs) / FocusSchema.RECORD_PERIOD_MS
                    if (idx < params.recalibrationBuckets) {
                        RECALIBRATION
                    } else {
                        events.add(Event(EventType.RECALIBRATION_END, tMonoMs))
                        phase = Phase.Idle
                        NONE
                    }
                }
            }
        }
        return if (events.isEmpty()) verdict else verdict.copy(events = verdict.events + events)
    }

    /**
     * Close the tracker at session end ([tMonoMs] = the last bucket). Returns `recalibration_aborted`
     * when a recalibration was still open so the start/end pairing holds; resets to idle.
     */
    fun finish(tMonoMs: Long): List<Event> {
        val out = if (phase is Phase.Recalibrating) listOf(Event(EventType.RECALIBRATION_ABORTED, tMonoMs)) else emptyList()
        phase = Phase.Idle
        return out
    }

    private fun startMotion(tMonoMs: Long, events: MutableList<Event>): PhoneGateVerdict {
        events.add(Event(EventType.PICKUP_CANDIDATE, tMonoMs))
        return if (params.pickupConfirmBuckets <= 1) {
            phase = Phase.PickedUp(tMonoMs)
            events.add(Event(EventType.PICKUP_CONFIRMED, tMonoMs))
            PhoneGateVerdict(State.PHONE, candidateState = State.PHONE, candidateStartMonoMs = tMonoMs)
        } else {
            phase = Phase.Motion(tMonoMs, 1)
            shake(tMonoMs)
        }
    }

    private fun shake(startMs: Long) =
        PhoneGateVerdict(State.INVALID, InvalidReason.PHONE_SHAKE, candidateState = State.PHONE, candidateStartMonoMs = startMs)

    private fun startPending(tMonoMs: Long): PhoneGateVerdict {
        val p = Phase.RedockPending(tMonoMs)
        phase = p
        return pendingVerdict(p, tMonoMs, ArrayList())
    }

    private fun pendingVerdict(p: Phase.RedockPending, tMonoMs: Long, events: MutableList<Event>): PhoneGateVerdict {
        val idx = (tMonoMs - p.restStartMs) / FocusSchema.RECORD_PERIOD_MS
        return if (idx < params.redockPendingBuckets) {
            REDOCK_PENDING
        } else {
            if (idx == params.redockPendingBuckets.toLong()) events.add(Event(EventType.REDOCK_PENDING_TIMEOUT, tMonoMs))
            PhoneGateVerdict(State.PHONE)
        }
    }

    private fun startConfirming(tMonoMs: Long, events: MutableList<Event>): PhoneGateVerdict =
        if (params.redockStationaryConfirmBuckets <= 1) {
            confirmRedock(tMonoMs, RedockBy.ORIENTATION, events)
        } else {
            phase = Phase.RedockConfirming(tMonoMs, 1)
            REDOCK_PENDING
        }

    private fun confirmRedock(tMonoMs: Long, by: RedockBy, events: MutableList<Event>): PhoneGateVerdict {
        events.add(Event.redockConfirmed(tMonoMs, by))
        events.add(Event(EventType.RECALIBRATION_START, tMonoMs))
        return if (params.recalibrationBuckets <= 0) {
            events.add(Event(EventType.RECALIBRATION_END, tMonoMs))
            phase = Phase.Idle
            NONE
        } else {
            phase = Phase.Recalibrating(tMonoMs)
            RECALIBRATION
        }
    }

    private companion object {
        val NONE = PhoneGateVerdict(null)
        val REDOCK_PENDING = PhoneGateVerdict(State.INVALID, InvalidReason.REDOCK_PENDING)
        val RECALIBRATION = PhoneGateVerdict(State.INVALID, InvalidReason.RECALIBRATION)
    }
}
