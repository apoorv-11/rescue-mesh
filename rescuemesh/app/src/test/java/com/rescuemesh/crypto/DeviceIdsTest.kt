package com.rescuemesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Prompt 4 identity formula:
 *   originDeviceId = lowercase_hex( SHA-256( raw 32-byte Ed25519 public key ) )
 * Expected hashes below were computed independently (python3 hashlib) — not
 * by the code under test — so a formula change (e.g. hashing the SPKI
 * encoding instead of the raw key) fails loudly here.
 */
class DeviceIdsTest {

    // SHA-256 of bytes 0x00..0x1F (python3: hashlib.sha256(bytes(range(32))).hexdigest())
    private val rangeKey = ByteArray(32) { it.toByte() }
    private val rangeKeyId = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd"

    // SHA-256 of 32 bytes of 0x42 (python3: hashlib.sha256(bytes([0x42]*32)).hexdigest())
    private val fillKeyId = "425ed4e4a36b30ea21b90e21c712c649e8214c29b7eaf68089d1039c6e55384c"

    @Test
    fun `originDeviceId matches the independent SHA-256 test vectors`() {
        assertEquals(rangeKeyId, DeviceIds.originDeviceId(rangeKey))
        assertEquals(fillKeyId, DeviceIds.originDeviceId(ByteArray(32) { 0x42 }))
    }

    @Test
    fun `originDeviceId is 64 lowercase hex chars`() {
        val id = DeviceIds.originDeviceId(rangeKey)
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `originDeviceId rejects keys that are not exactly 32 raw bytes`() {
        // 44 bytes would be the SPKI encoding — hashing that is the interop bug
        // this guard exists to prevent.
        assertThrows(IllegalArgumentException::class.java) { DeviceIds.originDeviceId(ByteArray(44)) }
        assertThrows(IllegalArgumentException::class.java) { DeviceIds.originDeviceId(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { DeviceIds.originDeviceId(ByteArray(0)) }
    }

    @Test
    fun `isValidOriginDeviceId recomputes the hash from the key`() {
        assertTrue(DeviceIds.isValidOriginDeviceId(rangeKeyId, rangeKey))
        // Right format, wrong key:
        assertFalse(DeviceIds.isValidOriginDeviceId(rangeKeyId, ByteArray(32) { 0x42 }))
        // Uppercase hex is not the canonical form:
        assertFalse(DeviceIds.isValidOriginDeviceId(rangeKeyId.uppercase(), rangeKey))
        // Truncated ids are never accepted:
        assertFalse(DeviceIds.isValidOriginDeviceId(rangeKeyId.take(32), rangeKey))
        assertFalse(DeviceIds.isValidOriginDeviceId(rangeKeyId, ByteArray(10)))
    }

    @Test
    fun `advertised label is RM- plus the first 12 hex chars`() {
        assertEquals("RM-630dcd2966c4", DeviceIds.advertisedLabel(rangeKeyId))
        assertEquals(15, DeviceIds.advertisedLabel(rangeKeyId).length)
    }

    @Test
    fun `base64url is unpadded and round-trips`() {
        // Independently computed: base64.urlsafe_b64encode(bytes(range(32))) minus padding.
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            DeviceIds.base64UrlEncode(rangeKey)
        )
        assertTrue(DeviceIds.base64UrlDecode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").contentEquals(rangeKey))
        assertFalse(DeviceIds.base64UrlEncode(rangeKey).contains("="))
    }
}
