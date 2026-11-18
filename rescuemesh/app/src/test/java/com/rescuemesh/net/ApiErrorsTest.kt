package com.rescuemesh.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins parseServerError: the server's uniform error body is
 * {"error": "..."} (every controller over there builds it that way), and
 * the account UI shows that message verbatim. Anything else must degrade
 * to null so the caller falls back to the HTTP code.
 */
class ApiErrorsTest {

    @Test
    fun `extracts the server error message`() {
        assertEquals("email not verified", parseServerError("""{"error":"email not verified"}"""))
        assertEquals(
            "deviceId already bound to another user",
            parseServerError("""{"error":"deviceId already bound to another user"}""")
        )
    }

    @Test
    fun `returns null for blank or missing bodies`() {
        assertNull(parseServerError(null))
        assertNull(parseServerError(""))
        assertNull(parseServerError("   "))
    }

    @Test
    fun `returns null for non-JSON and wrong-shaped bodies`() {
        assertNull(parseServerError("Internal Server Error"))
        assertNull(parseServerError("""{"status":"ok"}"""))
        assertNull(parseServerError("""{"error":""}"""))
        assertNull(parseServerError("""["error"]"""))
    }

    @Test
    fun `ignores extra fields in the body`() {
        assertEquals("invalid code", parseServerError("""{"error":"invalid code","trace":"x"}"""))
    }
}
