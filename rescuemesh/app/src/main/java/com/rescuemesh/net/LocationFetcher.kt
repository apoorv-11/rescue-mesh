package com.rescuemesh.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rescuemesh.domain.PayloadLocation
import com.rescuemesh.domain.SavedLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * FIX9 (19 Nov 2026) — reading the phone's location with LocationManager
 * (no new dependency: no Play Services in this app).
 *
 * Two reads, in the order the callers use them:
 * - [lastKnown]: the OS last-known fix (GPS preferred, else network),
 *   instant and free — what Home saves on every visit.
 * - [freshFix]: one fresh single update from GPS + network, first fix
 *   wins, [FRESH_FIX_TIMEOUT_MS] then null — what Fill from GPS tries
 *   before falling back to the saved location.
 *
 * Both return null without permission or without any fix, and never
 * throw (SecurityException included) — a caller must never crash or
 * block a record on location.
 */
object LocationFetcher {

    /** The runtime permissions a location read needs (fine or coarse). */
    val LOCATION_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** A fresh-fix attempt waits this long before giving up. */
    const val FRESH_FIX_TIMEOUT_MS = 8_000L

    fun hasPermission(context: Context): Boolean =
        LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** The OS last-known fix (newest of GPS / network), or null. Never throws. */
    fun lastKnown(context: Context): SavedLocation? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            val fixes = listOfNotNull(
                runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
                runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            )
            val best = fixes.maxByOrNull { it.time } ?: return null
            SavedLocation.of(
                best.latitude, best.longitude,
                PayloadLocation.SOURCE_LAST_KNOWN, best.time
            )
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * One fresh fix: single updates from GPS and network, first fix
     * wins (source GPS), [timeoutMs] then null. Suspends without
     * blocking; cancelling removes the listener. Never throws.
     */
    suspend fun freshFix(
        context: Context,
        timeoutMs: Long = FRESH_FIX_TIMEOUT_MS
    ): SavedLocation? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val fix = SavedLocation.of(
                            location.latitude, location.longitude,
                            PayloadLocation.SOURCE_GPS, location.time
                        )
                        if (fix != null && cont.isActive) cont.resume(fix)
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                cont.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
                var requested = 0
                runCatching {
                    manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                }.onSuccess { requested++ }
                runCatching {
                    manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                }.onSuccess { requested++ }
                // Neither provider exists on this device: don't wait out
                // the timeout for a fix that can never arrive.
                if (requested == 0 && cont.isActive) cont.resume(null)
            }
        }
    }
}
