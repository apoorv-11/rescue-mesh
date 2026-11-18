package com.rescuemesh.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.util.Bytes
import com.rescuemesh.data.local.MessageEntity
import java.security.GeneralSecurityException

/**
 * Envelope signing (Prompt 4, scope changed by Prompt 5 — batch contracts §1).
 * The envelope contract stays exactly the 11 fixed fields; what gets signed
 * is the canonical JSON (see [CanonicalJson]) of the EIGHT origin-fixed
 * fields {messageId, eventType, originDeviceId, createdAt, expiresAt,
 * priority, hopLimit, payload}, with payload as a parsed JSON value (it is
 * stored as JSON text in Room). `hopCount`, `hopTrace` and `signature` are
 * NOT signed: relay appends hop entries and changes hopCount, so the old
 * Prompt 4 scope (everything except `signature`) would have made every
 * relayed record unverifiable. The signature itself is a raw 64-byte
 * Ed25519 signature, stored as Base64url without padding.
 *
 * Consequence (accepted in the contracts, pre-1.0 test data only): records
 * created by Phase 1 builds carry old-scope signatures and fail this
 * verification. They are test data; there is no migration.
 *
 * Signing needs a Tink [PublicKeySign] (NO_PREFIX Ed25519 keyset — see
 * [DeviceIdentity]); verification needs only the sender's raw 32-byte public
 * key, so any phone — and the server — can verify fully offline.
 */
class EnvelopeSigner(private val signer: PublicKeySign) {

    /** Base64url-no-pad Ed25519 signature over the envelope's canonical bytes. */
    fun signEnvelope(message: MessageEntity): String {
        val signature = signer.sign(canonicalBytes(message))
        check(signature.size == SIGNATURE_BYTES) {
            "Ed25519 signature must be exactly $SIGNATURE_BYTES bytes, got ${signature.size} " +
                "(the keyset is not using the NO_PREFIX variant)"
        }
        return DeviceIds.base64UrlEncode(signature)
    }

    /** A copy of [message] with its real signature filled in; the input is untouched. */
    fun signedCopy(message: MessageEntity): MessageEntity =
        message.copy(signature = signEnvelope(message))

    companion object {
        const val SIGNATURE_BYTES = 64

        /**
         * The signed field set (batch contracts §1): the eight origin-fixed
         * fields. `hopCount`, `hopTrace` and `signature` are deliberately
         * excluded — relays mutate the first two. Field names are the exact
         * contract names — never rename them.
         */
        fun envelopeFields(message: MessageEntity): Map<String, Any?> = linkedMapOf(
            "messageId" to message.messageId,
            "eventType" to message.eventType,
            "originDeviceId" to message.originDeviceId,
            "createdAt" to message.createdAt,
            "expiresAt" to message.expiresAt,
            "priority" to message.priority,
            "hopLimit" to message.hopLimit,
            "payload" to JsonParser.parse(message.payload)
        )

        /** The exact bytes that are signed and verified. */
        fun canonicalBytes(message: MessageEntity): ByteArray =
            CanonicalJson.canonicalBytes(envelopeFields(message))

        /** Convenience: an [EnvelopeSigner] backed by a Tink private keyset handle. */
        fun fromKeysetHandle(privateKeysetHandle: KeysetHandle): EnvelopeSigner =
            EnvelopeSigner(privateKeysetHandle.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java))

        /**
         * The raw 32-byte Ed25519 public key of a private keyset handle.
         * Used by [DeviceIdentity] and by tests; safe to publish — it is a
         * public key. Never do the equivalent for private key material.
         */
        fun rawPublicKeyBytes(privateKeysetHandle: KeysetHandle): ByteArray {
            val publicHandle = privateKeysetHandle.publicKeysetHandle
            for (index in 0 until publicHandle.size()) {
                val entry = publicHandle.getAt(index)
                if (entry.isPrimary) {
                    val key = entry.key as? Ed25519PublicKey
                        ?: throw GeneralSecurityException("Primary key is not an Ed25519 public key")
                    return key.publicKeyBytes.toByteArray()
                }
            }
            throw GeneralSecurityException("Keyset has no primary key")
        }

        /** A [PublicKeyVerify] for one raw 32-byte Ed25519 public key (NO_PREFIX). */
        fun verifierFor(rawPublicKey: ByteArray): PublicKeyVerify {
            require(rawPublicKey.size == DeviceIds.RAW_PUBLIC_KEY_BYTES) {
                "Ed25519 public key must be exactly ${DeviceIds.RAW_PUBLIC_KEY_BYTES} raw bytes, got ${rawPublicKey.size}"
            }
            val publicKey = Ed25519PublicKey.create(Bytes.copyFrom(rawPublicKey))
            val handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary())
                .build()
            return handle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
        }

        /**
         * True when [message]'s signature is a valid Ed25519 signature by the
         * holder of [rawPublicKey] over the envelope's canonical bytes.
         * Any malformation (bad Base64, wrong length, bad signature) is false,
         * never an exception leaking into relay code.
         */
        fun verifyEnvelope(message: MessageEntity, rawPublicKey: ByteArray): Boolean {
            val signature = try {
                DeviceIds.base64UrlDecode(message.signature)
            } catch (e: IllegalArgumentException) {
                return false
            }
            if (signature.size != SIGNATURE_BYTES) return false
            return try {
                verifierFor(rawPublicKey).verify(signature, canonicalBytes(message))
                true
            } catch (e: GeneralSecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
