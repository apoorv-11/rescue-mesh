package com.rescuemesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The canonicaliser is what makes a signature mean the same thing on every
 * phone and on the server: same envelope in -> same bytes out, always.
 */
class CanonicalJsonTest {

    @Test
    fun `object keys are sorted and no whitespace is emitted`() {
        val value = linkedMapOf<String, Any?>("b" to 1L, "a" to 2L, "c" to 3L)
        assertEquals("""{"a":2,"b":1,"c":3}""", CanonicalJson.canonicalize(value))
    }

    @Test
    fun `output does not depend on the input map order`() {
        val one = linkedMapOf<String, Any?>("x" to 1L, "a" to "s", "m" to true)
        val two = linkedMapOf<String, Any?>("m" to true, "a" to "s", "x" to 1L)
        assertEquals(CanonicalJson.canonicalize(one), CanonicalJson.canonicalize(two))
        assertEquals("""{"a":"s","m":true,"x":1}""", CanonicalJson.canonicalize(one))
    }

    @Test
    fun `nested payload object and hopTrace array canonicalise stably`() {
        val envelopeLike = linkedMapOf<String, Any?>(
            "payload" to linkedMapOf<String, Any?>(
                "peopleCount" to 2L,
                "category" to "MEDICAL",
                "description" to "trapped"
            ),
            "hopTrace" to listOf(
                linkedMapOf<String, Any?>("timestamp" to 1_700_000_000_000L, "deviceId" to "abc"),
                linkedMapOf<String, Any?>("deviceId" to "def", "timestamp" to 1_700_000_005_000L)
            ),
            "hopCount" to 2
        )
        assertEquals(
            """{"hopCount":2,"hopTrace":[{"deviceId":"abc","timestamp":1700000000000},""" +
                """{"deviceId":"def","timestamp":1700000005000}],""" +
                """"payload":{"category":"MEDICAL","description":"trapped","peopleCount":2}}""",
            CanonicalJson.canonicalize(envelopeLike)
        )
    }

    @Test
    fun `array order is preserved (hopTrace order is meaningful)`() {
        assertEquals("""[3,1,2]""", CanonicalJson.canonicalize(listOf(3L, 1L, 2L)))
        assertEquals("""[]""", CanonicalJson.canonicalize(emptyList<Any?>()))
        assertEquals("""{}""", CanonicalJson.canonicalize(emptyMap<String, Any?>()))
    }

    @Test
    fun `integers booleans and null render plainly`() {
        assertEquals("1700000000000", CanonicalJson.canonicalize(1_700_000_000_000L))
        assertEquals("-5", CanonicalJson.canonicalize(-5))
        assertEquals("true", CanonicalJson.canonicalize(true))
        assertEquals("null", CanonicalJson.canonicalize(null))
    }

    @Test
    fun `strings use only the escapes JCS requires`() {
        assertEquals(""" "a\"b\\c" """.trim(), CanonicalJson.canonicalize("a\"b\\c"))
        assertEquals(""" "line\nbreak" """.trim(), CanonicalJson.canonicalize("line\nbreak"))
        assertEquals(""" "tab\there" """.trim(), CanonicalJson.canonicalize("tab\there"))
        // Other C0 controls become \u00xx with lowercase hex:
        assertEquals(""" "\u0001" """.trim(), CanonicalJson.canonicalize("\u0001"))
        // DEL and non-ASCII are emitted raw, never \u-escaped:
        assertEquals("\"café\"", CanonicalJson.canonicalize("café"))
    }

    @Test
    fun `floats are rejected - the contract has integers only`() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.canonicalize(1.5) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalJson.canonicalize(1.5f) }
    }

    @Test
    fun `parse then canonicalise normalises stored JSON text`() {
        val stored = """{ "peopleCount" : 1, "category" : "OTHER", "description" : "" }"""
        assertEquals(
            """{"category":"OTHER","description":"","peopleCount":1}""",
            CanonicalJson.canonicalize(JsonParser.parse(stored))
        )
        val trace = """[{"timestamp":5,"deviceId":"d1"}]"""
        assertEquals(
            """[{"deviceId":"d1","timestamp":5}]""",
            CanonicalJson.canonicalize(JsonParser.parse(trace))
        )
    }

    @Test
    fun `parser rejects non-integer numbers and malformed JSON`() {
        assertThrows(IllegalArgumentException::class.java) { JsonParser.parse("""{"a":1.5}""") }
        assertThrows(IllegalArgumentException::class.java) { JsonParser.parse("""{"a":}""") }
        assertThrows(IllegalArgumentException::class.java) { JsonParser.parse("""{"a":1} extra""") }
        assertThrows(IllegalArgumentException::class.java) { JsonParser.parse("""[1,2""") }
    }

    @Test
    fun `parser handles escapes and unicode`() {
        val parsed = JsonParser.parse("""{"s":"a\nbé\\"}""") as Map<*, *>
        assertEquals("a\nbé\\", parsed["s"])
    }
}
