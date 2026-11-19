package com.rescuemesh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX9 (19 Nov 2026) — the saved last-known location: formatting,
 * the "better fix wins" rule, and the form GPS-fill choice. Pure JVM,
 * no Android.
 */
class DeviceLocationTest {

    private val t0 = 1_700_000_000_000L
    private val lastKnown = SavedLocation(12.971, 77.594, PayloadLocation.SOURCE_LAST_KNOWN, t0)
    private val fresh = SavedLocation(12.971, 77.594, PayloadLocation.SOURCE_GPS, t0 + 60_000)

    @Test
    fun `toPayloadLocation formats to exactly 3 decimals`() {
        val payload = SavedLocation(12.97149, 77.59423, PayloadLocation.SOURCE_GPS, t0)
            .toPayloadLocation()
        assertEquals("12.971", payload.lat)
        assertEquals("77.594", payload.lon)
        assertEquals(PayloadLocation.SOURCE_GPS, payload.source)
    }

    @Test
    fun `toHopLoc formats the hop-entry loc string`() {
        assertEquals("12.971,77.594", lastKnown.toHopLoc())
        assertEquals("-33.865,151.209", SavedLocation(-33.8654, 151.2093, "GPS", t0).toHopLoc())
    }

    @Test
    fun `of rejects NaN and out-of-range coordinates`() {
        assertNull(SavedLocation.of(Double.NaN, 77.594, "GPS", t0))
        assertNull(SavedLocation.of(12.971, Double.NaN, "GPS", t0))
        assertNull(SavedLocation.of(90.001, 77.594, "GPS", t0))
        assertNull(SavedLocation.of(12.971, 180.001, "GPS", t0))
        assertEquals(lastKnown, SavedLocation.of(12.971, 77.594, "LAST_KNOWN", t0))
    }

    @Test
    fun `isBetterThan - newer wins, older never overwrites`() {
        assertTrue(fresh.isBetterThan(lastKnown))
        assertFalse(lastKnown.isBetterThan(fresh))
        assertFalse(lastKnown.isBetterThan(lastKnown))
        assertTrue(lastKnown.isBetterThan(null))
    }

    @Test
    fun `isBetterThan - same timestamp, a fresh GPS fix beats a stored last-known`() {
        val gpsSameTime = SavedLocation(12.971, 77.594, PayloadLocation.SOURCE_GPS, t0)
        assertTrue(gpsSameTime.isBetterThan(lastKnown))
        assertFalse(lastKnown.isBetterThan(gpsSameTime))
    }

    @Test
    fun `chooseFormLocation - fresh wins, else the saved last-known, else nothing`() {
        assertEquals(fresh, chooseFormLocation(fresh, lastKnown))
        assertEquals(lastKnown, chooseFormLocation(null, lastKnown))
        assertEquals(fresh, chooseFormLocation(fresh, null))
        assertNull(chooseFormLocation(null, null))
    }

    @Test
    fun `locationPillState - blocked wins over a saved fix and a running refresh`() {
        assertEquals(LocationPillState.BLOCKED, locationPillState(lastKnown, locating = true, blocked = true))
        assertEquals(LocationPillState.BLOCKED, locationPillState(null, locating = false, blocked = true))
    }

    @Test
    fun `locationPillState - locating, then located, then get location`() {
        assertEquals(LocationPillState.LOCATING, locationPillState(lastKnown, locating = true, blocked = false))
        assertEquals(LocationPillState.LOCATED, locationPillState(lastKnown, locating = false, blocked = false))
        assertEquals(LocationPillState.GET_LOCATION, locationPillState(null, locating = false, blocked = false))
    }

    @Test
    fun `locationPillText - a saved fix shows its coordinates and source`() {
        val (title, subtitle) = locationPillText(LocationPillState.LOCATED, fresh)
        assertEquals("12.971, 77.594", title)
        assertTrue(subtitle.contains("fresh GPS fix"))
        val (title2, subtitle2) = locationPillText(LocationPillState.LOCATED, lastKnown)
        assertEquals("12.971, 77.594", title2)
        assertTrue(subtitle2.contains("last known"))
    }

    @Test
    fun `locationPillText - get location and blocked carry the action`() {
        val (title, _) = locationPillText(LocationPillState.GET_LOCATION, null)
        assertEquals("Get location", title)
        val (bTitle, bSubtitle) = locationPillText(LocationPillState.BLOCKED, null)
        assertEquals("Location is blocked", bTitle)
        assertTrue(bSubtitle.contains("Settings"))
    }
}
