package co.byite.focus.core.log

import co.byite.focus.core.Synth
import co.byite.focus.core.model.State
import co.byite.focus.core.model.V0bRawRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `v0b_raw` lines (V0-B raw scalars) ride in the same JSONL next to the `second` line of the same bucket. */
class V0bRawCodecTest {
    private val raw0 = V0bRawRecord(tMonoMs = Synth.mono(0), segmentLabel = "정면", poseSamples = 1, sceneSamples = 1, imuSamples = 5, thermalStatus = 0, isInteractive = false, isDeviceIdle = false, faceInferMsMean = 12.5)
    private val raw1 = raw0.copy(tMonoMs = Synth.mono(1), segmentLabel = null)
    private val log = Synth.log(Synth.stated(0, State.PRESENT, State.PRESENT), timebase = listOf(Synth.timebase(0))).copy(v0bRaw = listOf(raw0, raw1))

    @Test
    fun rawLinesFollowTheirSecondLineAndRoundTrip() {
        val text = JsonlCodec.encode(log)
        val lines = text.trim().lines()
        assertEquals(1 + 1 + 4, lines.size)
        assertTrue(lines[2].startsWith("{\"type\":\"second\",\"t_mono_ms\":${Synth.mono(0)},"), lines[2])
        assertTrue(lines[3].startsWith("{\"type\":\"v0b_raw\",\"t_mono_ms\":${Synth.mono(0)},\"segment_label\":\"정면\","), lines[3])
        assertTrue(lines[5].startsWith("{\"type\":\"v0b_raw\",\"t_mono_ms\":${Synth.mono(1)},\"segment_label\":null,"), lines[5])
        assertEquals(log, JsonlCodec.decode(text))
    }

    @Test
    fun typeIsInferredFromPoseSamples() {
        val text = JsonlCodec.encode(log).replace("\"type\":\"v0b_raw\",", "")
        assertEquals(log, JsonlCodec.decode(text))
    }

    @Test
    fun singleLineEncodersMatchTheBulkEncoder() {
        val bulk = JsonlCodec.encode(log).trim().lines()
        assertEquals(bulk[0], JsonlCodec.encodeHeader(log.header))
        assertEquals(bulk[1], JsonlCodec.encodeTimebase(log.timebase[0]))
        assertEquals(bulk[2], JsonlCodec.encodeSecond(log.records[0]))
        assertEquals(bulk[3], JsonlCodec.encodeV0bRaw(raw0))
        val streamed = listOf(
            JsonlCodec.encodeHeader(log.header), JsonlCodec.encodeTimebase(log.timebase[0]),
            JsonlCodec.encodeSecond(log.records[0]), JsonlCodec.encodeV0bRaw(raw0),
            JsonlCodec.encodeSecond(log.records[1]), JsonlCodec.encodeV0bRaw(raw1),
        ).joinToString("\n")
        assertEquals(log, JsonlCodec.decode(streamed))
    }
}
