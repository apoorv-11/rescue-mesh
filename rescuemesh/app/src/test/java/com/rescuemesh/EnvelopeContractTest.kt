package com.rescuemesh

import com.rescuemesh.data.local.MessageEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shared contract (plan Rule 2): the envelope properties keep their
 * exact names. Renaming any of them must fail this test loudly, not silently
 * break sync with the server in Phase 3.
 */
class EnvelopeContractTest {

    private val envelopeFields = listOf(
        "messageId", "eventType", "originDeviceId", "createdAt", "expiresAt",
        "priority", "hopLimit", "hopCount", "hopTrace", "payload", "signature"
    )

    @Test
    fun `Message entity carries every envelope field with its exact name`() {
        val fieldNames = MessageEntity::class.java.declaredFields.map { it.name }
        envelopeFields.forEach { expected ->
            assertTrue("Missing or renamed envelope field: $expected (found: $fieldNames)", fieldNames.contains(expected))
        }
    }
}
