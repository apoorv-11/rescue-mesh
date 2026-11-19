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

    /**
     * Default hop limit for a new envelope (protocol default from Doc 2).
     *
     * FIX9 (19 Nov 2026): raised 8 → 50 on the user's decision — at 8 a
     * record could die on the hop limit before ever reaching a gateway
     * in a long relay chain. The server has no cap of its own (it only
     * checks hopCount <= hopLimit), so no server change is needed, and
     * old records keep their own hopLimit 8. Note the honest cost,
     * measured for this fix (hop50-measurement.py, replicating
     * CanonicalJson + the MSG packet shape): a 50-hop trace (51 entries,
     * all with loc) is 19,713 bytes of the 30,000-byte packet guard, so
     * text records still fit easily (quick SOS: 20,230 bytes; maxed
     * detailed SOS: 20,627). But a Missing record with a full
     * 12,000-char photoThumb reaches 32,798 bytes at 50 hops — 2,798
     * over the guard — and the mesh drops oversize packets on send AND
     * on receipt, so it stops relaying (it survives up to hopCount 42:
     * 43 entries = 29,694 bytes, 44 entries = 30,082). The guard itself
     * is unchanged by this fix.
     *
     * The hop limit is NOT a lifetime: record age is still bound by TTL
     * (SOS 24 h, check-in 12 h, Missing/Found 72 h, Resource 12 h) — 50
     * hops only buys more route inside the same lifetime.
     */
    const val DEFAULT_HOP_LIMIT = 50
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
