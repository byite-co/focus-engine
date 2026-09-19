package co.byite.focus.core.model

import co.byite.focus.core.log.FocusJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun intervalRecordRejectsNonGapStates() {
        assertFailsWith<IllegalArgumentException> {
            IntervalRecord(0, 10, 0, 10, State.ABSENT, GapReason.APP_SWITCH)
        }
        assertFailsWith<IllegalArgumentException> {
            IntervalRecord(10, 0, 10, 0, State.PHONE, GapReason.APP_SWITCH)
        }
    }

    @Test
    fun gapReasonFixesTheIntervalState() {
        assertEquals(State.PHONE, GapReason.APP_SWITCH.state)
        assertEquals(State.PAUSED, GapReason.SCREEN_LOCK.state)
    }

    @Test
    fun parameterSetRoundTripsAndKeepsId() {
        val json = FocusJson.compact.encodeToString(ParameterSet.serializer(), ParameterSet.DEFAULT)
        assertTrue(json.contains("\"parameter_set_id\":\"ps-v0.2.0-default\""))
        assertEquals(ParameterSet.DEFAULT, FocusJson.compact.decodeFromString(ParameterSet.serializer(), json))
        // a partial file only needs the id; every threshold has a spec default
        val partial = FocusJson.compact.decodeFromString(ParameterSet.serializer(), """{"parameter_set_id":"x","absent_confirm_ms":4000}""")
        assertEquals(4000, partial.absentConfirmMs)
        assertEquals(30_000, partial.finalizeDelayMs)
    }

    @Test
    fun parameterSetValidates() {
        assertFailsWith<IllegalArgumentException> { ParameterSet("bad", finalizeDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> { ParameterSet("bad", facePresentMinRatio = 1.5) }
    }

    @Test
    fun stateSetsFollowTheSpec() {
        assertEquals(setOf(State.ABSENT, State.PRONE, State.PHONE), State.BACKDATABLE)
        assertEquals(setOf(State.PHONE, State.PAUSED), State.INTERVAL_STATES)
        assertEquals(listOf("PHONE", "INVALID", "ABSENT", "PRONE", "AWAY", "PRESENT", "PAUSED"), State.entries.map { it.name })
    }
}
