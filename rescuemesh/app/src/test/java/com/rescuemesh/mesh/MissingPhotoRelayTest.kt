package com.rescuemesh.mesh

import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.InMemoryMetricsDao
import com.rescuemesh.PairedFakeTransport
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.awaitCondition
import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.domain.MessageFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random

/**
 * TG-4 regression suite (device test, 14 Nov 2026): a Missing-person
 * record carrying a photo thumbnail was the one record that never
 * settled on real phones, and its photo rendered nowhere.
 *
 * What these tests pin down:
 * - the wire path itself is sound at the maximum thumbnail size (the
 *   device failure lived in NearbyTransport's lossy incoming hand-off,
 *   which the fakes never modelled — fixed there; these tests keep the
 *   engine/codec honest at this payload size);
 * - NOTHING in the relay path matches on the person's name or on the
 *   Found update's caseId: a Found update that references a queued
 *   Missing record (caseId == the Missing record's messageId, same
 *   person's name) can neither consume the Missing record's delivery
 *   nor settle its state — only the Missing record's own ACK can;
 * - two Missing records for the same name are two records: both relay,
 *   both store (dedup is by messageId only).
 */
class MissingPhotoRelayTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private fun fastConfig() = RelayConfig(
        purgeIntervalMs = 60_000,
        ackTimeoutMs = 2_000,
        ttlTestMode = false,
        backoffBaseMs = 10,
        cooldownMs = 30_000
    )

    private fun engineFor(
        identity: TestIdentity,
        transport: MeshTransport,
        dao: InMemoryMessageDao,
        metrics: InMemoryMetricsDao = InMemoryMetricsDao()
    ) = RelayEngine(
        transport = transport,
        messageDao = dao,
        metricsDao = metrics,
        hopSigner = identity.hopSigner,
        ownDeviceId = identity.deviceId,
        ownPublicKey = identity.rawPublicKey,
        config = fastConfig()
    )

    private fun repositoryFor(identity: TestIdentity, dao: InMemoryMessageDao, factory: MessageFactory) =
        MessageRepository(
            messageDao = dao,
            signer = identity.envelopeSigner,
            hopSigner = identity.hopSigner,
            factory = factory
        )

    private suspend fun startPair(
        ea: RelayEngine, ta: PairedFakeTransport, a: TestIdentity,
        eb: RelayEngine, tb: PairedFakeTransport, b: TestIdentity
    ) {
        ta.start(a.label)
        tb.start(b.label)
        ea.start()
        eb.start()
    }

    /** A PhotoThumb-shaped string: the project's real encoder over deterministic bytes. */
    private fun thumb(seed: Int, byteCount: Int = 8_900): String =
        DeviceIds.base64UrlEncode(Random(seed).nextBytes(byteCount))

    private suspend fun createMissing(
        identity: TestIdentity,
        dao: InMemoryMessageDao,
        id: String,
        now: Long,
        name: String?,
        photoThumbB64: String?
    ): MessageEntity = repositoryFor(
        identity, dao, MessageFactory(nowMs = { now }, newId = { id })
    ).reportMissingPerson(
        originDeviceId = identity.deviceId,
        name = name,
        age = 27,
        description = "Blue jacket, last seen near the river bridge",
        lastSeenArea = "River bridge",
        lastSeenAtMs = now - 3_600_000,
        photoThumbB64 = photoThumbB64,
        photoOmitted = photoThumbB64 == null
    )

    private fun payloadOf(entity: MessageEntity): Map<*, *> =
        JsonParser.parse(entity.payload) as Map<*, *>

    private fun msgIdsSentBy(transport: PairedFakeTransport): List<String> =
        transport.sentPayloads.mapNotNull { (_, bytes) ->
            (PacketCodec.decode(bytes) as? RelayPacket.Msg)?.envelope?.messageId
        }

    @Test
    fun `missing person with max-size thumbnail relays A to B and A marks PEER_ACKED`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val thumb = thumb(seed = 42)
        // The §7 shape: Base64url, no padding, no line breaks, within the cap.
        assertTrue(thumb.length <= 12_000)
        assertTrue(thumb.none { it == '\n' || it == '+' || it == '/' || it == '=' })

        val record = createMissing(a, daoA, "missing-thumb-1", now, "Asha Verma", thumb)
        assertTrue(
            "wire packet must fit the 30 000-byte guard",
            PacketCodec.encode(RelayPacket.Msg(record)).size <= 30_000
        )

        val (ta, tb) = com.rescuemesh.pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue("B should store the Missing record", awaitCondition(30_000) { daoB.getById(record.messageId) != null })
            val storedByB = daoB.getById(record.messageId)!!
            assertEquals(thumb, payloadOf(storedByB)["photoThumb"])
            assertEquals(
                CanonicalJson.canonicalize(JsonParser.parse(record.payload)),
                storedByB.payload
            )
            assertEquals(1, storedByB.hopCount)
            assertTrue(
                "A should mark the Missing record PEER_ACKED",
                awaitCondition(30_000) { daoA.getById(record.messageId)?.localState == "PEER_ACKED" }
            )
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `found update for the same case never consumes or settles the missing record`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val thumb = thumb(seed = 7)

        // A creates the Missing report; it sits in A's queue (no session yet).
        val missing = createMissing(a, daoA, "missing-case-1", now, "Asha Verma", thumb)
        // B creates the Found update for that very case: its payload caseId
        // IS the Missing record's messageId, and it is the same person's name.
        val found = repositoryFor(
            b, daoB, MessageFactory(nowMs = { now + 1_000 }, newId = { "found-case-1" })
        ).reportPersonFound(
            originDeviceId = b.deviceId,
            caseId = missing.messageId,
            note = "Asha Verma found safe at the relief camp"
        )
        assertEquals(missing.messageId, payloadOf(found)["caseId"])

        // Session 1: the Found travels B -> A and the Missing travels
        // A -> B in the same session, as on the devices.
        val (ta, tb) = com.rescuemesh.pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue("A should store the Found update", awaitCondition(30_000) { daoA.getById(found.messageId) != null })
            assertTrue("B should store the Missing record", awaitCondition(30_000) { daoB.getById(missing.messageId) != null })
            // Each record is settled ONLY by its own ACK:
            assertTrue(
                "the Missing record is settled by its own ACK, not by the Found's",
                awaitCondition(30_000) { daoA.getById(missing.messageId)?.localState == "PEER_ACKED" }
            )
            assertTrue(
                "the Found update is settled by its own ACK",
                awaitCondition(30_000) { daoB.getById(found.messageId)?.localState == "PEER_ACKED" }
            )
            assertEquals(thumb, payloadOf(daoB.getById(missing.messageId)!!)["photoThumb"])
            assertEquals(2, daoA.count())
            assertEquals(2, daoB.count())
        } finally {
            ea.stop(); eb.stop()
        }

        // Session 2 (fresh link, same stores): both peers' HELLO inventories
        // now hold both ids, so neither record is offered again, and no
        // state moves. If anything anywhere matched on caseId or name,
        // this is where the Missing record would be re-sent or re-settled.
        val (ta2, tb2) = com.rescuemesh.pairedTransports(a.label, b.label)
        val ea2 = engineFor(a, ta2, daoA)
        val eb2 = engineFor(b, tb2, daoB)
        startPair(ea2, ta2, a, eb2, tb2, b)
        try {
            assertTrue(
                "session 2 should complete (DONE sent by both sides)",
                awaitCondition(30_000) {
                    ta2.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done } &&
                        tb2.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                }
            )
            assertTrue(msgIdsSentBy(ta2).isEmpty())
            assertTrue(msgIdsSentBy(tb2).isEmpty())
            assertEquals("PEER_ACKED", daoA.getById(missing.messageId)?.localState)
            assertEquals("PEER_ACKED", daoB.getById(found.messageId)?.localState)
            assertEquals(2, daoA.count())
            assertEquals(2, daoB.count())
        } finally {
            ea2.stop(); eb2.stop()
        }
    }

    @Test
    fun `two missing persons with the same name both relay and both store`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val thumb1 = thumb(seed = 1, byteCount = 3_000)
        val thumb2 = thumb(seed = 2, byteCount = 3_000)
        assertTrue(thumb1 != thumb2)

        val first = createMissing(a, daoA, "missing-same-name-1", now, "Asha Verma", thumb1)
        val second = createMissing(a, daoA, "missing-same-name-2", now + 1_000, "Asha Verma", thumb2)

        val (ta, tb) = com.rescuemesh.pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B should store BOTH same-name records (dedup is by messageId only)",
                awaitCondition(30_000) { daoB.count() == 2 }
            )
            assertEquals(thumb1, payloadOf(daoB.getById(first.messageId)!!)["photoThumb"])
            assertEquals(thumb2, payloadOf(daoB.getById(second.messageId)!!)["photoThumb"])
            assertNotNull(daoB.getById(first.messageId))
            assertNotNull(daoB.getById(second.messageId))
            assertTrue(awaitCondition(30_000) { daoA.getById(first.messageId)?.localState == "PEER_ACKED" })
            assertTrue(awaitCondition(30_000) { daoA.getById(second.messageId)?.localState == "PEER_ACKED" })
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `codec round trip preserves standard-alphabet base64 characters in the payload`() {
        val identity = TestIdentity()
        val dao = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        // A thumbnail string in the STANDARD Base64 alphabet (+, /, =):
        // not what this app produces, but the wire codec must carry any
        // payload string byte-identically regardless of alphabet.
        val standardThumb = "ab+cd/ef+gh/ij=="
        val record = runBlocking {
            createMissing(identity, dao, "missing-std-b64", now, null, standardThumb)
        }
        val decoded = PacketCodec.decode(PacketCodec.encode(RelayPacket.Msg(record))) as? RelayPacket.Msg
        assertNotNull(decoded)
        assertEquals(standardThumb, payloadOf(decoded!!.envelope)["photoThumb"])
        assertEquals(
            CanonicalJson.canonicalize(JsonParser.parse(record.payload)),
            decoded.envelope.payload
        )
    }
}
