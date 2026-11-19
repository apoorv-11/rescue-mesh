package com.rescuemesh.domain

import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.local.MessageEntity

/**
 * FIX6 (19 Nov 2026) — the Found picker's LOCAL cap.
 *
 * Problem: the Found form's case-picker listed every stored
 * MISSING_PERSON case, including ones a found report had ALREADY
 * gone out for — so the same person kept getting re-reported by
 * mistake, even after a coordinator had CONFIRMED FOUND on the
 * dashboard.
 *
 * Declared scope (do NOT over-claim): the phone CANNOT know the
 * dashboard's review state offline — whether a coordinator confirmed
 * a case reaches the phone only via a sync, and the review state is
 * not in the envelope at all. So the cap is LOCAL: a case counts as
 * "found-linked" when this phone's own Room holds a
 * PERSON_FOUND_UPDATE whose payload.caseId equals the case's
 * messageId — whether that update was made on this phone or relayed
 * in over the mesh. The server rejects nothing, and the dashboard
 * already shows ALL found reports per case (newest first), so a
 * deliberate second report still lands visibly there; this cap only
 * stops the accidental one.
 *
 * Envelope, payload keys and the server are untouched — this is
 * purely a read of already-stored records plus picker UI.
 */
object FoundLinkage {

    /** The caseId a PERSON_FOUND_UPDATE points at, or null. */
    fun caseIdOf(record: MessageEntity): String? {
        if (record.eventType != "PERSON_FOUND_UPDATE") return null
        val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
        return (payload?.get("caseId") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Every caseId that has a found report stored on this phone.
     * Origin does not matter: an update made here and one relayed in
     * over the mesh link the case exactly the same way.
     */
    fun linkedCaseIds(records: List<MessageEntity>): Set<String> =
        records.mapNotNull { caseIdOf(it) }.toSet()

    /** Picker split: main list first, already-reported cases after. */
    data class FoundPickerSplit(
        val main: List<MessageEntity>,
        val alreadyReported: List<MessageEntity>
    )

    fun split(missing: List<MessageEntity>, linkedIds: Set<String>): FoundPickerSplit {
        val main = ArrayList<MessageEntity>()
        val reported = ArrayList<MessageEntity>()
        for (record in missing) {
            if (record.messageId in linkedIds) reported += record else main += record
        }
        return FoundPickerSplit(main, reported)
    }
}

/**
 * The override flow's rule, as a tiny state machine — the Found form
 * screen mirrors these exact transitions with Compose state (see
 * FoundFormScreen), so this class is the single tested statement of
 * the rule:
 * - tapping an UNLINKED case selects it at once, no confirmation;
 * - tapping a LINKED case only stages it as pending confirmation;
 * - confirm() selects the staged case, dismiss() drops it and keeps
 *   whatever was selected before.
 */
class FoundPickerState {
    var selectedCaseId: String? = null
        private set
    var pendingConfirmCaseId: String? = null
        private set

    fun select(caseId: String, isLinked: Boolean) {
        if (isLinked) {
            pendingConfirmCaseId = caseId
        } else {
            selectedCaseId = caseId
            pendingConfirmCaseId = null
        }
    }

    fun confirm() {
        pendingConfirmCaseId?.let { selectedCaseId = it }
        pendingConfirmCaseId = null
    }

    fun dismiss() {
        pendingConfirmCaseId = null
    }
}
