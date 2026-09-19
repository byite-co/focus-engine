package co.byite.focus.core.log

import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader

/** One session as exported to JSONL: header, per-second records, lifecycle-gap intervals, optional end marker. */
data class SessionLog(
    val header: SessionHeader,
    val records: List<SecondRecord>,
    val intervals: List<IntervalRecord> = emptyList(),
    val sessionEnd: SessionEnd? = null,
)
