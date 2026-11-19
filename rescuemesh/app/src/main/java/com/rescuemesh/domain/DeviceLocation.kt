package com.rescuemesh.domain

import com.rescuemesh.crypto.HopTrace

/**
 * FIX9 (19 Nov 2026) — the phone's saved last-known location.
 *
 * One saved fix, kept app-wide in SharedPreferences ([LocationStore]):
 * read at app start (Home), refreshed whenever a newer / better fix
 * arrives (a fresh GPS fix, or the form's Fill from GPS), and read —
 * never awaited — by every record path that embeds location. Nothing
 * ever waits for it: a record with no saved location simply goes
 * without one, exactly as before (SOS never waits for GPS).
 *
 * The fix carries its source with it: "GPS" for a fresh fix, "LAST_KNOWN"
 * for a stored / OS last-known one (the payload contract's [PayloadLocation]
 * sources). Hop entries use the same coordinates via [toHopLoc] — the
 * "<lat>,<lon>" 3-decimal string HopTrace already formats.
 */
data class SavedLocation(
    val lat: Double,
    val lon: Double,
    val source: String,
    val atMs: Long
) {
    /** The payload object: {"lat":"<3dp>","lon":"<3dp>","source":...}. */
    fun toPayloadLocation(): PayloadLocation = PayloadLocation.of(lat, lon, source)

    /** The hop-entry `loc` string: "<lat>,<lon>", 3 decimals. */
    fun toHopLoc(): String = HopTrace.formatLoc(lat, lon)

    /**
     * "Better" decides whether a new fix replaces the saved one: newer
     * wins; at the same timestamp a fresh GPS fix beats a stored
     * last-known. An equal-or-older last-known never overwrites.
     */
    fun isBetterThan(other: SavedLocation?): Boolean {
        if (other == null) return true
        if (atMs != other.atMs) return atMs > other.atMs
        return source == PayloadLocation.SOURCE_GPS &&
                other.source != PayloadLocation.SOURCE_GPS
    }

    companion object {
        /** Null for NaN / out-of-range coordinates — never store garbage. */
        fun of(latitude: Double, longitude: Double, source: String, atMs: Long): SavedLocation? {
            if (latitude.isNaN() || longitude.isNaN()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return SavedLocation(latitude, longitude, source, atMs)
        }
    }
}

/**
 * The seam every record path reads the saved location through: a plain
 * `() -> SavedLocation?`, the same shape as GatewaySync's
 * networkTypeProvider and the other providers in this codebase. The
 * repository, relay engine and gateway sync all take one as an optional
 * constructor parameter (null = the pre-FIX9 behaviour, no location
 * anywhere); production wires `locationStore::savedLocation`, tests pass
 * a lambda.
 */
typealias SavedLocationProvider = () -> SavedLocation?

/**
 * The form GPS-fill choice (FIX9 Kaam 3): a fresh fix wins, otherwise
 * the saved last-known, otherwise nothing. Pure, so the rule is
 * unit-tested without Android.
 */
fun chooseFormLocation(fresh: SavedLocation?, saved: SavedLocation?): SavedLocation? =
    fresh ?: saved

/**
 * FIX11 (19 Nov 2026) — the Home location pill's state. Pure, so the
 * rule is unit-tested without Android:
 * - BLOCKED: permission is permanently denied (Android will not show
 *   the prompt again) — the pill opens Settings instead of asking.
 * - LOCATING: a refresh is running — the pill is inert.
 * - LOCATED: a fix is saved — the pill shows it, tap refreshes it.
 * - GET_LOCATION: nothing saved yet — the pill asks for the permission
 *   (the system popup), then fetches.
 */
enum class LocationPillState { LOCATED, LOCATING, GET_LOCATION, BLOCKED }

fun locationPillState(saved: SavedLocation?, locating: Boolean, blocked: Boolean): LocationPillState =
    when {
        blocked -> LocationPillState.BLOCKED
        locating -> LocationPillState.LOCATING
        saved != null -> LocationPillState.LOCATED
        else -> LocationPillState.GET_LOCATION
    }

/** The pill's two lines (title, subtitle) for a state. */
fun locationPillText(state: LocationPillState, saved: SavedLocation?): Pair<String, String> =
    when (state) {
        LocationPillState.LOCATED -> {
            val coords = saved?.toHopLoc()?.replace(",", ", ") ?: ""
            val source = if (saved?.source == PayloadLocation.SOURCE_GPS) "fresh GPS fix" else "last known"
            coords to "Last known location · $source"
        }
        LocationPillState.LOCATING -> "Locating…" to "Getting a GPS fix…"
        LocationPillState.GET_LOCATION -> "Get location" to "Tap to allow location access"
        LocationPillState.BLOCKED -> "Location is blocked" to "Tap to open Settings"
    }
