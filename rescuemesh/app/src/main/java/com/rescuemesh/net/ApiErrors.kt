package com.rescuemesh.net

import kotlinx.serialization.json.Json

/**
 * Outcome of one account-API call. The account layer is optional and the
 * server is often asleep, off, or on another network — so "could not reach
 * it" is a normal, distinct result, never an exception escaping upwards.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** The server answered with a non-2xx status; [message] is its own wording when it sent one. */
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>

    /** No HTTP answer at all (offline, wrong URL, server down/asleep past the timeout). */
    data class Unreachable(val detail: String) : ApiResult<Nothing>
}

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Extracts the message from the server's `{"error": "..."}` body.
 * Returns null for blank bodies, non-JSON bodies, or JSON without a usable
 * error field — the caller then falls back to "HTTP <code>". Pure; tested.
 */
fun parseServerError(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching { errorJson.decodeFromString<ErrorBody>(body).error }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}
