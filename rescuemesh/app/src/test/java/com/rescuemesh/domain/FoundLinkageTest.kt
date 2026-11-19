package com.rescuemesh.domain

import com.rescuemesh.data.local.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX6 — the Found picker's local cap. A MISSING_PERSON case is
 * "found-linked" when Room holds a PERSON_FOUND_UPDATE whose
 * payload.caseId equals the case's messageId.
 */
class FoundLinkageTest {

    private fun record(
        messageId: String,
        eventType: String,
        payload: String,
        originDeviceId: String = "this-phone"
    ) = MessageEntity(
        messageId = messageId,
        eventType = eventType,
        originDeviceId = originDeviceId,
        createdAt = 1_700_000_000_000L,
        expiresAt = 1_700_000_000_000L + 86_400_000L,
        priority = 1,
        hopLimit = 5,
        hopCount = 0,
        hopTrace = "[]",
        payload = payload,
        signature = "",
        localState = "CREATED",
        isOwn = originDeviceId == "this-phone"
    )

    private fun missing(id: String) = record(
        id, "MISSING_PERSON",
        """{"name":"Person $id","description":"last seen near the river"}"""
    )

    private fun foundUpdate(id: String, caseId: String, origin: String = "this-phone") =
        record(id, "PERSON_FOUND_UPDATE", """{"caseId":"$caseId","note":"found at the shelter"}""", origin)

    @Test
    fun `a found update made on this phone links its case`() {
        val ids = FoundLinkage.linkedCaseIds(listOf(missing("case-1"), foundUpdate("f-1", "case-1")))
        assertEquals(setOf("case-1"), ids)
    }

    @Test
    fun `a found update relayed in over the mesh links its case too`() {
        // Origin is a different phone — linkage is payload-only, so a
        // relayed update counts exactly like an own one.
        val ids = FoundLinkage.linkedCaseIds(
            listOf(missing("case-1"), foundUpdate("f-1", "case-1", origin = "peer-phone-9"))
        )
        assertEquals(setOf("case-1"), ids)
    }

    @Test
    fun `only PERSON_FOUND_UPDATE records link a case`() {
        val ids = FoundLinkage.linkedCaseIds(
            listOf(
                record("s-1", "SOS", """{"caseId":"case-1"}"""),
                record("m-1", "MISSING_PERSON", """{"caseId":"case-1"}"""),
                record("a-1", "ANNOUNCEMENT", """{"caseId":"case-1"}""")
            )
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `missing or blank caseId and malformed payloads link nothing`() {
        val ids = FoundLinkage.linkedCaseIds(
            listOf(
                record("f-1", "PERSON_FOUND_UPDATE", """{"note":"no case here"}"""),
                record("f-2", "PERSON_FOUND_UPDATE", """{"caseId":"  "}"""),
                record("f-3", "PERSON_FOUND_UPDATE", """{"caseId": 42}"""),
                record("f-4", "PERSON_FOUND_UPDATE", """not json at all""")
            )
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `duplicate found updates for one case link it once`() {
        val ids = FoundLinkage.linkedCaseIds(
            listOf(foundUpdate("f-1", "case-1"), foundUpdate("f-2", "case-1", "peer-phone-9"))
        )
        assertEquals(setOf("case-1"), ids)
    }

    @Test
    fun `split puts a found-linked case only in alreadyReported`() {
        val split = FoundLinkage.split(
            listOf(missing("case-1"), missing("case-2"), missing("case-3")),
            setOf("case-2")
        )
        assertEquals(listOf("case-1", "case-3"), split.main.map { it.messageId })
        assertEquals(listOf("case-2"), split.alreadyReported.map { it.messageId })
    }

    @Test
    fun `split keeps order and leaves an unlinked case in the main list`() {
        val split = FoundLinkage.split(listOf(missing("case-1")), emptySet())
        assertEquals(listOf("case-1"), split.main.map { it.messageId })
        assertTrue(split.alreadyReported.isEmpty())
    }

    @Test
    fun `selecting an unlinked case selects it at once, no confirmation`() {
        val state = FoundPickerState()
        state.select("case-1", isLinked = false)
        assertEquals("case-1", state.selectedCaseId)
        assertNull(state.pendingConfirmCaseId)
    }

    @Test
    fun `selecting a linked case only stages it until confirmed`() {
        val state = FoundPickerState()
        state.select("case-1", isLinked = false)
        state.select("case-2", isLinked = true)
        // The earlier selection is untouched until the user confirms.
        assertEquals("case-1", state.selectedCaseId)
        assertEquals("case-2", state.pendingConfirmCaseId)
    }

    @Test
    fun `confirming the staged case selects it`() {
        val state = FoundPickerState()
        state.select("case-2", isLinked = true)
        state.confirm()
        assertEquals("case-2", state.selectedCaseId)
        assertNull(state.pendingConfirmCaseId)
    }

    @Test
    fun `dismissing the confirmation keeps the previous selection`() {
        val state = FoundPickerState()
        state.select("case-1", isLinked = false)
        state.select("case-2", isLinked = true)
        state.dismiss()
        assertEquals("case-1", state.selectedCaseId)
        assertNull(state.pendingConfirmCaseId)
    }

    @Test
    fun `confirm with nothing staged changes nothing`() {
        val state = FoundPickerState()
        state.confirm()
        assertNull(state.selectedCaseId)
        assertNull(state.pendingConfirmCaseId)
    }
}
