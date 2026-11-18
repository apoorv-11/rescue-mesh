package com.rescuemesh.ui

import com.rescuemesh.data.local.MetricsEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshCountsTest {

    private fun row(event: String, success: Boolean, hopCount: Int = 0) = MetricsEntity(
        event = event,
        messageId = "m",
        peerId = "p",
        startedAt = 0L,
        durationMs = 1L,
        hopCount = hopCount,
        success = success,
        detail = null
    )

    @Test
    fun emptyIsZero() {
        assertEquals(MeshCounts.ZERO, MeshCounts.count(emptyList()))
    }

    @Test
    fun relaySuccessAtHopZeroIsSent() {
        val counts = MeshCounts.count(listOf(row("RELAY", success = true, hopCount = 0)))
        assertEquals(MeshCounts(sent = 1, relayed = 0, dropped = 0), counts)
    }

    @Test
    fun relaySuccessAfterHopsIsRelayed() {
        val counts = MeshCounts.count(
            listOf(
                row("RELAY", success = true, hopCount = 1),
                row("RELAY", success = true, hopCount = 4)
            )
        )
        assertEquals(MeshCounts(sent = 0, relayed = 2, dropped = 0), counts)
    }

    @Test
    fun failuresAreDropped() {
        val counts = MeshCounts.count(
            listOf(
                row("RELAY", success = false, hopCount = 0),
                row("RELAY", success = false, hopCount = 2),
                row("TRANSFER", success = false)
            )
        )
        assertEquals(MeshCounts(sent = 0, relayed = 0, dropped = 3), counts)
    }

    @Test
    fun transferSuccessIsSent() {
        val counts = MeshCounts.count(listOf(row("TRANSFER", success = true)))
        assertEquals(MeshCounts(sent = 1, relayed = 0, dropped = 0), counts)
    }

    @Test
    fun discoveryAndSyncAreNotCounted() {
        val counts = MeshCounts.count(
            listOf(
                row("DISCOVERY", success = true),
                row("DISCOVERY", success = false),
                row("SYNC", success = true),
                row("SYNC", success = false)
            )
        )
        assertEquals(MeshCounts.ZERO, counts)
    }

    @Test
    fun mixedRowsAddUp() {
        val counts = MeshCounts.count(
            listOf(
                row("RELAY", success = true, hopCount = 0),
                row("RELAY", success = true, hopCount = 3),
                row("RELAY", success = false),
                row("TRANSFER", success = true),
                row("TRANSFER", success = false),
                row("SYNC", success = true)
            )
        )
        assertEquals(MeshCounts(sent = 2, relayed = 1, dropped = 2), counts)
    }
}
