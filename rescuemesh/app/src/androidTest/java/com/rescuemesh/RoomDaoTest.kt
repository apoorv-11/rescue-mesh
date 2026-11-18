package com.rescuemesh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescuemesh.data.local.ProfileEntity
import com.rescuemesh.data.local.RescueMeshDatabase
import com.rescuemesh.domain.MessageFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the Phase 1 persistence contract against a real Room database:
 * profile, SOS and I Am Safe are written to Room, dedup holds, and a file-backed
 * database keeps its rows when it is closed and reopened (the restart test —
 * an in-memory database cannot prove that).
 */
@RunWith(AndroidJUnit4::class)
class RoomDaoTest {

    private lateinit var context: Context
    private lateinit var db: RescueMeshDatabase
    private val factory = MessageFactory()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(RescueMeshDatabase.DATABASE_NAME)
        db = RescueMeshDatabase.create(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(RescueMeshDatabase.DATABASE_NAME)
    }

    @Test
    fun profile_sos_and_safe_survive_close_and_reopen() = runBlocking {
        db.profileDao().upsert(
            ProfileEntity(displayName = "Test User", role = "CITIZEN", deviceId = "device-a", createdAt = 1L)
        )
        val sos = factory.createSos("device-a", "MEDICAL")
        val safe = factory.createSafetyCheckin("device-a")
        assertTrue(db.messageDao().insertIfAbsent(sos) != -1L)
        assertTrue(db.messageDao().insertIfAbsent(safe) != -1L)

        // Simulate an app restart: close the file-backed DB and open it again.
        db.close()
        val reopened = RescueMeshDatabase.create(context)
        try {
            assertNotNull(reopened.profileDao().getProfile())
            assertEquals(2, reopened.messageDao().count())
            val rows = reopened.messageDao().observeAll().first()
            assertTrue(rows.any { it.eventType == "SOS" && it.priority == 1 })
            assertTrue(rows.any { it.eventType == "SAFETY_CHECKIN" })
        } finally {
            reopened.close()
        }
        // Re-create for tearDown's close.
        db = RescueMeshDatabase.create(context)
        val dbFile = File(context.getDatabasePath(RescueMeshDatabase.DATABASE_NAME).path)
        assertTrue(dbFile.exists())
    }

    @Test
    fun duplicate_messageId_is_stored_once() = runBlocking {
        val sos = factory.createSos("device-a")
        assertTrue(db.messageDao().insertIfAbsent(sos) != -1L)
        assertEquals(-1L, db.messageDao().insertIfAbsent(sos)) // FR-06 dedup
        assertEquals(1, db.messageDao().count())
    }
}
