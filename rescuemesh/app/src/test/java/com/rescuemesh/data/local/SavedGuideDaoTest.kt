package com.rescuemesh.data.local

import com.rescuemesh.InMemorySavedGuideDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prepare Saved (Prompt 10): Room persistence for saved guide ids —
 * the list must survive an app restart, so it is a Room table, not
 * in-memory state. The in-memory DAO mirrors the Room semantics
 * (REPLACE upserts, delete by id).
 */
class SavedGuideDaoTest {

    @Test
    fun `save, count and unsave round-trip`() = runBlocking {
        val dao = InMemorySavedGuideDao()
        assertEquals(0, dao.countForGuide("flood"))
        dao.upsert(SavedGuideEntity("flood", 1000L))
        assertEquals(1, dao.countForGuide("flood"))
        // Re-saving refreshes the time but stays one row.
        dao.upsert(SavedGuideEntity("flood", 2000L))
        assertEquals(1, dao.countForGuide("flood"))
        dao.deleteById("flood")
        assertEquals(0, dao.countForGuide("flood"))
    }
}
