package com.rescuemesh.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract with the server (rescuemesh-server records):
 * field names must match exactly, signup must never carry a role, and
 * server responses — including explicit nulls in /me and in application
 * views — must decode into the DTOs.
 */
class ApiDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register request has exactly the server fields and never a role`() {
        val encoded = json.encodeToString(
            RegisterRequest("apoorv", "a@example.com", "secret123", "device-001")
        )
        assertEquals(
            """{"username":"apoorv","email":"a@example.com","password":"secret123","deviceId":"device-001"}""",
            encoded
        )
        assertFalse("role must never be sent on signup", encoded.contains("role"))
    }

    @Test
    fun `token response decodes`() {
        val decoded = json.decodeFromString<TokenResponse>(
            """{"token":"abc.def.ghi","tokenType":"Bearer"}"""
        )
        assertEquals("abc.def.ghi", decoded.token)
        assertEquals("Bearer", decoded.tokenType)
    }

    @Test
    fun `register response decodes`() {
        val decoded = json.decodeFromString<RegisterResponse>(
            """{"username":"apoorv","role":"CITIZEN","deviceId":"device-001"}"""
        )
        assertEquals("CITIZEN", decoded.role)
        assertEquals("device-001", decoded.deviceId)
    }

    @Test
    fun `me response decodes with bound and unbound deviceId`() {
        val bound = json.decodeFromString<MeResponse>(
            """{"username":"apoorv","role":"VOLUNTEER","deviceId":"device-001"}"""
        )
        assertEquals("device-001", bound.deviceId)

        // /me emits an explicit null deviceId until a device is bound.
        val unbound = json.decodeFromString<MeResponse>(
            """{"username":"apoorv","role":"CITIZEN","deviceId":null}"""
        )
        assertNull(unbound.deviceId)
        assertEquals("CITIZEN", unbound.role)
    }

    @Test
    fun `role application view decodes a full server row`() {
        val decoded = json.decodeFromString<RoleApplicationView>(
            """{"id":7,"username":"apoorv","email":"a@example.com",""" +
                """"requestedRole":"VOLUNTEER","organization":"NSS","reason":"ground team",""" +
                """"status":"PENDING","reviewNote":null,"reviewedBy":null,""" +
                """"createdAt":"2026-11-14T07:30:00Z","reviewedAt":null}"""
        )
        assertEquals(7L, decoded.id)
        assertEquals("VOLUNTEER", decoded.requestedRole)
        assertEquals("PENDING", decoded.status)
        assertEquals("2026-11-14T07:30:00Z", decoded.createdAt)
        assertNull(decoded.reviewNote)
    }

    @Test
    fun `apply request omits absent optionals and keeps present ones`() {
        val bare = json.encodeToString(ApplyRoleRequest("COORDINATOR"))
        assertEquals("""{"requestedRole":"COORDINATOR"}""", bare)

        val full = json.encodeToString(ApplyRoleRequest("VOLUNTEER", "NSS", "ground team"))
        assertEquals(
            """{"requestedRole":"VOLUNTEER","organization":"NSS","reason":"ground team"}""",
            full
        )
    }

    @Test
    fun `small request bodies encode exactly`() {
        assertEquals(
            """{"username":"apoorv","code":"123456"}""",
            json.encodeToString(VerifyEmailRequest("apoorv", "123456"))
        )
        assertEquals(
            """{"username":"apoorv"}""",
            json.encodeToString(ResendVerificationRequest("apoorv"))
        )
        assertEquals(
            """{"code":"RM-ABCDE-FGHJK"}""",
            json.encodeToString(RedeemCodeRequest("RM-ABCDE-FGHJK"))
        )
        assertEquals(
            """{"deviceId":"device-001"}""",
            json.encodeToString(BindDeviceRequest("device-001"))
        )
    }

    @Test
    fun `bind device response and status response decode`() {
        val bind = json.decodeFromString<BindDeviceResponse>(
            """{"username":"apoorv","deviceId":"device-001"}"""
        )
        assertEquals("device-001", bind.deviceId)

        val status = json.decodeFromString<StatusResponse>(
            """{"status":"verification code sent"}"""
        )
        assertEquals("verification code sent", status.status)
    }

    @Test
    fun `unknown fields in server responses are tolerated`() {
        val decoded = json.decodeFromString<TokenResponse>(
            """{"token":"t","tokenType":"Bearer","futureField":42}"""
        )
        assertTrue(decoded.token == "t")
    }
}
