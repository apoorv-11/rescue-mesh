package com.rescuemesh.mesh

import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs GMS listener callbacks inline, on whatever thread completes the task. */
private val directExecutor = Executor { command -> command.run() }

/**
 * Awaits a Google Play services [Task] and THROWS its exception on failure.
 *
 * This exists because of a Phase 1 gate finding: fire-and-forget listener
 * code in NearbyTransport swallowed Nearby failures (advertising/discovery
 * could fail while the UI still showed the mesh as running). Every Nearby
 * call that decides success or failure now goes through this bridge, so
 * `runCatching` in the ViewModel actually catches real errors.
 */
suspend fun <T> Task<T>.awaitOrThrow(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener(directExecutor) { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener(directExecutor) { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener(directExecutor) {
        if (cont.isActive) cont.cancel()
    }
}
