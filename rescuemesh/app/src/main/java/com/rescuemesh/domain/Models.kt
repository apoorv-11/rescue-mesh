package com.rescuemesh.domain

/**
 * Shared domain vocabulary for RescueMesh.
 *
 * Contract source: the synopsis-aligned build plan (the single source of truth).
 * - Event types and envelope field names come from that plan / Doc 2 protocol.
 * - Priorities on the phone are rule-based, 3 levels only:
 *   1 = Critical, 2 = High, 3 = Normal (server ML is advisory later).
 * - TTL defaults: SOS 24 h, I Am Safe 12 h, Missing 72 h.
 */

enum class Role {
    CITIZEN, VOLUNTEER, COORDINATOR, ADMIN;

    companion object {
        fun fromName(name: String?): Role = entries.firstOrNull { it.name == name } ?: CITIZEN
    }
}

enum class EventType {
    SOS,
    SAFETY_CHECKIN,
    MISSING_PERSON,
    PERSON_FOUND_UPDATE,
    ANNOUNCEMENT,
    RESOURCE_UPDATE
}

/**
 * Local lifecycle of a stored record (batch contracts §4, state table).
 * REJECTED was added in Prompt 5's batch: set by GatewaySync when the server
 * refuses a record — terminal, never relayed or uploaded again. Stored as
 * text in Room, so adding a value needs no schema change.
 */
enum class LocalState {
    CREATED, QUEUED, SENDING, PEER_ACKED, SERVER_SYNCED, EXPIRED, FAILED_RETRYABLE, REJECTED
}

/**
 * A location as it appears in Prompt 6 payloads (contracts §7):
 * `{"lat":"<3dp string>","lon":"<3dp string>","source":"GPS"|"MANUAL"|"LAST_KNOWN"}`.
 * [lat]/[lon] are STRINGS with exactly three decimals — canonical JSON has
 * no floats, so coordinates never travel as numbers. Build via [of] from
 * device doubles, or directly when the user typed already-formatted text.
 */
data class PayloadLocation(
    val lat: String,
    val lon: String,
    /** "GPS", "MANUAL" or "LAST_KNOWN". */
    val source: String
) {
    companion object {
        const val SOURCE_GPS = "GPS"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_LAST_KNOWN = "LAST_KNOWN"

        /** Formats device coordinates to exactly 3 decimals (dot separator, US locale). */
        fun of(latitude: Double, longitude: Double, source: String): PayloadLocation =
            PayloadLocation(
                String.format(java.util.Locale.US, "%.3f", latitude),
                String.format(java.util.Locale.US, "%.3f", longitude),
                source
            )
    }
}

object PriorityRules {
    const val CRITICAL = 1
    const val HIGH = 2
    const val NORMAL = 3

    /**
     * Phone-side priority by rules only (no ML on the phone).
     * [category] is the SOS category string from the form, if any;
     * MEDICAL or TRAPPED SOS is Critical, every other SOS is High.
     */
    fun priorityFor(eventType: EventType, category: String? = null): Int = when (eventType) {
        EventType.SOS ->
            if (category.equals("MEDICAL", true) || category.equals("TRAPPED", true)) CRITICAL else HIGH
        EventType.MISSING_PERSON, EventType.PERSON_FOUND_UPDATE -> HIGH
        EventType.ANNOUNCEMENT, EventType.RESOURCE_UPDATE, EventType.SAFETY_CHECKIN -> NORMAL
    }

    /** Default hop limit for a new envelope (protocol default from Doc 2). */
    const val DEFAULT_HOP_LIMIT = 8
}

object TtlRules {
    private const val HOUR_MS = 60L * 60L * 1000L

    /** TTL in milliseconds per event type. Announcement TTL is set by its expiry, handled by the caller. */
    fun ttlMillisFor(eventType: EventType): Long = when (eventType) {
        EventType.SOS -> 24 * HOUR_MS
        EventType.SAFETY_CHECKIN -> 12 * HOUR_MS
        EventType.MISSING_PERSON -> 72 * HOUR_MS
        EventType.PERSON_FOUND_UPDATE -> 72 * HOUR_MS
        EventType.RESOURCE_UPDATE -> 12 * HOUR_MS
        EventType.ANNOUNCEMENT -> 24 * HOUR_MS // placeholder until a coordinator sets an expiry (Phase 2)
    }

    fun expiresAt(createdAtMs: Long, eventType: EventType): Long =
        createdAtMs + ttlMillisFor(eventType)

    fun isExpired(expiresAtMs: Long, nowMs: Long): Boolean = nowMs >= expiresAtMs
}
