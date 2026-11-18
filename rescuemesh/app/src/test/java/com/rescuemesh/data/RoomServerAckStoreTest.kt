package com.rescuemesh.data

import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import com.rescuemesh.InMemoryServerAckDao
import com.rescuemesh.InMemoryServerKeyCache
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.data.repository.RoomServerAckStore
import com.rescuemesh.mesh.ServerAck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The Prompt 9 fulfilment of the relay engine's ServerAckStore seam:
 * acks verify against the CACHED server public key over the canonical
 * {issuedAt, messageId, status} set, and only verified acks are stored
 * (so HELLO gossip from this phone only ever carries real server acks).
 */
class RoomServerAckStoreTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val server = TestIdentity()
    private val impostor = TestIdentity()
    private val issuedAt = 1_700_000_500_000L

    private fun ackSignedBy(identity: TestIdentity, messageId: String = "msg-1"): ServerAck {
        val signer = identity.keysetHandle.getPrimitive(
            RegistryConfiguration.get(), PublicKeySign::class.java
        )
        val bytes = CanonicalJson.canonicalBytes(
            linkedMapOf<String, Any?>(
                "issuedAt" to issuedAt,
                "messageId" to messageId,
                "status" to "ACCEPTED"
            )
        )
        return ServerAck(messageId, "ACCEPTED", issuedAt, DeviceIds.base64UrlEncode(signer.sign(bytes)))
    }

    private fun storeWithServerKey(): Pair<RoomServerAckStore, InMemoryServerAckDao> {
        val dao = InMemoryServerAckDao()
        val cache = InMemoryServerKeyCache()
        cache.save(server.deviceId.take(16), DeviceIds.base64UrlEncode(server.rawPublicKey))
        return RoomServerAckStore(dao, cache) to dao
    }

    @Test
    fun `record and verify round trip with the cached server key`() = runBlocking {
        val (store, dao) = storeWithServerKey()
        val ack = ackSignedBy(server)
        assertTrue(store.isAckVerified(ack))
        store.recordAck(ack)
        assertEquals(listOf(ack), store.allAcks())
        assertEquals(ack.signature, dao.getById("msg-1")?.signature)
    }

    @Test
    fun `an ack signed by the wrong key neither verifies nor is stored`() = runBlocking {
        val (store, _) = storeWithServerKey()
        val ack = ackSignedBy(impostor)
        assertFalse(store.isAckVerified(ack))
        store.recordAck(ack)
        assertTrue(store.allAcks().isEmpty())
    }

    @Test
    fun `without a cached server key nothing verifies`() = runBlocking {
        val store = RoomServerAckStore(InMemoryServerAckDao(), InMemoryServerKeyCache())
        assertFalse(store.isAckVerified(ackSignedBy(server)))
    }

    @Test
    fun `a tampered ack does not verify`() = runBlocking {
        val (store, _) = storeWithServerKey()
        val ack = ackSignedBy(server)
        assertFalse(store.isAckVerified(ack.copy(status = "ALREADY_EXISTS")))
        assertFalse(store.isAckVerified(ack.copy(issuedAt = issuedAt + 1)))
        assertFalse(store.isAckVerified(ack.copy(signature = "AAAA")))
    }
}
