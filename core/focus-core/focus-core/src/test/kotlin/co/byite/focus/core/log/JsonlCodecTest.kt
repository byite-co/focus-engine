package co.byite.focus.core.log

import co.byite.focus.core.Synth
import co.byite.focus.core.model.GapReason
import co.byite.focus.core.model.IntervalRecord
import co.byite.focus.core.model.SessionEnd
import co.byite.focus.core.model.SessionEndReason
import co.byite.focus.core.model.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonlCodecTest {
    private val interval = IntervalRecord(Synth.mono(5), Synth.mono(65), Synth.utc(5), Synth.utc(65), State.PHONE, GapReason.APP_SWITCH)

    @Test
    fun roundTripKeepsEverything() {
        val log = Synth.log(
            records = Synth.stated(0, State.PRESENT, State.PRESENT, State.ABSENT, State.ABSENT, State.PRESENT) +
                Synth.stated(65, State.PRESENT, State.PRESENT),
            intervals = listOf(interval),
            end = SessionEnd(Synth.mono(67), Synth.utc(67), SessionEndReason.USER),
        )
        val text = JsonlCodec.encode(log)
        val lines = text.trim().lines()
        assertEquals(1 + 7 + 1 + 1, lines.size)
        assertTrue(lines[0].startsWith("{\"type\":\"header\",\"session_id\":"))
        assertTrue(lines[1].startsWith("{\"type\":\"second\",\"t_mono_ms\":"))
        assertTrue(lines[6].startsWith("{\"type\":\"interval\","), lines[6])
        assertTrue(lines.last().startsWith("{\"type\":\"session_end\","))
        assertEquals(log, JsonlCodec.decode(text))
    }

    @Test
    fun typeIsInferredWhenMissing() {
        val log = Synth.log(Synth.stated(0, State.PRESENT), listOf(interval), SessionEnd(Synth.mono(70), null, SessionEndReason.UNKNOWN))
        val text = JsonlCodec.encode(log).replace("\"type\":\"second\",", "").replace("\"type\":\"interval\",", "").replace("\"type\":\"session_end\",", "")
        assertEquals(log, JsonlCodec.decode(text))
    }

    @Test
    fun blankLinesAndUnknownKeysAreTolerated() {
        val text = JsonlCodec.encode(Synth.log(Synth.stated(0, State.PRESENT)))
            .replace("\"face_detect_ratio\"", "\"future_field\":123,\"face_detect_ratio\"") + "\n\n   \n"
        assertEquals(1, JsonlCodec.decode(text).records.size)
    }

    @Test
    fun errorsNameTheLine() {
        val text = JsonlCodec.encode(Synth.log(Synth.stated(0, State.PRESENT, State.PRESENT))).lines().toMutableList()
        text[2] = "{\"type\":\"second\",\"t_mono_ms\":\"oops\"}"
        val e = assertFailsWith<LogFormatException> { JsonlCodec.decode(text.joinToString("\n")) }
        assertTrue(e.message!!.startsWith("line 3:"), e.message)
        assertFailsWith<LogFormatException> { JsonlCodec.decode("") }
        assertFailsWith<LogFormatException> { JsonlCodec.decode("{\"nope\":1}") }
        assertFailsWith<LogFormatException> { JsonlCodec.decode(text[0] + "\n{\"type\":\"header\"}") }
    }

    @Test
    fun encodingIsDeterministic() {
        val log = Synth.log(Synth.stated(0, State.PRESENT, State.ABSENT), listOf(interval))
        assertEquals(JsonlCodec.encode(log), JsonlCodec.encode(log))
    }
}
