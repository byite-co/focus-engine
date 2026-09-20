package co.byite.focus.core.log

import co.byite.focus.core.model.CalibrationSnapshot
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TimebaseRecord
import co.byite.focus.core.model.V0bRawRecord

/**
 * One session as exported to JSONL (schema 0.2.1): header, calibration snapshots, timebase
 * records, per-second records, lifecycle-gap intervals, optional end marker, and the optional
 * `v0b_raw` lines of the V0-B stage ([V0bRawRecord], paired with second records by `t_mono_ms`).
 */
data class SessionLog(
    val header: SessionHeader,
    val records: List<SecondRecord>,
    val intervals: List<IntervalRecord> = emptyList(),
    val sessionEnd: SessionEnd? = null,
    val calibrations: List<CalibrationSnapshot> = emptyList(),
    val timebase: List<TimebaseRecord> = emptyList(),
    val v0bRaw: List<V0bRawRecord> = emptyList(),
)
