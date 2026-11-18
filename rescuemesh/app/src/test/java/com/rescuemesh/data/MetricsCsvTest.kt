package com.rescuemesh.data

import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.data.repository.metricsToCsv
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Prompt 9 metrics CSV export format: fixed header + basic quoting. */
class MetricsCsvTest {

    @Test
    fun `header and plain rows`() {
        val csv = metricsToCsv(
            listOf(
                MetricsEntity(
                    id = 1, event = "RELAY", messageId = "m1", peerId = "p1",
                    startedAt = 100, durationMs = 42, hopCount = 2, success = true, detail = "STORED"
                )
            )
        )
        assertEquals(
            "id,event,messageId,peerId,startedAt,durationMs,hopCount,success,detail\n" +
                "1,RELAY,m1,p1,100,42,2,true,STORED\n",
            csv
        )
    }

    @Test
    fun `nulls are empty and commas quotes newlines are quoted`() {
        val csv = metricsToCsv(
            listOf(
                MetricsEntity(
                    id = 2, event = "SYNC", messageId = null, peerId = null,
                    startedAt = 0, durationMs = 0, hopCount = 0, success = false,
                    detail = "failed, \"bad\"\nreason"
                )
            )
        )
        assertEquals(
            "id,event,messageId,peerId,startedAt,durationMs,hopCount,success,detail\n" +
                "2,SYNC,,,0,0,0,false,\"failed, \"\"bad\"\"\nreason\"\n",
            csv
        )
    }

    @Test
    fun `empty input is just the header`() {
        assertEquals(
            "id,event,messageId,peerId,startedAt,durationMs,hopCount,success,detail\n",
            metricsToCsv(emptyList())
        )
    }
}
