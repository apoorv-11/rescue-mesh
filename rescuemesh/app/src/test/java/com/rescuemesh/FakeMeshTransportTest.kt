package com.rescuemesh

import com.rescuemesh.mesh.FakeMeshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMeshTransportTest {

    @Test
    fun `send to unconnected peer fails, send after connect succeeds and loops back`() = runBlocking {
        val transport = FakeMeshTransport()
        transport.start("device-a")
        assertTrue(transport.isRunning.value)
        assertEquals(1, transport.peers.value.size)

        val failed = transport.send("fake-endpoint-1", "hi".toByteArray())
        assertFalse(failed.success)

        transport.connect("fake-endpoint-1")
        // Subscribe before sending: the fake echoes sends back through
        // `incoming`, like a real link delivering to a registered callback.
        val subscribed = CompletableDeferred<Unit>()
        val incoming = async {
            withTimeout(5_000) {
                transport.incoming.onSubscription { subscribed.complete(Unit) }.first()
            }
        }
        // Wait until the collector is actually subscribed (condition-based;
        // a fixed sleep raced under load).
        assertTrue(awaitCondition { subscribed.isCompleted })
        val ok = transport.send("fake-endpoint-1", "hello mesh".toByteArray())
        assertTrue(ok.success)
        assertEquals("hello mesh", incoming.await().bytes.toString(Charsets.UTF_8))
        assertEquals(1, transport.sentPayloads.size)

        transport.stop()
        assertFalse(transport.isRunning.value)
        assertTrue(transport.peers.value.isEmpty())
    }
}
