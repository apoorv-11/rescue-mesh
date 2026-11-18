package com.rescuemesh.net

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the optional account layer points: the server's base URL, kept in a
 * plain app-private SharedPreferences file — configuration, not app data,
 * so deliberately NOT in Room. Unset (the default) means "not configured":
 * the account layer stays inert and the rest of the app is unaffected.
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    /** The saved, normalized base URL (no trailing slash), or null when none was saved. */
    fun baseUrl(): String? = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    /**
     * Normalizes and saves [raw]. Returns the normalized URL on success, or
     * null when [raw] is invalid — in which case nothing is saved.
     */
    fun saveBaseUrl(raw: String): String? {
        val normalized = normalizeBaseUrl(raw) ?: return null
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        return normalized
    }

    companion object {
        const val PREF_FILE_NAME = "rescuemesh_server_config"
        private const val KEY_BASE_URL = "baseUrl"

        /**
         * Normalizes a user-typed server URL: trims whitespace, strips
         * trailing '/' characters, then validates. Returns null unless the
         * result is an http:// or https:// URL with a non-blank host (a path
         * suffix after the host is kept). Pure — unit-tested.
         */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val scheme = when {
                trimmed.startsWith("http://", ignoreCase = true) -> "http://"
                trimmed.startsWith("https://", ignoreCase = true) -> "https://"
                else -> return null
            }
            val rest = trimmed.substring(scheme.length)
            val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            return scheme + rest
        }
    }
}
