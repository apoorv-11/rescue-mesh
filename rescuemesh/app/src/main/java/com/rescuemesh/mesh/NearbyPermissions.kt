package com.rescuemesh.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permissions Nearby Connections needs, by Android version.
 * Source: the official Nearby Connections "Get started" page
 * (developers.google.com/nearby/connections/android/get-started),
 * checked 2026-11-14. The manifest declares the matching gated sets.
 */
object NearbyPermissions {
    fun requiredRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf( // Android 13+
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        sdkInt >= Build.VERSION_CODES.S -> arrayOf( // Android 12 / 12L
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        sdkInt >= Build.VERSION_CODES.Q -> arrayOf( // Android 10 / 11
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> arrayOf( // Android 8 / 9 (project minSdk is 26)
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /**
     * Pure: which of the permissions required at [sdkInt] are absent from
     * [granted]. Plain strings only (permission names are compile-time
     * constants), so this runs in JVM unit tests with no Android classes.
     * Order follows the required list.
     */
    fun missingPermissions(sdkInt: Int, granted: Set<String>): List<String> =
        requiredRuntimePermissions(sdkInt).filter { it !in granted }

    /**
     * The permissions required on this device that the app does not
     * currently hold. Gate every transport/service start on this being
     * empty: Nearby fails un-granted starts with a raw
     * "8038: MISSING_PERMISSION_…" ApiException.
     */
    fun missingPermissions(context: Context): List<String> {
        val granted = requiredRuntimePermissions()
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
        return missingPermissions(Build.VERSION.SDK_INT, granted)
    }

    /** Short human label for a permission, for the status line. */
    fun friendlyLabel(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertise"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
        else -> permission.substringAfterLast('.')
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    /** "Bluetooth advertise, Bluetooth connect" style list for messages. */
    fun friendlyLabels(permissions: List<String>): String =
        permissions.joinToString(", ") { friendlyLabel(it) }
}
