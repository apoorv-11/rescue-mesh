package com.rescuemesh.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.PredefinedSignatureParameters
import com.google.crypto.tink.signature.SignatureConfig
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.domain.MessageFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Sign -> verify round trips against a real Tink Ed25519 keyset created with
 * the NO_PREFIX parameters — the same variant production uses. If the variant
 * ever regresses to Tink's default, signatures come out 69 bytes (5-byte
 * prefix + 64) and the 64-byte assertion below fails immediately.
 */
class EnvelopeSignerTest {

    companion object {
        private lateinit var keysetHandle: KeysetHandle
        private lateinit var rawPublicKey: ByteArray
        private lateinit var originDeviceId: String
        private lateinit var signer: EnvelopeSigner

    private fun generateKeyset(): KeysetHandle = KeysetHandle.newBuilder()
        .addEntry(
            KeysetHandle.generateEntryFromParameters(PredefinedSignatureParameters.ED25519WithRawOutput)
                .withRandomId()
                .makePrimary()
        )
        .build()

        @JvmStatic
        @BeforeClass
        fun setUpKeyset() {
            SignatureConfig.register()
            keysetHandle = generateKeyset()
            rawPublicKey = EnvelopeSigner.rawPublicKeyBytes(keysetHandle)
            originDeviceId = DeviceIds.originDeviceId(rawPublicKey)
            signer = EnvelopeSigner.fromKeysetHandle(keysetHandle)
        }
    }

    private val fixedNow = 1_700_000_000_000L
    private var counter = 0
    private val factory = MessageFactory(
        nowMs = { fixedNow },
        newId = { "00000000-0000-4000-8000-%012d".format(++counter) }
    )

    private fun sos(): MessageEntity = factory.createSos(originDeviceId, "MEDICAL")

    @Test
    fun `extracted raw public key is 32 bytes and derives the origin id`() {
        assertEquals(32, rawPublicKey.size)
        assertTrue(DeviceIds.isValidOriginDeviceId(originDeviceId, rawPublicKey))
    }

    @Test
    fun `signature is exactly 64 bytes once Base64url-decoded`() {
        val signed = signer.signedCopy(sos())
        assertEquals(64, DeviceIds.base64UrlDecode(signed.signature).size)
        assertFalse(signed.signature.contains("=")) // no padding, ever
    }

    @Test
    fun `signed copy does not mutate the unsigned draft`() {
        val draft = sos()
        val signed = signer.signedCopy(draft)
        assertEquals("", draft.signature)
        assertTrue(signed.signature.isNotEmpty())
        assertEquals(draft.messageId, signed.messageId)
    }

    @Test
    fun `sign then verify round-trips for SOS and safety checkin`() {
        assertTrue(EnvelopeSigner.verifyEnvelope(signer.signedCopy(sos()), rawPublicKey))
        val safe = signer.signedCopy(factory.createSafetyCheckin(originDeviceId))
        assertTrue(EnvelopeSigner.verifyEnvelope(safe, rawPublicKey))
    }

    @Test
    fun `tampered payload fails verification`() {
        val signed = signer.signedCopy(sos())
        val tampered = signed.copy(payload = """{"category":"OTHER","description":"","peopleCount":1}""")
        assertFalse(EnvelopeSigner.verifyEnvelope(tampered, rawPublicKey))
    }

    @Test
    fun `swapped originDeviceId fails verification (id is inside the signed bytes)`() {
        val signed = signer.signedCopy(sos())
        val otherId = DeviceIds.originDeviceId(ByteArray(32) { 0x42 })
        assertFalse(EnvelopeSigner.verifyEnvelope(signed.copy(originDeviceId = otherId), rawPublicKey))
    }

    @Test
    fun `a different key cannot verify the signature`() {
        val otherHandle = generateKeyset()
        val otherRawKey = EnvelopeSigner.rawPublicKeyBytes(otherHandle)
        assertFalse(EnvelopeSigner.verifyEnvelope(signer.signedCopy(sos()), otherRawKey))
    }

    @Test
    fun `empty or garbage signatures fail cleanly, never throw`() {
        val draft = sos()
        assertFalse(EnvelopeSigner.verifyEnvelope(draft, rawPublicKey)) // signature = ""
        assertFalse(EnvelopeSigner.verifyEnvelope(draft.copy(signature = "!!!not-base64!!!"), rawPublicKey))
        assertFalse(EnvelopeSigner.verifyEnvelope(draft.copy(signature = "AAAA"), rawPublicKey)) // 3 bytes only
    }

    @Test
    fun `canonical bytes are deterministic for the same envelope`() {
        val message = sos()
        assertTrue(EnvelopeSigner.canonicalBytes(message).contentEquals(EnvelopeSigner.canonicalBytes(message)))
        // The signature field itself is excluded: signing must not change the signed bytes.
        val signed = signer.signedCopy(message)
        assertTrue(EnvelopeSigner.canonicalBytes(signed).contentEquals(EnvelopeSigner.canonicalBytes(message)))
    }

    @Test
    fun `canonical form of a factory SOS has the exact expected shape`() {
        // Batch contracts §1: the signed set is the 8 origin-fixed fields —
        // hopCount and hopTrace are NOT signed (relays mutate them).
        val message = factory.createSos(originDeviceId, "MEDICAL")
        val canonical = String(EnvelopeSigner.canonicalBytes(message), Charsets.UTF_8)
        assertEquals(
            """{"createdAt":1700000000000,"eventType":"SOS","expiresAt":1700086400000,""" +
                """"hopLimit":8,"messageId":"${message.messageId}",""" +
                """"originDeviceId":"$originDeviceId","payload":{"category":"MEDICAL",""" +
                """"description":"","peopleCount":1},"priority":1}""",
            canonical
        )
    }

    @Test
    fun `relay mutations (hopCount and hopTrace) do not break the origin signature`() {
        // The whole point of the §1 scope change: a relay appends hop
        // entries and bumps hopCount; the origin signature must still verify.
        val signed = signer.signedCopy(sos())
        val relayed = signed.copy(
            hopCount = 1,
            hopTrace = """[{"i":0,"role":"ORIGIN","deviceId":"$originDeviceId",""" +
                """"pk":"x","at":1700000000000,"prevSig":"","sig":"y"}]"""
        )
        assertTrue(EnvelopeSigner.verifyEnvelope(relayed, rawPublicKey))
    }

    // ---- Batch contracts §3: the fixed cross-side test vector (TEST ONLY) ----

    private val vectorPublicKey: ByteArray =
        DeviceIds.base64UrlDecode("A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg")
    private val vectorDeviceId = "56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c"
    private val vectorEntity = MessageEntity(
        messageId = "12345678-1234-4234-9234-1234567890ab",
        eventType = "SOS",
        originDeviceId = vectorDeviceId,
        createdAt = 1_700_000_000_000L,
        expiresAt = 1_700_086_400_000L,
        priority = 1,
        hopLimit = 8,
        hopCount = 0,
        hopTrace = """[{"i":0,"role":"ORIGIN","deviceId":"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c","pk":"A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg","at":1700000000000,"prevSig":"","sig":"que4YgpLPBO7pSi8H1_0qgeH5jx_G0FzKo6_l_DkYutQ03MvV39k7rAJtSO8zrSed7Vud2Q45mLIQcuWkxHeDg"}]""",
        payload = """{"category":"MEDICAL","description":"Trapped under debris","peopleCount":3,"injured":1}""",
        signature = "7BfTxpgGrAvUhFV7OGbpM_-ITV15MWjUAy3Hed6QraGldYNRk2YDWBlv1JkPc_hr3_c0IfQG1uSfmjUUPThrBQ",
        localState = "CREATED",
        isOwn = false
    )

    @Test
    fun `fixed vector - canonical bytes equal the contracts string exactly`() {
        assertEquals(vectorDeviceId, DeviceIds.originDeviceId(vectorPublicKey))
        val canonical = String(EnvelopeSigner.canonicalBytes(vectorEntity), Charsets.UTF_8)
        assertEquals(
            """{"createdAt":1700000000000,"eventType":"SOS","expiresAt":1700086400000,"hopLimit":8,"messageId":"12345678-1234-4234-9234-1234567890ab","originDeviceId":"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c","payload":{"category":"MEDICAL","description":"Trapped under debris","injured":1,"peopleCount":3},"priority":1}""",
            canonical
        )
    }

    @Test
    fun `fixed vector - envelope signature verifies`() {
        assertTrue(EnvelopeSigner.verifyEnvelope(vectorEntity, vectorPublicKey))
    }

    @Test
    fun `fixed vector - hop entry 0 validates under the section 2 rules`() {
        val trace = HopTrace.parseTrace(vectorEntity.hopTrace)
        assertEquals(1, trace?.size)
        val entry0 = trace!![0]
        // Canonical hop-0 bytes equal the contracts string exactly.
        assertEquals(
            """{"at":1700000000000,"deviceId":"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c","i":0,"messageId":"12345678-1234-4234-9234-1234567890ab","prevSig":"","role":"ORIGIN"}""",
            String(HopTrace.canonicalSignedBytes(vectorEntity.messageId, entry0), Charsets.UTF_8)
        )
        // Id binding + signature (per-entry), and full chain validation.
        assertTrue(HopTrace.verifyEntry(entry0, vectorEntity.messageId))
        assertEquals(
            listOf(true),
            HopTrace.validateTrace(trace, vectorEntity.messageId, vectorDeviceId)
        )
    }
}
