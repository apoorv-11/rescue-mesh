package com.rescuemesh.net

import android.content.Context
import android.content.SharedPreferences
import com.rescuemesh.domain.PayloadLocation
import com.rescuemesh.domain.SavedLocation

/**
 * FIX9 (19 Nov 2026) — the saved last-known location, app-private
 * SharedPreferences (the ServerConfig pattern: configuration, not app
 * data, so deliberately NOT in Room). One fix only; [save] stores it
 * only when it is better than what is already saved (newer wins, a
 * fresh GPS fix beats a same-time last-known — see
 * [SavedLocation.isBetterThan]), so a stale fix can never overwrite a
 * fresher one.
 *
 * Coordinates are stored as strings (Double.toString / toDoubleOrNull)
 * so no precision is lost to float storage; a corrupt / partial entry
 * reads back as null, never as garbage.
 */
class LocationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    /** The saved fix, or null when nothing was ever saved. */
    fun savedLocation(): SavedLocation? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        val source = prefs.getString(KEY_SOURCE, null) ?: PayloadLocation.SOURCE_LAST_KNOWN
        val atMs = prefs.getLong(KEY_AT_MS, 0L)
        return SavedLocation.of(lat, lon, source, atMs)
    }

    /**
     * Saves [location] when it is better than the saved one; returns
     * true when it was stored, false when the saved one was kept.
     */
    fun save(location: SavedLocation): Boolean {
        if (!location.isBetterThan(savedLocation())) return false
        prefs.edit()
            .putString(KEY_LAT, location.lat.toString())
            .putString(KEY_LON, location.lon.toString())
            .putString(KEY_SOURCE, location.source)
            .putLong(KEY_AT_MS, location.atMs)
            .apply()
        return true
    }

    /** Forgets the saved fix (test support / a fresh start). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * FIX11: true once the location permission has been asked for at
     * least once (persisted — it tells a first denial apart from a
     * permanent one: a first denial still shows the system prompt on
     * the next ask, a permanent one never does).
     */
    fun wasPermissionAsked(): Boolean = prefs.getBoolean(KEY_PERMISSION_ASKED, false)

    /** Records that the system permission prompt was shown. */
    fun markPermissionAsked() {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    companion object {
        const val PREF_FILE_NAME = "rescuemesh_location"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_SOURCE = "source"
        private const val KEY_AT_MS = "atMs"
        private const val KEY_PERMISSION_ASKED = "permissionAsked"
    }
}
