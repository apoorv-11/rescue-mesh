package com.rescuemesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The state-colour mapping (UI redesign spec, 14 Nov): the exact spec
 * hex values for all eight record states, the border following the
 * chip colour, and the calm slate fallback for anything unknown —
 * Records cards and Mesh rows both read from this one source.
 */
class StateColorsTest {

    private fun assertState(state: String, fg: Long, bg: Long) {
        val colors = StateColors.forState(state)
        assertEquals("foreground for $state", fg, colors.foreground)
        assertEquals("background for $state", bg, colors.background)
        assertEquals("border follows the chip colour for $state", fg, colors.border)
    }

    @Test
    fun `all eight spec states map to their exact colours`() {
        assertState("CREATED", 0xFF64748B, 0xFFF1F5F9)
        assertState("QUEUED", 0xFFB45309, 0xFFFEF3C7)
        assertState("SENDING", 0xFF2563EB, 0xFFDBEAFE)
        assertState("PEER_ACKED", 0xFF0E9F6E, 0xFFD1FAE5)
        assertState("SERVER_SYNCED", 0xFF6D28D9, 0xFFEDE9FE)
        assertState("FAILED_RETRYABLE", 0xFFEA580C, 0xFFFFEDD5)
        assertState("EXPIRED", 0xFF6B7280, 0xFFF3F4F6)
        assertState("REJECTED", 0xFFDC2626, 0xFFFEE2E2)
    }

    @Test
    fun `every known state is covered`() {
        assertEquals(8, StateColors.KNOWN_STATES.size)
        for (state in StateColors.KNOWN_STATES) {
            // A state listed as known must not fall through to another
            // state's colours by accident: re-check via the table size
            // of distinct mappings for the lifecycle states.
            StateColors.forState(state)
        }
        val distinct = StateColors.KNOWN_STATES.map { StateColors.forState(it) }.distinct()
        // CREATED slate and EXPIRED grey differ, so all 8 are distinct.
        assertEquals(8, distinct.size)
    }

    @Test
    fun `unknown states fall back to the CREATED slate`() {
        val created = StateColors.forState("CREATED")
        assertEquals(created, StateColors.forState("SOME_FUTURE_STATE"))
        assertEquals(created, StateColors.forState(""))
        assertEquals(created, StateColors.forState("created"))
    }
}
