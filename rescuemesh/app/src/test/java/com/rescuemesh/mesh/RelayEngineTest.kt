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
import com.rescuemesh.data.repository.MessageCreationResult
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.pairedTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * RelayEngine end-to-end on paired FakeMeshTransports (batch contracts §4).
 * Each "phone" is a real Tink identity + in-memory DAOs + its own engine;
 * the paired fakes deliver bytes to each other like two Nearby phones.
 */
class RelayEngineTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private fun fastConfig(
        ackTimeoutMs: Long = 2_000,
        ttlTestMode: Boolean = false,
        purgeIntervalMs: Long = 60_000
    ) = RelayConfig(
        purgeIntervalMs = purgeIntervalMs,
        ackTimeoutMs = ackTimeoutMs,
        ttlTestMode = ttlTestMode,
        backoffBaseMs = 10,
        cooldownMs = 30_000
    )

    private fun engineFor(
        identity: TestIdentity,
        transport: MeshTransport,
        dao: InMemoryMessageDao,
        metrics: InMemoryMetricsDao = InMemoryMetricsDao(),
        ackStore: ServerAckStore = NoOpServerAckStore(),
        config: RelayConfig = fastConfig()
    ) = RelayEngine(
        transport = transport,
        messageDao = dao,
        metricsDao = metrics,
        hopSigner = identity.hopSigner,
        ownDeviceId = identity.deviceId,
        ownPublicKey = identity.rawPublicKey,
        serverAckStore = ackStore,
        config = config
    )

    private fun repositoryFor(identity: TestIdentity, dao: InMemoryMessageDao, factory: MessageFactory) =
        MessageRepository(
            messageDao = dao,
            signer = identity.envelopeSigner,
            hopSigner = identity.hopSigner,
            factory = factory
        )

    /**
     * Starts both transports BEFORE both engines: a connect flip that lands
     * while the peer transport is still starting would be wiped by its
     * start() (a real Nearby peer cannot connect before we advertise, so
     * the fake pair is driven in the realistic order).
     */
    private suspend fun startPair(
        ea: RelayEngine, ta: PairedFakeTransport, a: TestIdentity,
        eb: RelayEngine, tb: PairedFakeTransport, b: TestIdentity
    ) {
        ta.start(a.label)
        tb.start(b.label)
        ea.start()
        eb.start()
    }

    private suspend fun createSos(
        identity: TestIdentity,
        dao: InMemoryMessageDao,
        factory: MessageFactory,
        category: String? = null
    ): MessageEntity {
        val result = repositoryFor(identity, dao, factory).sendSos(identity.deviceId, category)
        assertTrue(result is MessageCreationResult.Created)
        return (result as MessageCreationResult.Created).message
    }

    @Test
    fun `initiator rule - the smaller advertised label connects, the other only accepts`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, InMemoryMessageDao())
        val eb = engineFor(b, tb, InMemoryMessageDao())
        startPair(ea, ta, a, eb, tb, b)
        try {
            val (smallerTransport, largerTransport) = if (a.label < b.label) ta to tb else tb to ta
            // A no-record session can complete (DONE/DONE -> disconnect)
            // faster than a state poll, so assert on the durable evidence:
            // exactly one connect call, from the smaller label, and a HELLO
            // exchanged by both sides (HELLO is only sent on connect).
            assertTrue(awaitCondition { smallerTransport.connectCalls.isNotEmpty() })
            assertTrue(awaitCondition {
                ta.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Hello } &&
                    tb.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Hello }
            })
            assertEquals(1, smallerTransport.connectCalls.size)
            assertEquals(0, largerTransport.connectCalls.size)
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `cooldown - the initiator does not re-dial within the cooldown window after a completed session`() = runBlocking {
        // Regression test for the session-end race: the peers collector can
        // observe the post-DONE disconnect before the incoming collector has
        // processed the peer's DONE, so the cooldown must already exist at
        // disconnect time — otherwise the initiator re-dials in the very
        // same emission and connectCalls becomes 2. Repeated over fresh
        // pairs because the race is an interleaving, not a certainty.
        repeat(3) {
            val a = TestIdentity()
            val b = TestIdentity()
            val (ta, tb) = pairedTransports(a.label, b.label)
            val ea = engineFor(a, ta, InMemoryMessageDao())
            val eb = engineFor(b, tb, InMemoryMessageDao())
            startPair(ea, ta, a, eb, tb, b)
            try {
                val (smallerTransport, largerTransport) = if (a.label < b.label) ta to tb else tb to ta
                assertTrue(awaitCondition { smallerTransport.connectCalls.size == 1 })
                assertTrue(
                    "session should complete (DONE sent by both sides)",
                    awaitCondition {
                        ta.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done } &&
                            tb.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                    }
                )
                assertTrue(
                    "both sides should disconnect after DONE/DONE",
                    awaitCondition { ta.peers.value.none { it.connected } && tb.peers.value.none { it.connected } }
                )
                // The disconnect emission is exactly where a buggy engine
                // re-dials (synchronously, inside the peers collector), so a
                // bounded condition wait for a second dial is conclusive:
                // post-fix no dial can occur until the 30 s cooldown ends.
                assertTrue(
                    "initiator must not re-dial within the cooldown window",
                    !awaitCondition(1_500) { smallerTransport.connectCalls.size > 1 }
                )
                assertEquals(1, smallerTransport.connectCalls.size)
                assertEquals(0, largerTransport.connectCalls.size)
            } finally {
                ea.stop(); eb.stop()
            }
        }
    }

    @Test
    fun `SOS relays A to B, B stores with a relay entry, A marks PEER_ACKED, then B relays onward to C`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val c = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val daoC = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val sos = createSos(a, daoA, MessageFactory(nowMs = { now }, newId = { "sos-a-1" }), "MEDICAL")

        // --- Session A-B ---
        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue("B should store the SOS", awaitCondition { daoB.getById(sos.messageId) != null })
            assertTrue("A should mark PEER_ACKED", awaitCondition { daoA.getById(sos.messageId)?.localState == "PEER_ACKED" })
            // Let the DONE/DONE handshake fully settle before the engines
            // stop and leg 2 reuses daoB: both sides' DONE in the sent log
            // is the durable evidence both send loops have finished.
            // (Polling the peers state for "both disconnected" is racy —
            // a fast re-dial can make that moment unobservable.)
            assertTrue(
                "session A-B should complete (DONE sent by both sides)",
                awaitCondition {
                    ta.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done } &&
                        tb.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                }
            )
        } finally {
            ea.stop(); eb.stop()
        }
        val storedByB = daoB.getById(sos.messageId)!!
        val traceB = HopTrace.parseTrace(storedByB.hopTrace)!!
        // B appended its RELAY entry before storing: ORIGIN + RELAY, hopCount 1.
        assertEquals(listOf(HopTrace.ROLE_ORIGIN, HopTrace.ROLE_RELAY), traceB.map { it.role })
        assertEquals(2, traceB.size)
        assertEquals(1, storedByB.hopCount)
        assertEquals(listOf(true, true), HopTrace.validateTrace(traceB, sos.messageId, a.deviceId))
        assertTrue(EnvelopeSigner.verifyEnvelope(storedByB, a.rawPublicKey))
        assertEquals("QUEUED", storedByB.localState)

        // --- Session B-C: B forwards its stored copy unchanged (§2) ---
        val (tb2, tc) = pairedTransports(b.label, c.label)
        val eb2 = engineFor(b, tb2, daoB)
        val ec = engineFor(c, tc, daoC)
        startPair(eb2, tb2, b, ec, tc, c)
        try {
            assertTrue("C should store the relayed SOS", awaitCondition { daoC.getById(sos.messageId) != null })
        } finally {
            eb2.stop(); ec.stop()
        }
        val storedByC = daoC.getById(sos.messageId)!!
        val traceC = HopTrace.parseTrace(storedByC.hopTrace)!!
        // C appends its own entry too: ORIGIN + RELAY(B) + RELAY(C), hopCount 2.
        assertEquals(
            listOf(HopTrace.ROLE_ORIGIN, HopTrace.ROLE_RELAY, HopTrace.ROLE_RELAY),
            traceC.map { it.role }
        )
        assertEquals(3, traceC.size)
        assertEquals(2, storedByC.hopCount)
        assertEquals(listOf(true, true, true), HopTrace.validateTrace(traceC, sos.messageId, a.deviceId))
        // After two relays the origin signature still verifies (the §1 point).
        assertTrue(EnvelopeSigner.verifyEnvelope(storedByC, a.rawPublicKey))
    }

    @Test
    fun `duplicate MSG gets ACK DUPLICATE and still one stored row`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val sos = createSos(a, daoA, MessageFactory(nowMs = { now }, newId = { "sos-dup-1" }))

        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(awaitCondition { daoB.getById(sos.messageId) != null })
            ea.stop() // A goes quiet; B's engine stays up.
            // Reconnect at transport level and re-send the same envelope by hand.
            ta.connect("ep-b")
            val msgBytes = PacketCodec.encode(RelayPacket.Msg(daoA.getById(sos.messageId)!!))
            ta.send("ep-b", msgBytes)
            assertTrue(
                "expected an ACK DUPLICATE from B",
                awaitCondition {
                    tb.sentPayloads.mapNotNull { PacketCodec.decode(it.second) as? RelayPacket.Ack }
                        .any { it.messageId == sos.messageId && it.status == PacketCodec.ACK_DUPLICATE }
                }
            )
            assertEquals(1, daoB.count())
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `queue order - priority 1 newer is sent before priority 2 older`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val t0 = System.currentTimeMillis()
        // Older priority-2 SOS first, then a newer priority-1 (MEDICAL) SOS.
        val olderP2 = createSos(a, daoA, MessageFactory(nowMs = { t0 }, newId = { "sos-p2-older" }), "OTHER")
        val newerP1 = createSos(a, daoA, MessageFactory(nowMs = { t0 + 10_000 }, newId = { "sos-p1-newer" }), "MEDICAL")
        assertEquals(2, olderP2.priority)
        assertEquals(1, newerP1.priority)

        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        val eb = engineFor(b, tb, daoB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(awaitCondition { daoB.count() == 2 })
            assertEquals(listOf(newerP1.messageId, olderP2.messageId), daoB.insertOrder)
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `TTL purge in test mode marks a 61 s old record EXPIRED and leaves SERVER_SYNCED alone`() = runBlocking {
        val a = TestIdentity()
        val dao = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val stale = MessageEntity(
            messageId = "stale-1", eventType = "SOS", originDeviceId = a.deviceId,
            createdAt = now - 61_000, expiresAt = now + 86_400_000, priority = 2,
            hopLimit = 8, hopCount = 0, hopTrace = "[]", payload = "{}", signature = "",
            localState = "CREATED", isOwn = true
        )
        val synced = stale.copy(messageId = "synced-1", localState = "SERVER_SYNCED")
        dao.insertIfAbsent(stale)
        dao.insertIfAbsent(synced)
        val transport = FakeMeshTransport(emptyList())
        val engine = engineFor(
            a, transport, dao,
            config = fastConfig(ttlTestMode = true, purgeIntervalMs = 100)
        )
        engine.start()
        try {
            assertTrue(awaitCondition(5_000) { dao.getById("stale-1")?.localState == "EXPIRED" })
            assertEquals("SERVER_SYNCED", dao.getById("synced-1")?.localState)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `retry cap - a peer that never ACKs leaves the record FAILED_RETRYABLE after 3 attempts`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val sos = createSos(a, daoA, MessageFactory(nowMs = { now }, newId = { "sos-retry-1" }))
        val (ta, tb) = pairedTransports(a.label, b.label)
        // B has NO engine: a test responder answers HELLO once, then silence.
        val responderScope = CoroutineScope(Dispatchers.Default)
        val helloAnswered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val responderSubscribed = kotlinx.coroutines.CompletableDeferred<Unit>()
        responderScope.launch {
            tb.incoming
                .onSubscription { responderSubscribed.complete(Unit) }
                .collect { payload ->
                val packet = PacketCodec.decode(payload.bytes)
                if (packet is RelayPacket.Hello && !helloAnswered.isCompleted) {
                    tb.send(
                        "ep-a",
                        PacketCodec.encode(
                            RelayPacket.Hello(
                                deviceId = b.deviceId,
                                publicKey = b.hopSigner.publicKeyBase64Url,
                                gateway = false,
                                inventory = emptyList(),
                                serverAcked = emptyList()
                            )
                        )
                    )
                    helloAnswered.complete(Unit)
                }
            }
        }
        val ea = engineFor(a, ta, daoA, config = fastConfig(ackTimeoutMs = 250))
        try {
            tb.start(b.label) // B's transport runs so A's sends land; no engine on B.
            ta.start(a.label)
            // Wait until the responder is actually subscribed to tb.incoming
            // before the engine sends any HELLO (condition-based: a fixed
            // sleep raced on a loaded machine and the first HELLO was missed).
            assertTrue(
                "responder should subscribe to tb.incoming",
                awaitCondition { responderSubscribed.isCompleted }
            )
            ea.start()
            // Make sure the pair is connected even if B's label is smaller
            // (A's engine only auto-connects when A is the initiator).
            if (a.label >= b.label) {
                assertTrue(awaitCondition { ta.peers.value.isNotEmpty() })
                ta.connect("ep-b")
            }
            assertTrue(
                "record should become FAILED_RETRYABLE",
                // The slowest legitimate path in the suite: 3 send attempts,
                // each bounded by an ack timeout plus backoff, all on
                // wall-clock timers — give it extra margin under load.
                awaitCondition(90_000) { daoA.getById(sos.messageId)?.localState == "FAILED_RETRYABLE" }
            )
            val msgSends = ta.sentPayloads.count {
                (PacketCodec.decode(it.second) as? RelayPacket.Msg)?.envelope?.messageId == sos.messageId
            }
            assertEquals(3, msgSends)
        } finally {
            ea.stop()
            responderScope.cancel()
        }
    }

    @Test
    fun `HELLO serverAcked - a verified gossiped ack marks the record SERVER_SYNCED and it is not relayed`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val now = System.currentTimeMillis()
        val sos = createSos(a, daoA, MessageFactory(nowMs = { now }, newId = { "sos-acked-1" }))
        val ack = ServerAck(sos.messageId, "ACCEPTED", issuedAt = now, signature = "c2ln")
        // B gossips the ack in its HELLO (allAcks); A verifies incoming acks
        // against ITS OWN store — here a fixed verifier standing in for A's
        // cached server public key (the Prompt 9 seam).
        val bStore = object : ServerAckStore {
            override suspend fun allAcks(): List<ServerAck> = listOf(ack)
            override suspend fun recordAck(ack: ServerAck) = Unit
            override suspend fun isAckVerified(ack: ServerAck): Boolean = false
        }
        val aStore = object : ServerAckStore {
            override suspend fun allAcks(): List<ServerAck> = emptyList()
            override suspend fun recordAck(ack: ServerAck) = Unit
            override suspend fun isAckVerified(ack: ServerAck): Boolean = ack.messageId == sos.messageId
        }
        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA, ackStore = aStore)
        val eb = engineFor(b, tb, daoB, ackStore = bStore)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "A should apply the verified ack",
                awaitCondition { daoA.getById(sos.messageId)?.localState == "SERVER_SYNCED" }
            )
            // No fixed soak to "give a buggy engine a chance": wait until
            // both sides have sent DONE — the durable transcript evidence
            // that each side's send loop for this session has run to
            // completion. (Observing "both disconnected" instead would be
            // racy: a peer whose disconnect is processed before its DONE
            // can be re-dialled within microseconds, so the disconnected
            // moment is practically unobservable.) A wrong send would have
            // preceded the sender's DONE in the same send loop, and
            // SERVER_SYNCED is a never-send state in any later session, so
            // once both DONEs are in the log the checks below are settled.
            assertTrue(
                "both sides should finish the session (DONE sent)",
                awaitCondition {
                    ta.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done } &&
                        tb.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                }
            )
            assertNull(daoB.getById(sos.messageId))
            val msgSent = ta.sentPayloads.any {
                (PacketCodec.decode(it.second) as? RelayPacket.Msg)?.envelope?.messageId == sos.messageId
            }
            assertTrue("SERVER_SYNCED record must never be relayed", !msgSent)
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `HELLO with a mismatched public key is ignored and the peer disconnected`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val impostor = TestIdentity()
        val daoA = InMemoryMessageDao()
        val (ta, tb) = pairedTransports(a.label, b.label)
        val ea = engineFor(a, ta, daoA)
        try {
            ta.start(a.label)
            tb.start(b.label)
            ea.start()
            assertTrue(awaitCondition { ta.peers.value.isNotEmpty() })
            if (a.label >= b.label) ta.connect("ep-b")
            assertTrue(awaitCondition { ta.peers.value.any { it.connected } })
            // Impostor HELLO: B's deviceId but someone else's key.
            tb.send(
                "ep-a",
                PacketCodec.encode(
                    RelayPacket.Hello(
                        deviceId = b.deviceId,
                        publicKey = impostor.hopSigner.publicKeyBase64Url,
                        gateway = false,
                        inventory = emptyList(),
                        serverAcked = emptyList()
                    )
                )
            )
            assertTrue(
                "A should disconnect the impostor peer",
                awaitCondition { ta.peers.value.none { it.connected } }
            )
            assertEquals(0, daoA.count())
        } finally {
            ea.stop()
        }
    }
}
