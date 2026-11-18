package com.rescuemesh.crypto

import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Hop entries (batch contracts §2): origin entry creation, relay/gateway
 * appends, the id binding, the signed set, and chain validation. An invalid
 * entry must be flagged per-entry — never fatal, never contagious.
 */
class HopTraceTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val alice = TestIdentity()
    private val bob = TestIdentity()
    private val carol = TestIdentity()
    private val messageId = "12345678-1234-4234-9234-1234567890ab"
    private val t0 = 1_700_000_000_000L

    @Test
    fun `origin entry builds with the contract shape and validates`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        assertEquals(0, entry0.i)
        assertEquals(HopTrace.ROLE_ORIGIN, entry0.role)
        assertEquals(alice.deviceId, entry0.deviceId)
        assertEquals("", entry0.prevSig)
        assertEquals(t0, entry0.at)
        assertEquals(64, DeviceIds.base64UrlDecode(entry0.sig).size)
        assertTrue(HopTrace.verifyEntry(entry0, messageId))
        assertEquals(
            listOf(true),
            HopTrace.validateTrace(listOf(entry0), messageId, alice.deviceId)
        )
    }

    @Test
    fun `relay append chains onto entry 0 and the whole trace validates`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        val entry1 = bob.hopSigner.appendEntry(messageId, listOf(entry0), HopTrace.ROLE_RELAY, at = t0 + 5_000)
        assertEquals(1, entry1.i)
        assertEquals(entry0.sig, entry1.prevSig)
        assertEquals(HopTrace.ROLE_RELAY, entry1.role)
        assertEquals(
            listOf(true, true),
            HopTrace.validateTrace(listOf(entry0, entry1), messageId, alice.deviceId)
        )
        // Trace JSON round-trips through storage form unchanged.
        val parsed = HopTrace.parseTrace(HopTrace.traceToJson(listOf(entry0, entry1)))
        assertEquals(listOf(entry0, entry1), parsed)
    }

    @Test
    fun `gateway entry carries net in its signed set`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        val entry1 = bob.hopSigner.appendEntry(messageId, listOf(entry0), HopTrace.ROLE_RELAY, at = t0 + 1_000)
        val entry2 = carol.hopSigner.appendEntry(
            messageId, listOf(entry0, entry1), HopTrace.ROLE_GATEWAY,
            at = t0 + 2_000, net = HopTrace.NET_WIFI
        )
        assertEquals("WIFI", entry2.net)
        assertEquals(
            listOf(true, true, true),
            HopTrace.validateTrace(listOf(entry0, entry1, entry2), messageId, alice.deviceId)
        )
        // net is signed: changing it afterwards breaks the entry.
        assertFalse(HopTrace.verifyEntry(entry2.copy(net = HopTrace.NET_CELLULAR), messageId))
    }

    @Test
    fun `loc is a 3-decimal string and is covered by the signature`() {
        assertEquals("12.971,77.594", HopTrace.formatLoc(12.9714, 77.5943))
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0, loc = HopTrace.formatLoc(12.9714, 77.5943))
        assertEquals("12.971,77.594", entry0.loc)
        assertTrue(HopTrace.verifyEntry(entry0, messageId))
        assertFalse(HopTrace.verifyEntry(entry0.copy(loc = "12.972,77.594"), messageId))
        assertFalse(HopTrace.verifyEntry(entry0.copy(loc = null), messageId))
    }

    @Test
    fun `tampered entries fail - time, signature, and the pk binding`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        assertFalse(HopTrace.verifyEntry(entry0.copy(at = t0 + 1), messageId))
        assertFalse(HopTrace.verifyEntry(entry0.copy(sig = entry0.sig.replaceFirstChar { if (it == 'A') 'B' else 'A' }), messageId))
        // pk swapped for Bob's key: the deviceId no longer hashes from pk.
        assertFalse(HopTrace.verifyEntry(entry0.copy(pk = bob.hopSigner.publicKeyBase64Url), messageId))
        // Signed for a different message.
        assertFalse(HopTrace.verifyEntry(entry0, "00000000-0000-4000-8000-000000000000"))
    }

    @Test
    fun `a broken chain flags only the broken entry`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        val entry1 = bob.hopSigner.appendEntry(messageId, listOf(entry0), HopTrace.ROLE_RELAY, at = t0 + 1_000)
        val entry2 = carol.hopSigner.appendEntry(messageId, listOf(entry0, entry1), HopTrace.ROLE_RELAY, at = t0 + 2_000)
        val broken = entry1.copy(prevSig = "AAAA")
        // Entry 1 no longer chains (and its sig no longer matches its fields);
        // entry 2 still chains onto entry 1's (unchanged) sig and verifies.
        assertEquals(
            listOf(true, false, true),
            HopTrace.validateTrace(listOf(entry0, broken, entry2), messageId, alice.deviceId)
        )
    }

    @Test
    fun `entry 0 from the wrong device or role fails trace validation`() {
        val entry0 = alice.hopSigner.originEntry(messageId, at = t0)
        assertEquals(
            listOf(false),
            HopTrace.validateTrace(listOf(entry0), messageId, bob.deviceId)
        )
        val relayAsEntry0 = bob.hopSigner.signEntry(messageId, i = 0, role = HopTrace.ROLE_RELAY, at = t0, prevSig = "")
        assertEquals(
            listOf(false),
            HopTrace.validateTrace(listOf(relayAsEntry0), messageId, bob.deviceId)
        )
    }
}
