package com.rescuemesh.mesh

import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.InMemoryMetricsDao
import com.rescuemesh.InMemoryPhotoDeliveryDao
import com.rescuemesh.PairedFakeTransport
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.awaitCondition
import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.data.repository.RoomPhotoDeliveryStore
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PhotoPreview
import com.rescuemesh.domain.PhotoPreviewSource
import com.rescuemesh.domain.PhotoRef
import com.rescuemesh.domain.PhotoStore
import com.rescuemesh.domain.PhotoThumb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Photo-transfer design, mesh half (14 Nov 2026): full photos (up to the
 * 500 KB cap) travel A→B in PHOTO_CHUNKs after the record queue, are
 * assembled + hash-verified by the receiver, resend only their missing
 * chunks, are never promoted when corrupt, and store-and-forward
 * A→B→C through a verified holder. The record itself never waits for
 * its photo — every test also watches the record's own relay state.
 */
class PhotoChunkRelayTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String): PhotoStore =
        PhotoStore(File(tmp.newFolder(name), "files"))

    private fun fastConfig(ttlTestMode: Boolean = false) = RelayConfig(
        purgeIntervalMs = 60_000,
        ackTimeoutMs = 2_000,
        ttlTestMode = ttlTestMode,
        backoffBaseMs = 10,
        cooldownMs = 30_000,
        photoAckTimeoutMs = 5_000,
        maxPhotoRounds = 3
    )

    private fun engineFor(
        identity: TestIdentity,
        transport: MeshTransport,
        dao: InMemoryMessageDao,
        store: PhotoStore?,
        ttlTestMode: Boolean = false,
        deliveries: PhotoDeliveryStore = NoOpPhotoDeliveryStore(),
        config: RelayConfig = fastConfig(ttlTestMode)
    ) = RelayEngine(
        transport = transport,
        messageDao = dao,
        metricsDao = InMemoryMetricsDao(),
        hopSigner = identity.hopSigner,
        ownDeviceId = identity.deviceId,
        ownPublicKey = identity.rawPublicKey,
        photoStore = store,
        photoDeliveryStore = deliveries,
        config = config
    )

    /** A [PhotoDeliveryStore] that only records — the engine's receipt callback, observed. */
    private class RecordingPhotoDeliveryStore(
        private val delegate: PhotoDeliveryStore = NoOpPhotoDeliveryStore()
    ) : PhotoDeliveryStore {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Long>>()

        override suspend fun recordPhotoDelivery(
            messageId: String,
            peerDeviceId: String,
            deliveredAtMs: Long
        ) {
            calls += Triple(messageId, peerDeviceId, deliveredAtMs)
            delegate.recordPhotoDelivery(messageId, peerDeviceId, deliveredAtMs)
        }
    }

    private fun repositoryFor(
        identity: TestIdentity,
        dao: InMemoryMessageDao,
        store: PhotoStore?,
        nowMs: () -> Long = { System.currentTimeMillis() },
        factory: MessageFactory = MessageFactory()
    ) = MessageRepository(
        messageDao = dao,
        signer = identity.envelopeSigner,
        hopSigner = identity.hopSigner,
        factory = factory,
        nowMs = nowMs,
        photoStore = store
    )

    /** A paired transport whose sends can be filtered: modify bytes, or swallow (null). */
    private class FilteringTransport(fakePeers: List<MeshPeer>) : PairedFakeTransport(fakePeers) {
        var filter: (ByteArray) -> ByteArray? = { it }

        override suspend fun send(endpointId: String, bytes: ByteArray): SendResult {
            val peer = peers.value.firstOrNull { it.endpointId == endpointId }
            if (peer?.connected != true) {
                return SendResult(endpointId, 0, success = false, error = "PEER_NOT_CONNECTED")
            }
            val out = filter(bytes) ?: return SendResult(endpointId, 1, success = true)
            return super.send(endpointId, out)
        }
    }

    private fun filteringPair(
        labelA: String, labelB: String
    ): Pair<FilteringTransport, FilteringTransport> {
        val a = FilteringTransport(listOf(MeshPeer("ep-b", labelB, false)))
        val b = FilteringTransport(listOf(MeshPeer("ep-a", labelA, false)))
        a.myEndpointOnLink = "ep-a"
        b.myEndpointOnLink = "ep-b"
        a.link = b
        b.link = a
        return a to b
    }

    private suspend fun startPair(
        ea: RelayEngine, ta: FakeMeshTransport, a: TestIdentity,
        eb: RelayEngine, tb: FakeMeshTransport, b: TestIdentity
    ) {
        ta.start(a.label)
        tb.start(b.label)
        ea.start()
        eb.start()
    }

    private fun thumb(seed: Int): String =
        DeviceIds.base64UrlEncode(Random(seed).nextBytes(3_000))

    private fun photoChunksSentBy(transport: FakeMeshTransport): List<RelayPacket.PhotoChunk> =
        transport.sentPayloads.mapNotNull { PacketCodec.decode(it.second) as? RelayPacket.PhotoChunk }

    private fun photoAcksSentBy(transport: FakeMeshTransport): List<RelayPacket.PhotoAck> =
        transport.sentPayloads.mapNotNull { PacketCodec.decode(it.second) as? RelayPacket.PhotoAck }

    @Test
    fun `photo packets round-trip and a full-size chunk fits the packet guard`() {
        val slice = Random(3).nextBytes(PacketCodec.PHOTO_CHUNK_BYTES)
        val chunk = RelayPacket.PhotoChunk("id-1", 5, 32, DeviceIds.base64UrlEncode(slice))
        val encoded = PacketCodec.encode(chunk)
        assertTrue("a 16 KiB chunk must fit the 30 000-byte guard", encoded.size <= 30_000)
        assertEquals(chunk, PacketCodec.decode(encoded))

        val end = RelayPacket.PhotoChunk("id-1", PacketCodec.PHOTO_END_INDEX, 32, null)
        val decodedEnd = PacketCodec.decode(PacketCodec.encode(end)) as? RelayPacket.PhotoChunk
        assertNotNull(decodedEnd)
        assertTrue(decodedEnd!!.isEndMarker)
        assertNull(decodedEnd.data)

        val ack = RelayPacket.PhotoAck("id-1", PacketCodec.PHOTO_MISSING, listOf(3, 7, 31))
        assertEquals(ack, PacketCodec.decode(PacketCodec.encode(ack)))

        assertEquals(32, PacketCodec.photoChunkCount(512_000))
        assertEquals(1, PacketCodec.photoChunkCount(1))

        // A HELLO from a pre-photo build has no `photos` key at all.
        val legacyHello = CanonicalJson.canonicalBytes(
            mapOf(
                "type" to "HELLO",
                "deviceId" to "device-x",
                "publicKey" to "key-x",
                "gateway" to false,
                "inventory" to emptyList<String>(),
                "serverAcked" to emptyList<Any>()
            )
        )
        val hello = PacketCodec.decode(legacyHello) as? RelayPacket.Hello
        assertNotNull(hello)
        assertTrue(hello!!.photos.isEmpty())
    }

    @Test
    fun `500 KB photo relays A to B byte-identical and the record never waits for it`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(42).nextBytes(512_000) // exactly the 500 KB cap

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            age = 27,
            description = "Blue jacket, last seen near the river bridge",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(1),
            fullPhotoJpeg = photo
        )
        assertTrue(storeA.hasComplete(record.messageId))
        val ref = PhotoRef.parse(record.payload)!!
        assertEquals(record.messageId, ref.photoId)
        assertEquals(512_000, ref.sizeBytes)
        assertEquals(PhotoStore.sha256Hex(photo), ref.sha256)

        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "the record itself relays and settles (PEER_ACKED) on its own ACK",
                awaitCondition(30_000) { daoA.getById(record.messageId)?.localState == "PEER_ACKED" }
            )
            assertTrue(
                "B ends up holding the complete photo",
                awaitCondition(30_000) { storeB.hasComplete(record.messageId) }
            )
            assertTrue(
                "the photo on B is byte-identical to A's",
                photo.contentEquals(storeB.readComplete(record.messageId)!!)
            )
            assertEquals(
                "B's stored payload carries the same signed ref",
                ref, PhotoRef.parse(daoB.getById(record.messageId)!!.payload)
            )
            assertTrue("no parts are left behind after assembly", storeB.partIndices(record.messageId).isEmpty())
            val dataChunks = photoChunksSentBy(ta).filter { !it.isEndMarker }
            assertEquals("512 000 bytes at 16 KiB per chunk", 32, dataChunks.size)
            assertTrue(
                "every photo packet respects the 30 000-byte guard",
                ta.sentPayloads.all { it.second.size <= 30_000 }
            )
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `a dropped chunk is named in MISSING, resent, and the photo completes`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(7).nextBytes(200_000) // 13 chunks

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(2),
            fullPhotoJpeg = photo
        )

        val (ta, tb) = filteringPair(a.label, b.label)
        val droppedOnce = AtomicBoolean(false)
        ta.filter = { bytes ->
            val packet = PacketCodec.decode(bytes)
            if (packet is RelayPacket.PhotoChunk && !packet.isEndMarker && packet.index == 7 &&
                droppedOnce.compareAndSet(false, true)
            ) {
                null // swallowed once, as a lost Nearby payload would be
            } else {
                bytes
            }
        }
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B completes the photo after the MISSING resend round",
                awaitCondition(30_000) { storeB.hasComplete(record.messageId) }
            )
            assertTrue(photo.contentEquals(storeB.readComplete(record.messageId)!!))
            assertTrue(
                "B's PHOTO_ACK named exactly the dropped chunk as missing",
                photoAcksSentBy(tb).any {
                    it.status == PacketCodec.PHOTO_MISSING && it.missing == listOf(7)
                }
            )
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `a persistently corrupted chunk is never promoted to a complete photo`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(11).nextBytes(100_000) // 7 chunks

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(3),
            fullPhotoJpeg = photo
        )

        val (ta, tb) = filteringPair(a.label, b.label)
        ta.filter = { bytes ->
            val packet = PacketCodec.decode(bytes)
            if (packet is RelayPacket.PhotoChunk && !packet.isEndMarker && packet.index == 3 &&
                packet.data != null
            ) {
                // Flip one byte inside chunk 3's slice, every single round.
                val slice = DeviceIds.base64UrlDecode(packet.data)
                slice[0] = (slice[0] + 1).toByte()
                PacketCodec.encode(packet.copy(data = DeviceIds.base64UrlEncode(slice)))
            } else {
                bytes
            }
        }
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "the record itself still relays fine — photo failure never blocks it",
                awaitCondition(30_000) { daoB.getById(record.messageId) != null }
            )
            assertTrue(
                "all 3 rounds run and every assembly refuses the corrupt photo",
                awaitCondition(30_000) {
                    photoChunksSentBy(ta).count { it.isEndMarker } >= 3 &&
                        photoAcksSentBy(tb).count { it.status == PacketCodec.PHOTO_MISSING } >= 3
                }
            )
            assertFalse("corrupt bytes are never promoted", storeB.hasComplete(record.messageId))
            assertNull(storeB.readComplete(record.messageId))
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `store and forward — B verifies then carries the photo onward to C`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val c = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val daoC = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val storeC = store("c")
        val now = System.currentTimeMillis()
        val photo = Random(21).nextBytes(300_000)

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(4),
            fullPhotoJpeg = photo
        )

        // Leg 1: A -> B (C is nowhere near).
        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B receives and verifies the photo",
                awaitCondition(30_000) { storeB.hasComplete(record.messageId) }
            )
        } finally {
            ea.stop(); eb.stop()
        }

        // Leg 2: B -> C later, A long gone. B's HELLO advertises the photo
        // (it is a verified holder), and C ends byte-identical.
        val (tb2, tc) = filteringPair(b.label, c.label)
        val eb2 = engineFor(b, tb2, daoB, storeB)
        val ec = engineFor(c, tc, daoC, storeC)
        startPair(eb2, tb2, b, ec, tc, c)
        try {
            assertTrue(
                "C receives the record from B",
                awaitCondition(30_000) { daoC.getById(record.messageId) != null }
            )
            assertTrue(
                "C receives the complete photo, forwarded by B",
                awaitCondition(30_000) { storeC.hasComplete(record.messageId) }
            )
            assertTrue(photo.contentEquals(storeC.readComplete(record.messageId)!!))
            assertTrue(
                "B advertised the photo in its leg-2 HELLO",
                tb2.sentPayloads.any { (_, bytes) ->
                    (PacketCodec.decode(bytes) as? RelayPacket.Hello)
                        ?.photos?.contains(record.messageId) == true
                }
            )
        } finally {
            eb2.stop(); ec.stop()
        }
    }

    @Test
    fun `a found updates photo relays on the same path`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(31).nextBytes(150_000)

        // The case lives on A; B files the Found update WITH a photo of
        // the found person — its photoRef points at the Found record's
        // own id, not at the case.
        val missing = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(5)
        )
        val found = repositoryFor(b, daoB, storeB).reportPersonFound(
            originDeviceId = b.deviceId,
            caseId = missing.messageId,
            note = "Found safe at the relief camp",
            photoThumbB64 = thumb(6),
            fullPhotoJpeg = photo
        )
        val foundRef = PhotoRef.parse(found.payload)!!
        assertEquals(found.messageId, foundRef.photoId)

        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "A stores the Found update",
                awaitCondition(30_000) { daoA.getById(found.messageId) != null }
            )
            assertTrue(
                "A receives the Found record's complete photo",
                awaitCondition(30_000) { storeA.hasComplete(found.messageId) }
            )
            assertTrue(photo.contentEquals(storeA.readComplete(found.messageId)!!))
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `TTL purge deletes the complete photo and in-flight parts with the record`() = runBlocking {
        val a = TestIdentity()
        val daoA = InMemoryMessageDao()
        val storeA = store("a")
        val old = System.currentTimeMillis() - 120_000 // TTL test mode: dead after 60 s

        val record = repositoryFor(
            a, daoA, storeA,
            nowMs = { old },
            factory = MessageFactory(nowMs = { old })
        ).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = old - 3_600_000,
            photoThumbB64 = thumb(7),
            fullPhotoJpeg = Random(51).nextBytes(50_000)
        )
        assertTrue(storeA.hasComplete(record.messageId))
        storeA.writePart(record.messageId, 0, Random(52).nextBytes(100))
        assertTrue(storeA.partIndices(record.messageId).isNotEmpty())

        val engine = engineFor(a, FakeMeshTransport(), daoA, storeA, ttlTestMode = true)
        engine.runPurgeOnce()

        assertEquals("EXPIRED", daoA.getById(record.messageId)?.localState)
        assertFalse("the complete photo dies with the record", storeA.hasComplete(record.messageId))
        assertTrue("in-flight parts die with the record", storeA.partIndices(record.messageId).isEmpty())
        assertFalse(storeA.partsDir(record.messageId).exists())
    }

    @Test
    fun `a record without any photo relays exactly as before, with no photo traffic`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()

        val record = repositoryFor(
            a, daoA, storeA,
            factory = MessageFactory(nowMs = { now }, newId = { "plain-missing-1" })
        ).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(8)
        )
        assertNull(PhotoRef.parse(record.payload))

        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B stores the photo-less record",
                awaitCondition(30_000) { daoB.getById(record.messageId) != null }
            )
            assertTrue(
                "A marks it PEER_ACKED",
                awaitCondition(30_000) { daoA.getById(record.messageId)?.localState == "PEER_ACKED" }
            )
            assertTrue(
                "session completes",
                awaitCondition(30_000) {
                    ta.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                }
            )
            assertTrue(photoChunksSentBy(ta).isEmpty())
            assertTrue(photoChunksSentBy(tb).isEmpty())
            val helloA = ta.sentPayloads
                .mapNotNull { PacketCodec.decode(it.second) as? RelayPacket.Hello }
                .first()
            assertTrue(helloA.photos.isEmpty())
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `a COMPLETE photo ack records exactly one delivery receipt per record and peer`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b1")
        val now = System.currentTimeMillis()
        val photo = Random(61).nextBytes(200_000)

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(9),
            fullPhotoJpeg = photo
        )

        // The production stack, observed: engine callback → recording
        // decorator → the real RoomPhotoDeliveryStore over an in-memory
        // DAO. The durable receipt (what the card reads) must exist
        // exactly once per (record, peer) however often delivery is
        // re-confirmed.
        val deliveryDao = InMemoryPhotoDeliveryDao()
        val deliveries = RecordingPhotoDeliveryStore(RoomPhotoDeliveryStore(deliveryDao))

        // Leg 1: A -> B over a fresh pair (the store-and-forward test's
        // per-leg pattern — fresh transports keep the fake's incoming
        // replay from leaking one leg's packets into the next).
        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA, deliveries = deliveries)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B completes the photo in leg 1",
                awaitCondition(30_000) { storeB.hasComplete(record.messageId) }
            )
            assertTrue(
                "the COMPLETE ack becomes a receipt: (record, B, a real timestamp)",
                awaitCondition(30_000) { deliveries.calls.size == 1 }
            )
            val (messageId, peerDeviceId, deliveredAt) = deliveries.calls.single()
            assertEquals(record.messageId, messageId)
            assertEquals(b.deviceId, peerDeviceId)
            assertTrue(deliveredAt > 0L)
            assertEquals(1, deliveryDao.countForMessage(record.messageId))
            assertEquals(deliveredAt, deliveryDao.getForMessage(record.messageId).single().deliveredAt)
        } finally {
            ea.stop(); eb.stop()
        }

        // Leg 2: B kept the record but LOST its photo files (fresh
        // PhotoStore over the same DAO), so the photo is transferred
        // and confirmed COMPLETE a second time. The engine callback
        // fires again (a new engine instance has no memory of leg 1),
        // but the durable receipt must NOT duplicate — and the first
        // confirmed time must stand.
        val storeB2 = store("b2")
        val (ta2, tb2) = filteringPair(a.label, b.label)
        val ea2 = engineFor(a, ta2, daoA, storeA, deliveries = deliveries)
        val eb2 = engineFor(b, tb2, daoB, storeB2)
        startPair(ea2, ta2, a, eb2, tb2, b)
        try {
            assertTrue(
                "B re-acquires the complete photo in leg 2",
                awaitCondition(30_000) { storeB2.hasComplete(record.messageId) }
            )
            assertTrue(
                "B really did confirm COMPLETE a second time (the dedupe is not vacuous)",
                awaitCondition(30_000) {
                    photoAcksSentBy(tb2).any { it.status == PacketCodec.PHOTO_COMPLETE }
                }
            )
            assertTrue(
                "A's leg-2 send loop ran to DONE, so its ack handling is finished",
                awaitCondition(30_000) {
                    ta2.sentPayloads.any { PacketCodec.decode(it.second) is RelayPacket.Done }
                }
            )
            assertEquals(
                "the callback fired once per leg — twice in total",
                2, deliveries.calls.size
            )
            assertEquals(
                "but the durable receipt for the pair is still exactly one row",
                1, deliveryDao.countForMessage(record.messageId)
            )
            assertEquals(
                "and it still carries the FIRST delivery time",
                deliveries.calls.first().third,
                deliveryDao.getForMessage(record.messageId).single().deliveredAt
            )
        } finally {
            ea2.stop(); eb2.stop()
        }
    }

    @Test
    fun `no delivery receipt is recorded when the photo never completes`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(67).nextBytes(100_000) // 7 chunks

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Asha Verma",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = thumb(10),
            fullPhotoJpeg = photo
        )

        val deliveries = RecordingPhotoDeliveryStore()
        val (ta, tb) = filteringPair(a.label, b.label)
        ta.filter = { bytes ->
            val packet = PacketCodec.decode(bytes)
            if (packet is RelayPacket.PhotoChunk && !packet.isEndMarker && packet.index == 3 &&
                packet.data != null
            ) {
                val slice = DeviceIds.base64UrlDecode(packet.data)
                slice[0] = (slice[0] + 1).toByte()
                PacketCodec.encode(packet.copy(data = DeviceIds.base64UrlEncode(slice)))
            } else {
                bytes
            }
        }
        val ea = engineFor(a, ta, daoA, storeA, deliveries = deliveries)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "all 3 rounds run and every assembly refuses the corrupt photo",
                awaitCondition(30_000) {
                    photoChunksSentBy(ta).count { it.isEndMarker } >= 3 &&
                        photoAcksSentBy(tb).count { it.status == PacketCodec.PHOTO_MISSING } >= 3
                }
            )
            assertTrue(
                "B never once answered COMPLETE",
                photoAcksSentBy(tb).none { it.status == PacketCodec.PHOTO_COMPLETE }
            )
            assertTrue(
                "MISSING acks never record a delivery receipt",
                deliveries.calls.isEmpty()
            )
        } finally {
            ea.stop(); eb.stop()
        }
    }

    @Test
    fun `card inputs after the fix — thumbnail and held photo give both phones a preview`() = runBlocking {
        val a = TestIdentity()
        val b = TestIdentity()
        val daoA = InMemoryMessageDao()
        val daoB = InMemoryMessageDao()
        val storeA = store("a")
        val storeB = store("b")
        val now = System.currentTimeMillis()
        val photo = Random(71).nextBytes(150_000)

        // The creation pipeline after the fit-loop fix: the detailed
        // photo's first thumbnail candidate busts the 12 000-char cap
        // (the device defect) and the chooser steps down to one that
        // fits — that chosen string is what the form puts in the payload.
        val oversizeCandidate = "a".repeat(PhotoThumb.MAX_ENCODED_CHARS + 500)
        val fittingCandidate = thumb(11)
        val chosenThumb = PhotoThumb.chooseThumbnail(listOf(oversizeCandidate, fittingCandidate))
        assertEquals(fittingCandidate, chosenThumb)

        val record = repositoryFor(a, daoA, storeA).reportMissingPerson(
            originDeviceId = a.deviceId,
            name = "Ishika Avasthi",
            description = "Detailed photo, thumbnail needed the fit ladder",
            lastSeenArea = "River bridge",
            lastSeenAtMs = now - 3_600_000,
            photoThumbB64 = chosenThumb,
            photoOmitted = false,
            fullPhotoJpeg = photo
        )

        fun payloadThumb(payloadJson: String): String? {
            val payload = JsonParser.parse(payloadJson) as? Map<*, *> ?: return null
            return payload["photoThumb"] as? String
        }

        // Origin card inputs (before any relay): payload thumbnail
        // present AND the complete photo held → a picture shows.
        assertEquals(chosenThumb, payloadThumb(record.payload))
        assertEquals(
            PhotoPreviewSource.THUMBNAIL,
            PhotoPreview.choose(
                hasThumbnail = payloadThumb(record.payload) != null,
                hasCompletePhoto = storeA.hasComplete(record.messageId),
                photoOmitted = false
            )
        )

        val deliveries = RecordingPhotoDeliveryStore()
        val (ta, tb) = filteringPair(a.label, b.label)
        val ea = engineFor(a, ta, daoA, storeA, deliveries = deliveries)
        val eb = engineFor(b, tb, daoB, storeB)
        startPair(ea, ta, a, eb, tb, b)
        try {
            assertTrue(
                "B ends up holding the complete photo",
                awaitCondition(30_000) { storeB.hasComplete(record.messageId) }
            )
            // Receiver card inputs: the thumbnail travelled INSIDE the
            // signed payload, and the complete photo arrived in chunks —
            // B's card has a picture too, not just a "saved" line.
            val storedOnB = daoB.getById(record.messageId)!!
            assertEquals(chosenThumb, payloadThumb(storedOnB.payload))
            assertEquals(
                PhotoPreviewSource.THUMBNAIL,
                PhotoPreview.choose(
                    hasThumbnail = payloadThumb(storedOnB.payload) != null,
                    hasCompletePhoto = storeB.hasComplete(record.messageId),
                    photoOmitted = false
                )
            )
            // And the fallback leg of the same card: had this record's
            // payload carried NO thumbnail, B's held photo still yields
            // a preview (the FULL_PHOTO branch) — never a blank card.
            assertEquals(
                PhotoPreviewSource.FULL_PHOTO,
                PhotoPreview.choose(
                    hasThumbnail = false,
                    hasCompletePhoto = storeB.hasComplete(record.messageId),
                    photoOmitted = false
                )
            )
            // Sender-side visibility: A's engine recorded the delivery,
            // which is what lights up "Photo delivered ✓" on A's card.
            assertTrue(
                "A recorded B's photo delivery",
                awaitCondition(30_000) {
                    deliveries.calls.any {
                        it.first == record.messageId && it.second == b.deviceId
                    }
                }
            )
        } finally {
            ea.stop(); eb.stop()
        }
    }
}
