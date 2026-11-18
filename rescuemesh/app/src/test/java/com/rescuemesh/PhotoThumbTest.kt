package com.rescuemesh

import com.rescuemesh.domain.PhotoThumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PhotoThumb pure logic (contracts §7 photo rule): the 320 px longest-side
 * downscale and the 12 000-char encoded cap. The Bitmap work itself is
 * Android-only and not exercised here.
 */
class PhotoThumbTest {

    @Test
    fun `4000x3000 scales to the 320 px cap keeping aspect`() {
        assertEquals(320.0 / 4000.0, PhotoThumb.scaleFactor(4000, 3000), 1e-9)
        assertEquals(320 to 240, PhotoThumb.scaledDimensions(4000, 3000))
    }

    @Test
    fun `portrait image scales on its height`() {
        assertEquals(240 to 320, PhotoThumb.scaledDimensions(3000, 4000))
    }

    @Test
    fun `an image that already fits is never upscaled`() {
        assertEquals(1.0, PhotoThumb.scaleFactor(300, 200), 0.0)
        assertEquals(300 to 200, PhotoThumb.scaledDimensions(300, 200))
        assertEquals(320 to 320, PhotoThumb.scaledDimensions(320, 320))
    }

    @Test
    fun `one pixel over the cap still scales down`() {
        val (w, h) = PhotoThumb.scaledDimensions(321, 100)
        assertEquals(320, w)
        assertEquals(99, h) // floor(100 * 320/321)
    }

    @Test
    fun `the encoded-size cap is inclusive at 12000 chars`() {
        assertTrue(PhotoThumb.fitsCap("a".repeat(PhotoThumb.MAX_ENCODED_CHARS)))
        assertFalse(PhotoThumb.fitsCap("a".repeat(PhotoThumb.MAX_ENCODED_CHARS + 1)))
        assertTrue(PhotoThumb.fitsCap(""))
    }

    @Test
    fun `the fit ladder starts at the historical 320px q70 and steps down to its floor`() {
        val ladder = PhotoThumb.LADDER
        assertEquals(PhotoThumb.ThumbStep(PhotoThumb.MAX_SIDE_PX, PhotoThumb.JPEG_QUALITY), ladder.first())
        assertEquals(PhotoThumb.ThumbStep(128, 35), ladder.last())
        // Never better-later: dimensions and quality are non-increasing
        // rung over rung, so "first fit" really is "best fit".
        assertTrue(ladder.zipWithNext().all { (a, b) -> b.maxSidePx <= a.maxSidePx })
        assertTrue(ladder.zipWithNext().all { (a, b) ->
            b.maxSidePx < a.maxSidePx || b.quality <= a.quality
        })
    }

    @Test
    fun `chooseThumbnail picks the first candidate that fits and skips oversize ones`() {
        val oversize = "a".repeat(PhotoThumb.MAX_ENCODED_CHARS + 1)
        val fits = "b".repeat(9_000)
        val alsoFits = "c".repeat(4_000)
        // The detailed-photo case from the device report: the 320 px / q70
        // candidate busts the cap, a stepped-down one fits, and it (not a
        // later, worse one) is chosen.
        assertEquals(fits, PhotoThumb.chooseThumbnail(listOf(oversize, fits, alsoFits)))
        assertEquals(fits, PhotoThumb.chooseThumbnail(listOf(fits, alsoFits)))
    }

    @Test
    fun `chooseThumbnail returns null when nothing fits — the omitted path`() {
        val oversize = "a".repeat(PhotoThumb.MAX_ENCODED_CHARS + 1)
        assertEquals(null, PhotoThumb.chooseThumbnail(listOf(oversize, oversize)))
        assertEquals(null, PhotoThumb.chooseThumbnail(emptyList()))
    }
}
