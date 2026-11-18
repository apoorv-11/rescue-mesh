package com.rescuemesh

import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.repository.MessageCreationResult
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PayloadLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Prompt 6 payload shapes (contracts §7): exact JSON per type, optional
 * fields OMITTED when absent, caseId == the record's own messageId for
 * MISSING_PERSON, announcement expiresAt == validUntil, and priorities /
 * TTLs from the shared rules.
 */
class FormsPayloadTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val fixedNow = 1_700_000_000_000L
    private var counter = 0
    private val factory = MessageFactory(
        nowMs = { fixedNow },
        newId = { "00000000-0000-4000-8000-%012d".format(++counter) }
    )

    private val gps = PayloadLocation("12.971", "77.594", "GPS")

    @Test
    fun `SOS detailed - full payload is exact, priority 1, TTL 24h`() {
        val sos = factory.createSosDetailed(
            originDeviceId = "device-a",
            category = "MEDICAL",
            description = "Trapped under debris",
            peopleCount = 3,
            injured = 1,
            needs = listOf("WATER", "MEDICINE"),
            vulnerable = listOf("CHILD"),
            location = gps
        )
        assertEquals(
            """{"category":"MEDICAL","description":"Trapped under debris","peopleCount":3,"injured":1,""" +
                """"needs":["WATER","MEDICINE"],"vulnerable":["CHILD"],""" +
                """"location":{"lat":"12.971","lon":"77.594","source":"GPS"}}""",
            sos.payload
        )
        assertEquals(1, sos.priority)
        assertEquals(fixedNow + 24L * 3600_000, sos.expiresAt)
        assertEquals("SOS", sos.eventType)
    }

    @Test
    fun `SOS detailed - absent optionals are omitted, never null`() {
        val sos = factory.createSosDetailed("device-a", "FIRE", "", 2)
        assertEquals("""{"category":"FIRE","description":"","peopleCount":2}""", sos.payload)
        assertFalse(sos.payload.contains("injured"))
        assertFalse(sos.payload.contains("needs"))
        assertFalse(sos.payload.contains("vulnerable"))
        assertFalse(sos.payload.contains("location"))
        assertFalse(sos.payload.contains("null"))
        assertEquals(2, sos.priority) // FIRE is High, not Critical
    }

    @Test
    fun `SOS detailed - description is capped at 280 chars`() {
        val sos = factory.createSosDetailed("d", "OTHER", "x".repeat(300), 1)
        val payload = JsonParser.parse(sos.payload) as Map<*, *>
        assertEquals(280, (payload["description"] as String).length)
    }

    @Test
    fun `missing person - caseId equals the record's own messageId`() {
        val missing = factory.createMissingPerson(
            originDeviceId = "device-a",
            name = "Asha",
            age = 9,
            description = "Red jacket, short hair",
            lastSeenArea = "MG Road",
            lastSeenAtMs = 1_699_990_000_000L,
            photoThumbB64 = "aBc123_-",
            photoOmitted = false
        )
        assertEquals(
            """{"caseId":"${missing.messageId}","name":"Asha","age":9,""" +
                """"description":"Red jacket, short hair","lastSeenArea":"MG Road",""" +
                """"lastSeenAt":1699990000000,"photoThumb":"aBc123_-"}""",
            missing.payload
        )
        assertFalse(missing.payload.contains("photoOmitted"))
        assertEquals(2, missing.priority)
        assertEquals(fixedNow + 72L * 3600_000, missing.expiresAt)
    }

    @Test
    fun `missing person - omitted photo sets photoOmitted and drops name age photoThumb`() {
        val missing = factory.createMissingPerson(
            originDeviceId = "device-a",
            description = "Unknown",
            lastSeenArea = "Station",
            lastSeenAtMs = 1_699_990_000_000L,
            photoThumbB64 = null,
            photoOmitted = true
        )
        assertEquals(
            """{"caseId":"${missing.messageId}","description":"Unknown","lastSeenArea":"Station",""" +
                """"lastSeenAt":1699990000000,"photoOmitted":true}""",
            missing.payload
        )
    }

    @Test
    fun `person found - payload points at the case, priority 2, TTL 72h`() {
        val found = factory.createPersonFound("device-a", "case-42", "Found at the shelter", gps)
        assertEquals(
            """{"caseId":"case-42","note":"Found at the shelter",""" +
                """"location":{"lat":"12.971","lon":"77.594","source":"GPS"}}""",
            found.payload
        )
        assertEquals(2, found.priority)
        assertEquals(fixedNow + 72L * 3600_000, found.expiresAt)

        val noLocation = factory.createPersonFound("device-a", "case-42", "Safe")
        assertEquals("""{"caseId":"case-42","note":"Safe"}""", noLocation.payload)
    }

    @Test
    fun `announcement - envelope expiresAt IS the payload validUntil`() {
        val validUntil = 1_700_100_000_000L
        val announcement = factory.createAnnouncement("device-a", "WATER POINT", "Water at the school gate", validUntil)
        assertEquals(
            """{"category":"WATER POINT","text":"Water at the school gate","validUntil":1700100000000}""",
            announcement.payload
        )
        assertEquals(validUntil, announcement.expiresAt)
        assertEquals(3, announcement.priority)
    }

    @Test
    fun `resource update - payload, priority 3, TTL 12h`() {
        val resource = factory.createResourceUpdate("device-a", "WATER", 40)
        assertEquals("""{"resourceType":"WATER","quantity":40}""", resource.payload)
        assertEquals(3, resource.priority)
        assertEquals(fixedNow + 12L * 3600_000, resource.expiresAt)

        val withNote = factory.createResourceUpdate("device-a", "BEDS", 5, note = "Community hall", location = gps)
        assertEquals(
            """{"resourceType":"BEDS","quantity":5,"note":"Community hall",""" +
                """"location":{"lat":"12.971","lon":"77.594","source":"GPS"}}""",
            withNote.payload
        )
    }

    @Test
    fun `payload strings are escaped and round-trip through the JSON parser`() {
        val tricky = "He said \"help\"\nnow\tplease"
        val sos = factory.createSosDetailed("d", "OTHER", tricky, 1)
        assertTrue(sos.payload.contains("\\\"help\\\""))
        assertTrue(sos.payload.contains("\\n"))
        val parsed = JsonParser.parse(sos.payload) as Map<*, *>
        assertEquals(tricky, parsed["description"])
    }

    @Test
    fun `location from doubles formats to exactly 3 decimals`() {
        val loc = PayloadLocation.of(12.97149, 77.59423, PayloadLocation.SOURCE_GPS)
        assertEquals("12.971", loc.lat)
        assertEquals("77.594", loc.lon)
    }

    @Test
    fun `repository - the SOS form path respects the rate guard and the funnel signs`() = runBlocking {
        val alice = TestIdentity()
        val dao = InMemoryMessageDao()
        var clock = fixedNow
        var ids = 0
        val repo = MessageRepository(
            messageDao = dao,
            signer = alice.envelopeSigner,
            hopSigner = alice.hopSigner,
            factory = MessageFactory(nowMs = { clock }, newId = { "form-%04d".format(++ids) }),
            nowMs = { clock }
        )
        repeat(MessageRepository.MAX_SOS_PER_WINDOW) {
            val result = repo.sendSosDetailed(alice.deviceId, "FIRE", "fire $it", 2)
            assertTrue(result is MessageCreationResult.Created)
            clock += 1_000
        }
        val refused = repo.sendSosDetailed(alice.deviceId, "FIRE", "one too many", 2)
        assertTrue(refused is MessageCreationResult.Refused)
        // Non-SOS forms are never guarded.
        val missing = repo.reportMissingPerson(alice.deviceId, description = "x", lastSeenArea = "y", lastSeenAtMs = clock)
        assertEquals("CREATED", missing.localState)
        assertTrue(missing.signature.isNotEmpty())
    }
}
