package com.rescuemesh.ui

/**
 * The single source of record-state colours (UI redesign spec, 14 Nov).
 *
 * Every place that shows a record's `localState` — Records cards and
 * Mesh rows alike — takes its chip colours from here, so a state means
 * the same colour everywhere in the app:
 *
 * | State            | Chip     | Background |
 * |------------------|----------|------------|
 * | CREATED          | slate    | #F1F5F9    |
 * | QUEUED           | amber    | #FEF3C7    |
 * | SENDING          | blue     | #DBEAFE    |
 * | PEER_ACKED       | green    | #D1FAE5    |
 * | SERVER_SYNCED    | violet   | #EDE9FE    |
 * | FAILED_RETRYABLE | orange   | #FFEDD5    |
 * | EXPIRED          | grey     | #F3F4F6    |
 * | REJECTED         | red      | #FEE2E2    |
 *
 * Values are ARGB longs (0xFFRRGGBB) — deliberately NOT Compose `Color`
 * objects, so this mapping stays pure Kotlin and JVM-unit-testable; the
 * Compose layer wraps them in `Color(...)`. The border colour is the
 * chip colour: cards also get a 4 dp left border in it.
 *
 * An unknown state falls back to the CREATED slate — a state this build
 * does not know must still render calmly, never crash a list.
 */
data class StateChipColors(
    /** Chip text colour; also the card's left-border colour. */
    val foreground: Long,
    /** Chip background tint. */
    val background: Long,
    /** Border colour (the chip colour, per the spec). */
    val border: Long
)

object StateColors {

    /** The states with a defined mapping, in lifecycle order. */
    val KNOWN_STATES: List<String> = listOf(
        "CREATED", "QUEUED", "SENDING", "PEER_ACKED",
        "SERVER_SYNCED", "FAILED_RETRYABLE", "EXPIRED", "REJECTED"
    )

    private val CREATED = StateChipColors(0xFF64748B, 0xFFF1F5F9, 0xFF64748B)

    fun forState(localState: String): StateChipColors = when (localState) {
        "CREATED" -> CREATED
        "QUEUED" -> StateChipColors(0xFFB45309, 0xFFFEF3C7, 0xFFB45309)
        "SENDING" -> StateChipColors(0xFF2563EB, 0xFFDBEAFE, 0xFF2563EB)
        "PEER_ACKED" -> StateChipColors(0xFF0E9F6E, 0xFFD1FAE5, 0xFF0E9F6E)
        "SERVER_SYNCED" -> StateChipColors(0xFF6D28D9, 0xFFEDE9FE, 0xFF6D28D9)
        "FAILED_RETRYABLE" -> StateChipColors(0xFFEA580C, 0xFFFFEDD5, 0xFFEA580C)
        "EXPIRED" -> StateChipColors(0xFF6B7280, 0xFFF3F4F6, 0xFF6B7280)
        "REJECTED" -> StateChipColors(0xFFDC2626, 0xFFFEE2E2, 0xFFDC2626)
        else -> CREATED
    }
}
