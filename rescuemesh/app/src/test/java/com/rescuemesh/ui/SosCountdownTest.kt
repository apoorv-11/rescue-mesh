package com.rescuemesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry-screen SOS countdown numerals: 3, 2, 1 across the three
 * seconds, then null (fire) at exactly the duration — the overlay and
 * the AppViewModel job share this one definition of "3 seconds".
 */
class SosCountdownTest {

    @Test
    fun `numerals count down 3 2 1 across the three seconds`() {
        assertEquals(3, SosCountdown.secondsRemaining(0L))
        assertEquals(3, SosCountdown.secondsRemaining(999L))
        assertEquals(2, SosCountdown.secondsRemaining(1_000L))
        assertEquals(2, SosCountdown.secondsRemaining(1_999L))
        assertEquals(1, SosCountdown.secondsRemaining(2_000L))
        assertEquals(1, SosCountdown.secondsRemaining(2_999L))
    }

    @Test
    fun `at and past the duration there is no numeral left`() {
        assertNull(SosCountdown.secondsRemaining(3_000L))
        assertNull(SosCountdown.secondsRemaining(10_000L))
        assertTrue(SosCountdown.isFinished(3_000L))
        assertFalse(SosCountdown.isFinished(2_999L))
    }

    @Test
    fun `negative elapsed still shows 3`() {
        assertEquals(3, SosCountdown.secondsRemaining(-500L))
        assertFalse(SosCountdown.isFinished(-500L))
    }

    @Test
    fun `duration is three seconds`() {
        assertEquals(3_000L, SosCountdown.DURATION_MS)
    }
}
