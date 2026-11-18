package com.rescuemesh

import com.rescuemesh.domain.EventType
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PriorityRules
import com.rescuemesh.domain.TtlRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFactoryTest {

    private val fixedNow = 1_700_000_000_000L
    private var counter = 0
    private val factory = MessageFactory(
        nowMs = { fixedNow },
        newId = { "00000000-0000-4000-8000-%012d".format(++counter) }
    )

    @Test
    fun `SOS is stored with envelope defaults and 24h TTL`() {
        val sos = factory.createSos(originDeviceId = "device-a")
        assertEquals(EventType.SOS.name, sos.eventType)
        assertEquals("device-a", sos.originDeviceId)
        assertEquals(fixedNow, sos.createdAt)
        assertEquals(fixedNow + 24L * 60 * 60 * 1000, sos.expiresAt)
        assertEquals(PriorityRules.DEFAULT_HOP_LIMIT, sos.hopLimit)
        assertEquals(0, sos.hopCount)
        assertEquals("[]", sos.hopTrace)
        assertTrue(sos.isOwn)
        assertEquals("CREATED", sos.localState)
    }

    @Test
    fun `medical SOS is priority 1, other SOS is priority 2`() {
        assertEquals(1, factory.createSos("d", "MEDICAL").priority)
        assertEquals(1, factory.createSos("d", "TRAPPED").priority)
        assertEquals(2, factory.createSos("d", "FIRE").priority)
        assertEquals(2, factory.createSos("d", null).priority)
    }

    @Test
    fun `I Am Safe has 12h TTL and normal priority`() {
        val safe = factory.createSafetyCheckin(originDeviceId = "device-a")
        assertEquals(EventType.SAFETY_CHECKIN.name, safe.eventType)
        assertEquals(fixedNow + 12L * 60 * 60 * 1000, safe.expiresAt)
        assertEquals(3, safe.priority)
        assertTrue(safe.payload.contains("SAFE"))
    }

    @Test
    fun `every record gets a distinct messageId`() {
        val a = factory.createSos("d")
        val b = factory.createSos("d")
        assertTrue(a.messageId != b.messageId)
    }

    @Test
    fun `TTL rules match the plan defaults`() {
        assertEquals(72L * 60 * 60 * 1000, TtlRules.ttlMillisFor(EventType.MISSING_PERSON))
        assertTrue(TtlRules.isExpired(1000, 1000))
        assertTrue(!TtlRules.isExpired(1001, 1000))
    }
}
