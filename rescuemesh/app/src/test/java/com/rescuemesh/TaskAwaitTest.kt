package com.rescuemesh

import com.google.android.gms.tasks.Tasks
import com.rescuemesh.mesh.awaitOrThrow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the Phase 1 gate fix: Nearby failures must surface as exceptions,
 * never be swallowed. Tasks.forResult / forException complete immediately,
 * so these run on the JVM without a phone.
 */
class TaskAwaitTest {

    @Test
    fun awaitOrThrowReturnsValueOnSuccess() = runBlocking {
        val value: String = Tasks.forResult("ok").awaitOrThrow()
        assertEquals("ok", value)
    }

    @Test
    fun awaitOrThrowThrowsTheTaskExceptionOnFailure() {
        val boom = IllegalStateException("advertising failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { Tasks.forException<String>(boom).awaitOrThrow() }
        }
        assertEquals("advertising failed", thrown.message)
    }
}
