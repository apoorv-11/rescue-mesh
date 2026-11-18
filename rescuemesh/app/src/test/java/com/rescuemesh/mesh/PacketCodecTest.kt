package com.rescuemesh.mesh

import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.domain.MessageFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Packet codec (batch contracts §4): HELLO / MSG / ACK / DONE round-trips.
 * MSG must carry the envelope with hopTrace/payload as JSON values, so a
 * decode → encode cycle is byte-stable and a stored record survives intact.
 */
class PacketCodecTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val alice = TestIdentity()
    private val bob = TestIdentity()
    private val fixedNow = 1_700_000_000_000L
    private val factory = MessageFactory(nowMs = { fixedNow }, newId = { "msg-1" })

    private fun signedSosWithTrace(): MessageEntity {
        val draft = factory.createSos(alice.deviceId, "MEDICAL")
        val entry0 = alice.hopSigner.originEntry(draft.messageId, draft.createdAt)
        val entry1 = bob.hopSigner.appendEntry(draft.messageId, listOf(entry0), HopTrace.ROLE_RELAY, fixedNow + 60_000)
        val traced = draft.copy(
            hopTrace = HopTrace.traceToJson(listOf(entry0, entry1)),
            hopCount = 1
        )
        return alice.envelopeSigner.signedCopy(traced)
    }

    @Test
    fun `HELLO round-trips with inventory and server acks`() {
        val ack = ServerAck("m-9", "ACCEPTED", issuedAt = fixedNow, signature = "c2ln")
        val hello = RelayPacket.Hello(
            deviceId = alice.deviceId,
            publicKey = alice.hopSigner.publicKeyBase64Url,
            gateway = true,
            inventory = listOf("m-1", "m-2"),
            serverAcked = listOf(ack)
        )
        val decoded = PacketCodec.decode(PacketCodec.encode(hello))
        assertEquals(hello, decoded)
    }

    @Test
    fun `MSG round-trips an envelope with hop entries intact`() {
        val envelope = signedSosWithTrace()
        val decoded = PacketCodec.decode(PacketCodec.encode(RelayPacket.Msg(envelope)))
        assertTrue(decoded is RelayPacket.Msg)
        val back = (decoded as RelayPacket.Msg).envelope
        assertEquals(envelope.messageId, back.messageId)
        assertEquals(envelope.eventType, back.eventType)
        assertEquals(envelope.originDeviceId, back.originDeviceId)
        assertEquals(envelope.createdAt, back.createdAt)
        assertEquals(envelope.expiresAt, back.expiresAt)
        assertEquals(envelope.priority, back.priority)
        assertEquals(envelope.hopLimit, back.hopLimit)
        assertEquals(envelope.hopCount, back.hopCount)
        assertEquals(envelope.signature, back.signature)
        assertEquals(
            HopTrace.parseTrace(envelope.hopTrace),
            HopTrace.parseTrace(back.hopTrace)
        )
        // Byte-stable: decoding and re-encoding yields identical packet bytes.
        assertTrue(
            PacketCodec.encode(RelayPacket.Msg(envelope))
                .contentEquals(PacketCodec.encode(RelayPacket.Msg(back)))
        )
    }

    @Test
    fun `ACK and DONE round-trip`() {
        assertEquals(
            RelayPacket.Ack("m-1", PacketCodec.ACK_STORED),
            PacketCodec.decode(PacketCodec.encode(RelayPacket.Ack("m-1", PacketCodec.ACK_STORED)))
        )
        assertEquals(
            RelayPacket.Ack("m-1", PacketCodec.ACK_DUPLICATE),
            PacketCodec.decode(PacketCodec.encode(RelayPacket.Ack("m-1", PacketCodec.ACK_DUPLICATE)))
        )
        assertEquals(
            RelayPacket.Ack("m-1", PacketCodec.ACK_REJECTED),
            PacketCodec.decode(PacketCodec.encode(RelayPacket.Ack("m-1", PacketCodec.ACK_REJECTED)))
        )
        assertEquals(RelayPacket.Done, PacketCodec.decode(PacketCodec.encode(RelayPacket.Done)))
    }

    @Test
    fun `malformed packets decode to null`() {
        assertNull(PacketCodec.decode("not json".toByteArray()))
        assertNull(PacketCodec.decode("""{"type":"WAT"}""".toByteArray()))
        assertNull(PacketCodec.decode("""{"type":"ACK","messageId":"m-1"}""".toByteArray())) // no status
        assertNull(PacketCodec.decode("""{"nope":1}""".toByteArray()))
        assertNotNull(PacketCodec.decode(PacketCodec.encode(RelayPacket.Done)))
    }

    @Test
    fun `MSG structural violations are rejected at decode`() {
        val envelope = signedSosWithTrace()
        val goodMap = PacketCodec.envelopeToMap(envelope)
        fun decodeWith(map: Map<String, Any?>): RelayPacket? =
            PacketCodec.decode(
                com.rescuemesh.crypto.CanonicalJson.canonicalBytes(
                    linkedMapOf<String, Any?>("type" to "MSG", "envelope" to map)
                )
            )
        assertNotNull(decodeWith(goodMap))
        assertNull(decodeWith(goodMap - "signature")) // missing field
        assertNull(decodeWith(goodMap + ("priority" to 9L))) // priority out of 1..3
        assertNull(decodeWith(goodMap + ("hopCount" to 99L))) // hopCount > hopLimit
        assertNull(decodeWith(goodMap + ("createdAt" to "yesterday"))) // non-integer time
        assertNull(decodeWith(goodMap + ("payload" to listOf(1L)))) // payload not an object
        assertNull(decodeWith(goodMap + ("hopTrace" to "none"))) // hopTrace not an array
    }
}
