package com.rescuemesh.ui

import com.rescuemesh.data.local.MetricsEntity

/**
 * The three numbers of the Mesh tab's metrics strip (preview: Sent /
 * Relayed / Dropped), counted honestly from the metrics rows the app
 * already writes — no new bookkeeping, no invented events.
 *
 * Mapping (MetricsEntity.event / success / hopCount):
 *
 * - **Sent** — a record THIS phone originated left the phone
 *   successfully: a `RELAY` row with success = true and hopCount = 0
 *   (the record had never hopped before this phone sent it — the
 *   funnel only sends own records at hopCount 0), plus a successful
 *   spike `TRANSFER` row (the Mesh tab's test payload, sent by this
 *   phone).
 * - **Relayed** — this phone forwarded SOMEONE ELSE'S record: a
 *   `RELAY` row with success = true and hopCount ≥ 1 (the record had
 *   already hopped at least once before this phone passed it on).
 * - **Dropped** — a send or receive failed: a `RELAY` row with
 *   success = false (a failed send attempt, a peer's rejection, or an
 *   inbound record this phone refused) or a failed `TRANSFER` row.
 *
 * `DISCOVERY` and `SYNC` rows are deliberately NOT counted: finding a
 * peer and uploading to the server are neither mesh sends nor drops.
 * Pure Kotlin on purpose (StateColors precedent) so the mapping is
 * JVM-unit-tested (MeshCountsTest).
 */
data class MeshCounts(val sent: Int, val relayed: Int, val dropped: Int) {

    companion object {

        val ZERO = MeshCounts(sent = 0, relayed = 0, dropped = 0)

        fun count(rows: List<MetricsEntity>): MeshCounts {
            var sent = 0
            var relayed = 0
            var dropped = 0
            for (row in rows) {
                when (row.event) {
                    "RELAY" -> when {
                        !row.success -> dropped++
                        row.hopCount >= 1 -> relayed++
                        else -> sent++
                    }
                    "TRANSFER" -> if (row.success) sent++ else dropped++
                    else -> Unit // DISCOVERY / SYNC: not message traffic.
                }
            }
            return MeshCounts(sent, relayed, dropped)
        }
    }
}
