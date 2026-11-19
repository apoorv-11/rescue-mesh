package com.rescuemesh.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX5 periodic sync policy: the 15-minute cadence is WorkManager's
 * minimum — the pure due-check pins it so a later edit cannot
 * silently shorten it (and drain the battery) or lengthen it (and
 * leave queued records unsynced for hours).
 */
class SyncWorkTest {

    @Test
    fun `interval is WorkManager's 15-minute minimum`() {
        assertEquals(15L, SyncWork.REPEAT_INTERVAL_MINUTES)
        assertEquals("rescue-mesh-gateway-sync", SyncWork.UNIQUE_WORK_NAME)
    }

    @Test
    fun `never run is due, a fresh run is not`() {
        val now = 1_700_000_000_000L
        assertTrue(SyncWork.isDue(null, now))
        assertFalse(SyncWork.isDue(now, now))
        assertFalse(SyncWork.isDue(now - 14 * 60_000L, now))
    }

    @Test
    fun `a run 15 minutes ago is due again`() {
        val now = 1_700_000_000_000L
        assertTrue(SyncWork.isDue(now - 15 * 60_000L, now))
        assertTrue(SyncWork.isDue(now - 60 * 60_000L, now))
    }
}
