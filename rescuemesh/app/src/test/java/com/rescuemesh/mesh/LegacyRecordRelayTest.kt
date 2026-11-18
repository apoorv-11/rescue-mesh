package com.rescuemesh.mesh

import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.InMemoryMetricsDao
import com.rescuemesh.PairedFakeTransport
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.awaitCondition
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.pairedTransports
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Legacy-record relay fix: Phase-1-era records (UUID originDeviceId, empty
 * signature, empty hopTrace — created before identity/signing existed) sit
 * in upgraded phones' Room DBs. They can never pass the §4.5 receive
 * checks, so the relay must (a) never advertise or send them, (b) treat a
 * deterministic rejection as terminal REJECTED instead of an endless
 * backoff/re-offer loop, and (c) leave the records themselves untouched —
 * they are the user's history, just not relayable.
 */
class LegacyRecordRelayTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }

        /** The exact legacy shape from the user's device screenshot (14 Nov). */
        private const val LEGACY_ORIGIN_UUID = "76dd0314-01c6-4fde-97d2-c19fbe6dc714"
        private const val LEGACY_MESSAGE_ID = "db3e3295-bc63-4ef3-853a-c11547c92ebf"
    }

    private fun fastConfig() = RelayConfig(
        purgeIntervalMs = 60_000,
        ackTimeoutMs = 2_000,
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

    /** Transports start before engines (same order as RelayEngineTest). */
    private suspend fun startPair(
        ea: RelayEngine, ta: PairedFakeTransport, a: TestIdentity,
        eb: RelayEngine, tb: PairedFakeTransport, b: TestIdentity
    ) {
        ta.start(a.label)
        tb.start(b.label)
        ea.start()
        eb.start()
    }

    /** A Phase-1 record: pre-identity origin, never signed, no hop trace, NOT expired. */
    private fun legacyCheckin(now: Long): MessageEntity = MessageEntity(
        messageId = LEGACY_MESSAGE_ID,
        eventType = "SAFETY_CHECKIN",
        originDeviceId = LEGACY_ORIGIN_UUID,
        createdAt = now - 60_000,
        expiresAt = now + 12 * 3_600_000L, // SAFETY_CHECKIN TTL is 12 h — the loop is not expiry
        priority = 3,
        hopLimit = 8,
        hopCount = 0,
        hopTrace = "[]",
        payload = "{\"status\":\"SAFE\"}",
        signature = "",
        localState = "QUEUED",
        isOwn = true
    )

    private fun hellosOf(transport: PairedFakeTransport): List<RelayPacket.Hello> =
        transport.sentPayloads.mapNotNull { PacketCodec.decode(it.second) as? RelayPacket.Hello }

    private fun msgSendsOf(transport: PairedFakeTransport, messageId: String): Int =
        transport.sentPayloads.count {
            (PacketCodec.decode(it.second) as? RelayPacket.Msg)?.envelope?.messageId == messageId
        }

    private fun doneCount(transport: PairedFakeTransport): Int =
        transport.sentPayloads.count { PacketCodec.decode(it.second) is RelayPacket.Done }

    @Test
    fun `legacy record is excluded from HELLO inventory and never sent`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val metricsA = InMemoryMetricsDao()
        val now = System.currentTimeMillis()

        // One valid current-build record (repository funnel: entry 0 + signature).
        val repository = MessageRepository(
            messageDao = daoA,
            signer = a.envelopeSigner,
            hopSigner = a.hopSigner,
            factory = MessageFactory(nowMs = { now }, newId = { "checkin-valid-1" })
        )
        val valid = repository.sendSafetyCheckin(a.deviceId)
        assertTrue(RelayRecordValidation.isRelayEligible(valid))
        // …and the legacy record beside it.
        val legacy = legacyCheckin(now)
        daoA.insertIfAbsent(legacy)
        assertFalse(RelayRecordValidation.isRelayEligible(legacy))
        assertEquals("missing-entry0", RelayRecordValidation.deterministicInvalidReason(legacy))

        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA, metricsA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            // The session runs to completion: B stores the valid record and
            // both sides send DONE (durable transcript evidence).
            assertTrue("B should store the valid record", awaitCondition { daoB.getById(valid.messageId) != null })
            assertTrue(
                "session should complete (DONE sent by both sides)",
                awaitCondition { doneCount(ta) >= 1 && doneCount(tb) >= 1 }
            )
        } finally {
            ea.stop(); eb.stop()
        }
        // HELLO never advertised the legacy id; the valid one was advertised.
        val hellos = hellosOf(ta)
        assertTrue("A should have sent a HELLO", hellos.isNotEmpty())
        assertTrue(hellos.all { LEGACY_MESSAGE_ID !in it.inventory })
        assertTrue(hellos.any { valid.messageId in it.inventory })
        // The legacy record was never sent, never stored by B, and produced
        // no relay metric spam on A.
        assertEquals(0, msgSendsOf(ta, LEGACY_MESSAGE_ID))
        assertNull(daoB.getById(LEGACY_MESSAGE_ID))
        assertTrue(metricsA.rows.none { it.messageId == LEGACY_MESSAGE_ID })
        // …and it is still on A, untouched.
        assertNotNull(daoA.getById(LEGACY_MESSAGE_ID))
    }

    @Test
    fun `deterministic rejection marks the record REJECTED and a second session does not re-offer it`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val metricsA = InMemoryMetricsDao()
        val now = System.currentTimeMillis()

        // A record that PASSES sender eligibility (real entry 0, non-empty
        // signature) but whose signature does not verify — the shape a peer
        // rejects deterministically with "bad-signature".
        val factory = MessageFactory(nowMs = { now }, newId = { "checkin-poisoned-1" })
        val draft = factory.createSafetyCheckin(a.deviceId)
        val entry0 = a.hopSigner.originEntry(draft.messageId, draft.createdAt)
        val poisoned = draft.copy(
            hopTrace = HopTrace.traceToJson(listOf(entry0)),
            signature = DeviceIds.base64UrlEncode(ByteArray(EnvelopeSigner.SIGNATURE_BYTES)),
            localState = "QUEUED"
        )
        assertTrue("fixture must pass eligibility", RelayRecordValidation.isRelayEligible(poisoned))
        assertEquals("bad-signature", RelayRecordValidation.deterministicInvalidReason(poisoned))
        daoA.insertIfAbsent(poisoned)

        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA, metricsA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "A should mark the record REJECTED after the deterministic rejection",
                awaitCondition { daoA.getById(poisoned.messageId)?.localState == "REJECTED" }
            )
            assertTrue(
                "session 1 should complete (DONE sent by both sides)",
                awaitCondition { doneCount(ta) >= 1 && doneCount(tb) >= 1 }
            )
            // Let the DONE/DONE disconnect fully settle before reusing the
            // stores: session 2 must start from a drained session 1.
            assertTrue(
                "session 1 should disconnect after DONE/DONE",
                awaitCondition { ta.peers.value.none { it.connected } && tb.peers.value.none { it.connected } }
            )
        } finally {
            ea.stop(); eb.stop()
        }
        // Exactly ONE send attempt (no backoff retries), B never stored it,
        // and A logged exactly one terminal metric for it.
        assertEquals(1, msgSendsOf(ta, poisoned.messageId))
        assertNull(daoB.getById(poisoned.messageId))
        val terminalRows = metricsA.rows.filter { it.messageId == poisoned.messageId }
        assertEquals(1, terminalRows.size)
        assertEquals("rejected:bad-signature", terminalRows.single().detail)
        assertFalse(terminalRows.single().success)

        // Second session — fresh engines over a fresh link, same stores
        // (the suite's A→B→C leg-2 pattern; reusing the session-1 fakes
        // would replay session-1 packets into the new engines' collectors,
        // a harness artifact a real link never has). The REJECTED record
        // must not be re-offered and the metric count must stay flat.
        val (ta2, tb2) = pairedTransports(a.label, b.label)
        val ea2 = engineFor(a, ta2, daoA, metricsA)
        val eb2 = engineFor(b, tb2, daoB)
        startPair(ea2, ta2, a, eb2, tb2, b)
        try {
            assertTrue(
                "session 2 should complete (DONE sent by both sides)",
                awaitCondition { doneCount(ta2) >= 1 && doneCount(tb2) >= 1 }
            )
        } finally {
            ea2.stop(); eb2.stop()
        }
        assertEquals("REJECTED", daoA.getById(poisoned.messageId)?.localState)
        assertEquals(0, msgSendsOf(ta2, poisoned.messageId))
        assertEquals(1, metricsA.rows.count { it.messageId == poisoned.messageId })
    }

    @Test
    fun `normal current-build record still relays A to B and is stored with hopCount 1`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val repository = MessageRepository(
            messageDao = daoA,
            signer = a.envelopeSigner,
            hopSigner = a.hopSigner,
            factory = MessageFactory(nowMs = { now }, newId = { "checkin-normal-1" })
        )
        val created = repository.sendSafetyCheckin(a.deviceId)
        assertEquals(0, created.hopCount)

        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue("B should store the record", awaitCondition { daoB.getById(created.messageId) != null })
            assertTrue(
                "A should mark PEER_ACKED",
                awaitCondition { daoA.getById(created.messageId)?.localState == "PEER_ACKED" }
            )
        } finally {
            ea.stop(); eb.stop()
        }
        val stored = daoB.getById(created.messageId)!!
        assertEquals(1, stored.hopCount)
        val trace = HopTrace.parseTrace(stored.hopTrace)!!
        assertEquals(listOf(HopTrace.ROLE_ORIGIN, HopTrace.ROLE_RELAY), trace.map { it.role })
        assertTrue(EnvelopeSigner.verifyEnvelope(stored, a.rawPublicKey))
        assertEquals("QUEUED", stored.localState)
    }

    @Test
    fun `legacy record remains stored and readable from the repository`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val legacy = legacyCheckin(now)
        daoA.insertIfAbsent(legacy)

        // A full session runs (both DONE): the engine must neither delete
        // nor terminally re-state the legacy record.
        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "session should complete (DONE sent by both sides)",
                awaitCondition { doneCount(ta) >= 1 && doneCount(tb) >= 1 }
            )
        } finally {
            ea.stop(); eb.stop()
        }
        assertEquals(1, daoA.count())
        val held = daoA.getById(LEGACY_MESSAGE_ID)
        assertNotNull(held)
        assertEquals("QUEUED", held!!.localState)

        // Readable through the repository, exactly as the phone's lists show it.
        val repository = MessageRepository(
            messageDao = daoA,
            signer = a.envelopeSigner,
            hopSigner = a.hopSigner
        )
        val all = repository.observeMessages().first()
        assertTrue(all.any { it.messageId == LEGACY_MESSAGE_ID })
        assertEquals(1, repository.count())
    }
}
