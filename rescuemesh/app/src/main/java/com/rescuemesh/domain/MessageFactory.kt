package com.rescuemesh.domain

import com.rescuemesh.data.local.MessageEntity
import java.util.UUID

/**
 * Builds new local records. Pure and deterministic apart from the injected
 * clock / id generator, so it is unit-testable on the JVM.
 *
 * The factory builds UNSIGNED drafts (signature "", hopTrace "[]"):
 * MessageRepository's creation funnel attaches hop entry 0 and signs with
 * EnvelopeSigner before storing — the factory itself never signs.
 *
 * Payload shapes are contracts §7 EXACTLY: optional fields are OMITTED when
 * absent (never null), and coordinates travel as 3-decimal STRINGS (see
 * [PayloadLocation]) — payload values never pass through floats.
 */
class MessageFactory(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    /**
     * Quick 2-tap Home SOS: the existing minimal payload (contracts §7 keeps it valid).
     *
     * FIX9 (19 Nov 2026): [location] is the phone's saved last-known fix,
     * embedded automatically by the repository — the user taps nothing and
     * no checkbox exists. Absent when there is no saved fix: the record
     * goes without location, never delayed by it.
     */
    fun createSos(
        originDeviceId: String,
        category: String? = null,
        location: PayloadLocation? = null
    ): MessageEntity {
        val created = nowMs()
        val payload = buildString {
            append("{")
            append("\"category\":").append(jsonString(category ?: "OTHER"))
            append(",\"description\":\"\"")
            append(",\"peopleCount\":1")
            location?.let { append(",\"location\":").append(locationJson(it)) }
            append("}")
        }
        return base(EventType.SOS, originDeviceId, created, category, payload)
    }

    /**
     * SOS form (Prompt 6): the full §7 payload. [category] is one of the 7
     * form categories; [description] is capped at 280 chars, [peopleCount]
     * at 1..999 and [injured] at 0..peopleCount (defensive clamps — the UI
     * steppers already enforce the same ranges).
     */
    fun createSosDetailed(
        originDeviceId: String,
        category: String,
        description: String,
        peopleCount: Int,
        injured: Int? = null,
        needs: List<String> = emptyList(),
        vulnerable: List<String> = emptyList(),
        location: PayloadLocation? = null
    ): MessageEntity {
        val created = nowMs()
        val people = peopleCount.coerceIn(1, 999)
        val payload = buildString {
            append("{")
            append("\"category\":").append(jsonString(category))
            append(",\"description\":").append(jsonString(description.take(MAX_SOS_DESCRIPTION_CHARS)))
            append(",\"peopleCount\":").append(people)
            injured?.let { append(",\"injured\":").append(it.coerceIn(0, people)) }
            if (needs.isNotEmpty()) append(",\"needs\":").append(jsonStringArray(needs))
            if (vulnerable.isNotEmpty()) append(",\"vulnerable\":").append(jsonStringArray(vulnerable))
            location?.let { append(",\"location\":").append(locationJson(it)) }
            append("}")
        }
        return base(EventType.SOS, originDeviceId, created, category, payload)
    }

    /**
     * One-tap I Am Safe.
     *
     * FIX9 (19 Nov 2026): [location] is the phone's saved last-known fix,
     * embedded automatically by the repository — same rule as quick SOS:
     * present when a fix is saved, omitted when there is none, and the
     * check-in never waits for one.
     */
    fun createSafetyCheckin(
        originDeviceId: String,
        location: PayloadLocation? = null
    ): MessageEntity {
        val created = nowMs()
        val payload = buildString {
            append("{\"status\":\"SAFE\"")
            location?.let { append(",\"location\":").append(locationJson(it)) }
            append("}")
        }
        return base(EventType.SAFETY_CHECKIN, originDeviceId, created, null, payload)
    }

    /**
     * Missing-person report (§7). `caseId` in the payload IS this record's
     * own messageId — that is what PERSON_FOUND_UPDATE records later point
     * at — so the id is generated FIRST and the payload built around it.
     * [photoThumbB64] is included only when the caller already applied the
     * PhotoThumb size rule; [photoOmitted] is set (true) only when a photo
     * was picked but exceeded the cap.
     *
     * FIX9 (19 Nov 2026): [location] is the form's numeric location
     * (Fill from GPS / typed lat-lon). The Missing form had NO location
     * input at all before this — its only "location" was the
     * `lastSeenArea` text — so a GPS fix could never reach the payload;
     * the form now carries LocationFields like the other forms and this
     * key rides alongside `lastSeenArea` (the dashboard pins on it).
     */
    fun createMissingPerson(
        originDeviceId: String,
        name: String? = null,
        age: Int? = null,
        description: String,
        lastSeenArea: String,
        lastSeenAtMs: Long,
        photoThumbB64: String? = null,
        photoOmitted: Boolean = false,
        photoRef: PhotoRef? = null,
        location: PayloadLocation? = null
    ): MessageEntity {
        val created = nowMs()
        val messageId = newId()
        val payload = buildString {
            append("{")
            append("\"caseId\":").append(jsonString(messageId))
            name?.takeIf { it.isNotBlank() }?.let { append(",\"name\":").append(jsonString(it)) }
            age?.let { append(",\"age\":").append(it.coerceIn(0, 120)) }
            append(",\"description\":").append(jsonString(description))
            append(",\"lastSeenArea\":").append(jsonString(lastSeenArea))
            append(",\"lastSeenAt\":").append(lastSeenAtMs)
            location?.let { append(",\"location\":").append(locationJson(it)) }
            photoThumbB64?.let { append(",\"photoThumb\":").append(jsonString(it)) }
            if (photoOmitted) append(",\"photoOmitted\":true")
            photoRef?.let { append(",\"photoRef\":").append(photoRefJson(it)) }
            append("}")
        }
        return base(EventType.MISSING_PERSON, originDeviceId, created, null, payload, messageId = messageId)
    }

    /**
     * Found update for an existing MISSING_PERSON record ([caseId] = that
     * record's messageId). Priority 2. The update may carry the same photo
     * pair as a Missing report — [photoThumbB64] preview + [photoRef]
     * pointing at this record's OWN full photo — so the dashboard can
     * later compare the found photo against the missing photo of the case.
     *
     * FIX7 (19 Nov 2026): [foundArea] is the human place name where the
     * person was found ("NCU, Sector 23A, Gurugram") — the mirror of a
     * Missing report's `lastSeenArea`, which is what the dashboard shows
     * in its AREA column. Before this, the form had no place field at
     * all: the only location input was numeric lat/lon, which nobody
     * fills, so the place name ended up typed into `note` and the
     * dashboard showed "—" for the found report. The numeric
     * [location] is untouched and still rides along when set.
     */
    fun createPersonFound(
        originDeviceId: String,
        caseId: String,
        note: String,
        location: PayloadLocation? = null,
        photoThumbB64: String? = null,
        photoOmitted: Boolean = false,
        photoRef: PhotoRef? = null,
        foundArea: String? = null
    ): MessageEntity {
        val created = nowMs()
        val messageId = newId()
        val payload = buildString {
            append("{")
            append("\"caseId\":").append(jsonString(caseId))
            append(",\"note\":").append(jsonString(note))
            foundArea?.takeIf { it.isNotBlank() }?.let {
                append(",\"foundArea\":").append(jsonString(it))
            }
            location?.let { append(",\"location\":").append(locationJson(it)) }
            photoThumbB64?.let { append(",\"photoThumb\":").append(jsonString(it)) }
            if (photoOmitted) append(",\"photoOmitted\":true")
            photoRef?.let { append(",\"photoRef\":").append(photoRefJson(it)) }
            append("}")
        }
        return base(
            EventType.PERSON_FOUND_UPDATE, originDeviceId, created, null, payload,
            messageId = messageId
        )
    }

    /**
     * Announcement (§7): the envelope's expiresAt IS the payload's
     * validUntil — it overrides the TtlRules placeholder for this type.
     * Priority 3.
     */
    fun createAnnouncement(
        originDeviceId: String,
        category: String,
        text: String,
        validUntilMs: Long
    ): MessageEntity {
        val created = nowMs()
        val payload = buildString {
            append("{")
            append("\"category\":").append(jsonString(category))
            append(",\"text\":").append(jsonString(text))
            append(",\"validUntil\":").append(validUntilMs)
            append("}")
        }
        return base(
            EventType.ANNOUNCEMENT, originDeviceId, created, null, payload,
            expiresAtOverride = validUntilMs
        )
    }

    /** Resource update (§7): priority 3, TTL 12 h (TtlRules). */
    fun createResourceUpdate(
        originDeviceId: String,
        resourceType: String,
        quantity: Int,
        note: String? = null,
        location: PayloadLocation? = null
    ): MessageEntity {
        val created = nowMs()
        val payload = buildString {
            append("{")
            append("\"resourceType\":").append(jsonString(resourceType))
            append(",\"quantity\":").append(quantity.coerceAtLeast(0))
            note?.takeIf { it.isNotBlank() }?.let { append(",\"note\":").append(jsonString(it)) }
            location?.let { append(",\"location\":").append(locationJson(it)) }
            append("}")
        }
        return base(EventType.RESOURCE_UPDATE, originDeviceId, created, null, payload)
    }

    private fun base(
        eventType: EventType,
        originDeviceId: String,
        createdAtMs: Long,
        category: String?,
        payload: String,
        messageId: String = newId(),
        expiresAtOverride: Long? = null
    ): MessageEntity = MessageEntity(
        messageId = messageId,
        eventType = eventType.name,
        originDeviceId = originDeviceId,
        createdAt = createdAtMs,
        expiresAt = expiresAtOverride ?: TtlRules.expiresAt(createdAtMs, eventType),
        priority = PriorityRules.priorityFor(eventType, category),
        hopLimit = PriorityRules.DEFAULT_HOP_LIMIT,
        hopCount = 0,
        hopTrace = "[]",
        payload = payload,
        signature = "",
        localState = LocalState.CREATED.name,
        isOwn = true
    )

    /**
     * The photo-transfer design's signed photo pointer: the ref's
     * `photoId` is the owning record's own messageId, `sha256`/`sizeBytes`
     * describe the JPEG held in PhotoStore — never the photo bytes
     * themselves (they travel in PHOTO_CHUNK packets, not the envelope).
     */
    private fun photoRefJson(ref: PhotoRef): String = buildString {
        append("{")
        append("\"photoId\":").append(jsonString(ref.photoId))
        append(",\"sha256\":").append(jsonString(ref.sha256))
        append(",\"sizeBytes\":").append(ref.sizeBytes)
        append(",\"mime\":").append(jsonString(ref.mime))
        append("}")
    }

    /** The shared §7 location object: lat/lon as the pre-formatted 3dp strings, never numbers. */
    private fun locationJson(location: PayloadLocation): String = buildString {
        append("{")
        append("\"lat\":").append(jsonString(location.lat))
        append(",\"lon\":").append(jsonString(location.lon))
        append(",\"source\":").append(jsonString(location.source))
        append("}")
    }

    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }

    /**
     * Full JSON string escaping (the Phase 1 helper only handled \ and ";
     * form text can contain newlines and other controls). Short forms for
     * the usual controls, \u00xx for the rest of C0 — the same style
     * CanonicalJson writes, so stored payload text stays canonical-friendly.
     */
    private fun jsonString(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (c < ' ') {
                    append("\\u00")
                    append(HEX_DIGITS[(c.code shr 4) and 0xF])
                    append(HEX_DIGITS[c.code and 0xF])
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    companion object {
        const val MAX_SOS_DESCRIPTION_CHARS = 280
        private const val HEX_DIGITS = "0123456789abcdef"

        /** The 7 SOS categories offered by the form (§7). */
        val SOS_CATEGORIES = listOf(
            "MEDICAL", "TRAPPED", "FIRE", "FLOOD_RESCUE", "FOOD_WATER", "SHELTER", "OTHER"
        )

        /** Fixed needs chip set (form). */
        val SOS_NEEDS = listOf("WATER", "FOOD", "MEDICINE", "SHELTER", "RESCUE")

        /** Fixed vulnerable-groups chip set (form). */
        val SOS_VULNERABLE = listOf("CHILD", "ELDERLY", "DISABLED", "PREGNANT")
    }
}
