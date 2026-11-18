package com.rescuemesh.data

import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.repository.MessageCreationResult
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.domain.MessageFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Prompt 5 creation funnel: every record is created draft → hop entry 0
 * → signed → stored, and SOS creation is rate-guarded (contracts §4.9:
 * at most 5 new SOS per device per rolling 60 s, refused — never thrown).
 */
class MessageRepositoryTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val alice = TestIdentity()
    private var clock = 1_700_000_000_000L
    private var idCounter = 0

    private fun newRepository(dao: InMemoryMessageDao): MessageRepository {
        val factory = MessageFactory(
            nowMs = { clock },
            newId = { "00000000-0000-4000-8000-%012d".format(++idCounter) }
        )
        return MessageRepository(
            messageDao = dao,
            signer = alice.envelopeSigner,
            hopSigner = alice.hopSigner,
            factory = factory,
            nowMs = { clock }
        )
    }

    @Test
    fun `funnel attaches entry 0, signs, and stores`() = runBlocking {
        val dao = InMemoryMessageDao()
        val repo = newRepository(dao)
        val result = repo.sendSos(alice.deviceId, "MEDICAL")
        assertTrue(result is MessageCreationResult.Created)
        val stored = dao.getById((result as MessageCreationResult.Created).message.messageId)!!
        // Entry 0: ORIGIN, at = createdAt, prevSig "", hopCount stays 0.
        val trace = HopTrace.parseTrace(stored.hopTrace)!!
        assertEquals(1, trace.size)
        assertEquals(HopTrace.ROLE_ORIGIN, trace[0].role)
        assertEquals(stored.createdAt, trace[0].at)
        assertEquals("", trace[0].prevSig)
        assertEquals(0, stored.hopCount)
        assertEquals(listOf(true), HopTrace.validateTrace(trace, stored.messageId, alice.deviceId))
        // The envelope signature verifies against Alice's public key.
        assertTrue(EnvelopeSigner.verifyEnvelope(stored, alice.rawPublicKey))
        assertEquals("CREATED", stored.localState)
        assertTrue(stored.isOwn)
    }

    @Test
    fun `safety checkin goes through the same funnel`() = runBlocking {
        val dao = InMemoryMessageDao()
        val repo = newRepository(dao)
        val stored = repo.sendSafetyCheckin(alice.deviceId)
        assertEquals(1, HopTrace.parseTrace(stored.hopTrace)?.size)
        assertTrue(EnvelopeSigner.verifyEnvelope(stored, alice.rawPublicKey))
    }

    @Test
    fun `sixth SOS inside 60 s is refused, and allowed again after the window`() = runBlocking {
        val dao = InMemoryMessageDao()
        val repo = newRepository(dao)
        repeat(MessageRepository.MAX_SOS_PER_WINDOW) {
            val result = repo.sendSos(alice.deviceId)
            assertTrue("SOS $it must be created", result is MessageCreationResult.Created)
            clock += 1_000
        }
        val refused = repo.sendSos(alice.deviceId)
        assertTrue(refused is MessageCreationResult.Refused)
        assertEquals(
            MessageRepository.SOS_GUARD_MESSAGE,
            (refused as MessageCreationResult.Refused).reason
        )
        assertEquals(MessageRepository.MAX_SOS_PER_WINDOW, dao.count())
        // Rolling window: once the oldest SOS is more than 60 s old, creation works again.
        clock += MessageRepository.SOS_GUARD_WINDOW_MS
        val after = repo.sendSos(alice.deviceId)
        assertTrue(after is MessageCreationResult.Created)
    }

    @Test
    fun `the guard counts only SOS - safety checkins are never refused`() = runBlocking {
        val dao = InMemoryMessageDao()
        val repo = newRepository(dao)
        repeat(MessageRepository.MAX_SOS_PER_WINDOW + 3) {
            repo.sendSafetyCheckin(alice.deviceId)
        }
        val result = repo.sendSos(alice.deviceId)
        assertTrue(result is MessageCreationResult.Created)
    }
}
