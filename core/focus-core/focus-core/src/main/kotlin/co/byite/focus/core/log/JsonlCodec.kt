package co.byite.focus.core.log

import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionHeader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when a JSONL session log is malformed. */
class LogFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Session JSONL codec.
 *
 * Line 1 is the [SessionHeader]. Every later line is one object with a `type` key:
 * `second` ([SecondRecord]), `interval` ([IntervalRecord]) or `session_end` ([SessionEnd]).
 * When `type` is missing it is inferred from the keys (`t_mono_ms` → second,
 * `t_start_mono_ms` → interval). Blank lines are skipped.
 */
object JsonlCodec {
    const val TYPE_KEY: String = "type"
    const val TYPE_HEADER: String = "header"
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
        }

        val records = ArrayList<SecondRecord>()
        val intervals = ArrayList<IntervalRecord>()
        var sessionEnd: SessionEnd? = null
        for (i in 1 until lines.size) {
            val lineNo = i + 1
            val obj = parseObject(lines[i], lineNo)
            val type = obj[TYPE_KEY]?.jsonPrimitive?.content ?: inferType(obj, lineNo)
            try {
                when (type) {
                    TYPE_SECOND -> records.add(json.decodeFromJsonElement(SecondRecord.serializer(), obj))
                    TYPE_INTERVAL -> intervals.add(json.decodeFromJsonElement(IntervalRecord.serializer(), obj))
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
        return SessionLog(header, records, intervals, sessionEnd)
    }

    /** Encode as JSONL text (each line terminated by `\n`). Records and intervals are written in time order. */
    fun encode(log: SessionLog): String = buildString {
        for (line in encodeLines(log)) {
            append(line)
            append('\n')
        }
    }

    fun encodeLines(log: SessionLog): Sequence<String> = sequence {
        yield(typed(TYPE_HEADER, json.encodeToJsonElement(SessionHeader.serializer(), log.header).jsonObject))
        val records = log.records.sortedBy { it.tMonoMs }
        val intervals = log.intervals.sortedBy { it.tStartMonoMs }
        var ii = 0
        for (r in records) {
            while (ii < intervals.size && intervals[ii].tStartMonoMs < r.tMonoMs) {
                yield(typed(TYPE_INTERVAL, json.encodeToJsonElement(IntervalRecord.serializer(), intervals[ii++]).jsonObject))
            }
            yield(typed(TYPE_SECOND, json.encodeToJsonElement(SecondRecord.serializer(), r).jsonObject))
        }
        while (ii < intervals.size) {
            yield(typed(TYPE_INTERVAL, json.encodeToJsonElement(IntervalRecord.serializer(), intervals[ii++]).jsonObject))
        }
        log.sessionEnd?.let {
            yield(typed(TYPE_SESSION_END, json.encodeToJsonElement(SessionEnd.serializer(), it).jsonObject))
        }
    }

    private fun typed(type: String, obj: JsonObject): String {
        val withType = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(obj.size + 1)
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
        "t_mono_ms" in obj && "face_detect_ratio" in obj -> TYPE_SECOND
        "t_mono_ms" in obj && "reason" in obj -> TYPE_SESSION_END
        else -> throw LogFormatException("line $lineNo: missing 'type' and cannot infer record type")
    }
}
