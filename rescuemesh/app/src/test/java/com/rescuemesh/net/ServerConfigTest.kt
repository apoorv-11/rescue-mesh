package com.rescuemesh.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins ServerConfig.normalizeBaseUrl: the Account screen accepts whatever a
 * tester types, and a bad URL must be rejected at save time — not as a
 * mysterious network failure on every later call.
 */
class ServerConfigTest {

    @Test
    fun `trims whitespace and strips trailing slashes`() {
        assertEquals("https://x.onrender.com", ServerConfig.normalizeBaseUrl("  https://x.onrender.com/  "))
        assertEquals("http://192.168.1.5:8081", ServerConfig.normalizeBaseUrl("http://192.168.1.5:8081///"))
    }

    @Test
    fun `keeps a path suffix after the host`() {
        assertEquals("https://example.com/api", ServerConfig.normalizeBaseUrl("https://example.com/api/"))
    }

    @Test
    fun `accepts an uppercase scheme and normalizes it`() {
        assertEquals("http://192.168.1.5:8081", ServerConfig.normalizeBaseUrl("HTTP://192.168.1.5:8081"))
    }

    @Test
    fun `rejects missing or unsupported schemes`() {
        assertNull(ServerConfig.normalizeBaseUrl("192.168.1.5:8081"))
        assertNull(ServerConfig.normalizeBaseUrl("ftp://example.com"))
        assertNull(ServerConfig.normalizeBaseUrl("example.com"))
    }

    @Test
    fun `rejects blank input and missing hosts`() {
        assertNull(ServerConfig.normalizeBaseUrl(""))
        assertNull(ServerConfig.normalizeBaseUrl("   "))
        assertNull(ServerConfig.normalizeBaseUrl("http://"))
        assertNull(ServerConfig.normalizeBaseUrl("https:///path-only"))
    }

    @Test
    fun `rejects whitespace inside the host`() {
        assertNull(ServerConfig.normalizeBaseUrl("http://192. 168.1.5:8081"))
    }

    // ------------------------------------------------------------ FIX5 default

    @Test
    fun `default is the Render URL given on 19 Nov 2026`() {
        assertEquals(
            "https://rescue-mesh-m0n0.onrender.com",
            ServerConfig.DEFAULT_BASE_URL
        )
    }

    @Test
    fun `no saved URL falls back to the Render default, never null`() {
        assertEquals(ServerConfig.DEFAULT_BASE_URL, ServerConfig.effectiveBaseUrl(null))
        assertEquals(ServerConfig.DEFAULT_BASE_URL, ServerConfig.effectiveBaseUrl(""))
        assertEquals(ServerConfig.DEFAULT_BASE_URL, ServerConfig.effectiveBaseUrl("   "))
    }

    @Test
    fun `a saved custom URL wins over the default`() {
        assertEquals(
            "http://192.168.1.5:8081",
            ServerConfig.effectiveBaseUrl("http://192.168.1.5:8081")
        )
    }
}
