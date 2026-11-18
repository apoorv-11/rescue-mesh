package com.rescuemesh.mesh

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure missing-permissions logic behind the 8038 gate
 * (AppViewModel.permissionsReadyOrExplain). Permission names are
 * compile-time constants, so these run on the JVM with no Android
 * runtime — the Context variant is a thin checkSelfPermission wrapper
 * over exactly this function.
 */
class NearbyPermissionsTest {

    private val btScan = Manifest.permission.BLUETOOTH_SCAN
    private val btAdvertise = Manifest.permission.BLUETOOTH_ADVERTISE
    private val btConnect = Manifest.permission.BLUETOOTH_CONNECT
    private val nearbyWifi = Manifest.permission.NEARBY_WIFI_DEVICES
    private val fineLocation = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarseLocation = Manifest.permission.ACCESS_COARSE_LOCATION

    @Test
    fun `api 33 nothing granted - all four missing in required order`() {
        val missing = NearbyPermissions.missingPermissions(Build.VERSION_CODES.TIRAMISU, emptySet())
        assertEquals(listOf(btScan, btAdvertise, btConnect, nearbyWifi), missing)
    }

    @Test
    fun `api 33 all granted - nothing missing`() {
        val granted = setOf(btScan, btAdvertise, btConnect, nearbyWifi)
        assertTrue(NearbyPermissions.missingPermissions(Build.VERSION_CODES.TIRAMISU, granted).isEmpty())
    }

    @Test
    fun `api 33 partial grant - correct missing subset`() {
        // The fresh-install 8038 case: the user allowed some, not all.
        val granted = setOf(btScan, btConnect)
        val missing = NearbyPermissions.missingPermissions(Build.VERSION_CODES.TIRAMISU, granted)
        assertEquals(listOf(btAdvertise, nearbyWifi), missing)
    }

    @Test
    fun `api 31 band uses location instead of nearby wifi`() {
        val missing = NearbyPermissions.missingPermissions(Build.VERSION_CODES.S, emptySet())
        assertEquals(listOf(btScan, btAdvertise, btConnect, fineLocation), missing)
    }

    @Test
    fun `api 31 all granted - nothing missing`() {
        val granted = setOf(btScan, btAdvertise, btConnect, fineLocation)
        assertTrue(NearbyPermissions.missingPermissions(Build.VERSION_CODES.S, granted).isEmpty())
    }

    @Test
    fun `api 29 needs only fine location`() {
        assertEquals(
            listOf(fineLocation),
            NearbyPermissions.missingPermissions(Build.VERSION_CODES.Q, emptySet())
        )
        assertTrue(
            NearbyPermissions.missingPermissions(Build.VERSION_CODES.Q, setOf(fineLocation)).isEmpty()
        )
    }

    @Test
    fun `api 28 needs only coarse location`() {
        assertEquals(
            listOf(coarseLocation),
            NearbyPermissions.missingPermissions(Build.VERSION_CODES.P, emptySet())
        )
    }

    @Test
    fun `unrelated grants do not count`() {
        val granted = setOf(Manifest.permission.INTERNET, Manifest.permission.POST_NOTIFICATIONS)
        val missing = NearbyPermissions.missingPermissions(Build.VERSION_CODES.TIRAMISU, granted)
        assertEquals(listOf(btScan, btAdvertise, btConnect, nearbyWifi), missing)
    }

    @Test
    fun `friendly labels for every gated permission`() {
        assertEquals("Bluetooth advertise", NearbyPermissions.friendlyLabel(btAdvertise))
        assertEquals("Bluetooth connect", NearbyPermissions.friendlyLabel(btConnect))
        assertEquals("Bluetooth scan", NearbyPermissions.friendlyLabel(btScan))
        assertEquals("Nearby Wi-Fi devices", NearbyPermissions.friendlyLabel(nearbyWifi))
        assertEquals("Location", NearbyPermissions.friendlyLabel(fineLocation))
        assertEquals("Location", NearbyPermissions.friendlyLabel(coarseLocation))
    }

    @Test
    fun `friendly labels join for the status line`() {
        assertEquals(
            "Bluetooth advertise, Bluetooth connect",
            NearbyPermissions.friendlyLabels(listOf(btAdvertise, btConnect))
        )
        assertEquals("", NearbyPermissions.friendlyLabels(emptyList()))
    }
}
