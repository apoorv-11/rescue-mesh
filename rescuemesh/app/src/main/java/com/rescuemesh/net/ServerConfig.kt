package com.rescuemesh.net

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the account / gateway layer points: the server's base URL, kept in
 * a plain app-private SharedPreferences file — configuration, not app data,
 * so deliberately NOT in Room.
 *
 * FIX5 (19 Nov 2026): a fresh install has NO saved URL, and a new user
 * must never have to type one — so when nothing is saved, [baseUrl]
 * returns the public Render default ([DEFAULT_BASE_URL]) instead of
 * null. The URL field stays editable: saving any URL overrides the
 * default, and [clearCustomUrl] goes back to it. Nothing else changes:
 * SOS, mesh and sync never consult the account layer, and sync still
 * works without login (device-token fallback in GatewaySync).
 */
class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The effective base URL (no trailing slash): the saved custom URL
     * when one exists, otherwise the Render default. Never null.
     */
    fun baseUrl(): String = effectiveBaseUrl(prefs.getString(KEY_BASE_URL, null))

    /** True when no custom URL is saved, i.e. the Render default is in use. */
    fun isUsingDefault(): Boolean =
        prefs.getString(KEY_BASE_URL, null).isNullOrBlank()

    /**
     * Normalizes and saves [raw]. Returns the normalized URL on success, or
     * null when [raw] is invalid — in which case nothing is saved.
     */
    fun saveBaseUrl(raw: String): String? {
        val normalized = normalizeBaseUrl(raw) ?: return null
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        return normalized
    }

    /** Forgets a saved custom URL; the Render default applies again. */
    fun clearCustomUrl() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }

    companion object {
        const val PREF_FILE_NAME = "rescuemesh_server_config"
        private const val KEY_BASE_URL = "baseUrl"

        /**
         * FIX5 default — the public Render deployment, verified live
         * 19 Nov 2026: GET /health returns {"status":"UP"} and
         * GET /sync/pubkey returns a fixed (non-ephemeral) keyId.
         * This is the ONLY place the default lives; never hard-code
         * the URL anywhere else.
         */
        const val DEFAULT_BASE_URL = "https://rescue-mesh-m0n0.onrender.com"

        /**
         * Pure effective-URL rule, unit-tested: a saved (already
         * normalized) URL wins; blank / absent falls back to the
         * Render default. Never returns null.
         */
        fun effectiveBaseUrl(saved: String?): String =
            saved?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

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
