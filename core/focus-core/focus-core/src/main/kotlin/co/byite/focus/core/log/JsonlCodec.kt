package co.byite.focus.core.log

import co.byite.focus.core.model.CalibrationSnapshot
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import co.byite.focus.core.model.TimebaseRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when a JSONL session log is malformed. */
class LogFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Session JSONL codec (schema 0.2.1).
 *
 * Line 1 is the [SessionHeader]. Every later line is one object with a `type` key: `calibration`
 * ([CalibrationSnapshot]), `timebase` ([TimebaseRecord]), `second` ([SecondRecord]), `interval`
 * ([IntervalRecord]) or `session_end` ([SessionEnd]). When `type` is missing it is inferred from
 * the keys. Blank lines and unknown keys are ignored.
 */
object JsonlCodec {
    const val TYPE_KEY: String = "type"
    const val TYPE_HEADER: String = "header"
    const val TYPE_CALIBRATION: String = "calibration"
    const val TYPE_TIMEBASE: String = "timebase"
    const val TYPE_SECOND: String = "second"
    const val TYPE_INTERVAL: String = "interval"
    const val TYPE_SESSION_END: String = "session_end"

    private val json = FocusJson.compact

    fun decode(text: String): SessionLog {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) throw LogFormatException("empty log: no header line")

        val header = try {
            json.decodeFromJsonElement(SessionHeader.serializer(), parseObject(lines[0], 1))
        } catch (e: SerializationException) {
            throw LogFormatException("line 1: invalid session header: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw LogFormatException("line 1: invalid session header: ${e.message}", e)
        }

        val records = ArrayList<SecondRecord>()
        val intervals = ArrayList<IntervalRecord>()
        val calibrations = ArrayList<CalibrationSnapshot>()
        val timebase = ArrayList<TimebaseRecord>()
        var sessionEnd: SessionEnd? = null
        for (i in 1 until lines.size) {
            val lineNo = i + 1
            val obj = parseObject(lines[i], lineNo)
            val type = obj[TYPE_KEY]?.jsonPrimitive?.content ?: inferType(obj, lineNo)
            try {
                when (type) {
                    TYPE_SECOND -> records.add(json.decodeFromJsonElement(SecondRecord.serializer(), obj))
                    TYPE_INTERVAL -> intervals.add(json.decodeFromJsonElement(IntervalRecord.serializer(), obj))
                    TYPE_CALIBRATION -> calibrations.add(json.decodeFromJsonElement(CalibrationSnapshot.serializer(), obj))
                    TYPE_TIMEBASE -> timebase.add(json.decodeFromJsonElement(TimebaseRecord.serializer(), obj))
                    TYPE_SESSION_END -> {
                        if (sessionEnd != null) throw LogFormatException("line $lineNo: second session_end line")
                        sessionEnd = json.decodeFromJsonElement(SessionEnd.serializer(), obj)
                    }
                    TYPE_HEADER -> throw LogFormatException("line $lineNo: header must be the first line only")
                    else -> throw LogFormatException("line $lineNo: unknown type '$type'")
                }
            } catch (e: SerializationException) {
                throw LogFormatException("line $lineNo: invalid $type record: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw LogFormatException("line $lineNo: invalid $type record: ${e.message}", e)
            }
        }
        return SessionLog(header, records, intervals, sessionEnd, calibrations, timebase)
    }

    /** Encode as JSONL text (each line terminated by `\n`). Timed lines are written in time order. */
    fun encode(log: SessionLog): String = buildString {
        for (line in encodeLines(log)) {
            append(line)
            append('\n')
        }
    }

    /** One JSON line per element: header first, then calibration/timebase/interval/second lines merged by time, then session_end. */
    fun encodeLines(log: SessionLog): Sequence<String> = sequence {
        yield(typed(TYPE_HEADER, SessionHeader.serializer(), log.header))
        val timed = ArrayList<Triple<Long, Int, String>>()
        log.calibrations.forEach { timed.add(Triple(it.tMonoMs, 0, typed(TYPE_CALIBRATION, CalibrationSnapshot.serializer(), it))) }
        log.timebase.forEach { timed.add(Triple(it.tMonoMs, 1, typed(TYPE_TIMEBASE, TimebaseRecord.serializer(), it))) }
        log.intervals.forEach { timed.add(Triple(it.tStartMonoMs, 2, typed(TYPE_INTERVAL, IntervalRecord.serializer(), it))) }
        log.records.forEach { timed.add(Triple(it.tMonoMs, 3, typed(TYPE_SECOND, SecondRecord.serializer(), it))) }
        timed.sortWith(compareBy({ it.first }, { it.second }))
        for (t in timed) yield(t.third)
        log.sessionEnd?.let { yield(typed(TYPE_SESSION_END, SessionEnd.serializer(), it)) }
    }

    private fun <T> typed(type: String, serializer: KSerializer<T>, value: T): String {
        val obj = json.encodeToJsonElement(serializer, value).jsonObject
        val withType = LinkedHashMap<String, JsonElement>(obj.size + 1)
        withType[TYPE_KEY] = JsonPrimitive(type)
        withType.putAll(obj)
        return json.encodeToString(JsonObject.serializer(), JsonObject(withType))
    }

    private fun parseObject(line: String, lineNo: Int): JsonObject = try {
        json.parseToJsonElement(line).jsonObject
    } catch (e: SerializationException) {
        throw LogFormatException("line $lineNo: not a JSON object: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw LogFormatException("line $lineNo: not a JSON object: ${e.message}", e)
    }

    private fun inferType(obj: JsonObject, lineNo: Int): String = when {
        "t_start_mono_ms" in obj -> TYPE_INTERVAL
        "camera_ts_source" in obj -> TYPE_TIMEBASE
        "calibration_id" in obj && "zones" in obj -> TYPE_CALIBRATION
        "t_mono_ms" in obj && "face_detect_ratio" in obj -> TYPE_SECOND
        "t_mono_ms" in obj && "reason" in obj -> TYPE_SESSION_END
        else -> throw LogFormatException("line $lineNo: missing 'type' and cannot infer record type")
    }
}
