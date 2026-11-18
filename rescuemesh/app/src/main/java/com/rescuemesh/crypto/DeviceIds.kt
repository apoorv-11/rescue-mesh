package com.rescuemesh.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Device identity maths (Prompt 4 decisions, 14 Nov 2026 — see
 * prompt4-identity-decisions-2026-11-14.md §3). Pure functions, JVM-testable.
 *
 * The one formula, valid everywhere (phone and server):
 *   originDeviceId = lowercase_hex( SHA-256( raw 32-byte Ed25519 public key ) )
 * The RAW key bytes are hashed, never the 44-byte SPKI encoding — mixing
 * those two up is the most likely phone/server interop bug, so the input
 * length is enforced here.
 */
object DeviceIds {

    const val RAW_PUBLIC_KEY_BYTES = 32

    /** Nearby advertised name prefix; the full id never goes over the air as an endpoint name. */
    const val ADVERTISED_PREFIX = "RM-"

    fun originDeviceId(rawPublicKey: ByteArray): String {
        require(rawPublicKey.size == RAW_PUBLIC_KEY_BYTES) {
            "Ed25519 public key must be exactly $RAW_PUBLIC_KEY_BYTES raw bytes, got ${rawPublicKey.size}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey)
        return digest.toHexLower()
    }

    /**
     * The binding rule: an id is only believable together with the public key
     * that hashes to it. Receivers recompute this from the key carried in
     * HELLO and reject the peer on mismatch (the id is self-certifying).
     */
    fun isValidOriginDeviceId(id: String, rawPublicKey: ByteArray): Boolean {
        if (id.length != 64 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return false
        if (rawPublicKey.size != RAW_PUBLIC_KEY_BYTES) return false
        return originDeviceId(rawPublicKey) == id
    }

    /** Short Nearby label: "RM-" + first 12 hex chars (15 chars total). Display hint only. */
    fun advertisedLabel(originDeviceId: String): String = ADVERTISED_PREFIX + originDeviceId.take(12)

    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
