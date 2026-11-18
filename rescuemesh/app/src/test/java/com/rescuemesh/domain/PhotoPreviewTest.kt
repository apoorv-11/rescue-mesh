package com.rescuemesh.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The record card's photo-preview decision table (device fix, 14 Nov):
 * every combination of (thumbnail present, complete photo held,
 * photoOmitted) maps to exactly one display source, so a card can
 * never again end up with no picture while the phone holds the photo.
 */
class PhotoPreviewTest {

    @Test
    fun `a present thumbnail always wins — it is the preview every holder has`() {
        assertEquals(
            PhotoPreviewSource.THUMBNAIL,
            PhotoPreview.choose(hasThumbnail = true, hasCompletePhoto = true, photoOmitted = true)
        )
        assertEquals(
            PhotoPreviewSource.THUMBNAIL,
            PhotoPreview.choose(hasThumbnail = true, hasCompletePhoto = true, photoOmitted = false)
        )
        assertEquals(
            PhotoPreviewSource.THUMBNAIL,
            PhotoPreview.choose(hasThumbnail = true, hasCompletePhoto = false, photoOmitted = true)
        )
        assertEquals(
            PhotoPreviewSource.THUMBNAIL,
            PhotoPreview.choose(hasThumbnail = true, hasCompletePhoto = false, photoOmitted = false)
        )
    }

    @Test
    fun `without a thumbnail the held complete photo is the preview — the device defect case`() {
        // The Ishika Avasthi record on device: full photo kept and
        // delivered, but the thumbnail never made it into the payload.
        assertEquals(
            PhotoPreviewSource.FULL_PHOTO,
            PhotoPreview.choose(hasThumbnail = false, hasCompletePhoto = true, photoOmitted = true)
        )
        assertEquals(
            PhotoPreviewSource.FULL_PHOTO,
            PhotoPreview.choose(hasThumbnail = false, hasCompletePhoto = true, photoOmitted = false)
        )
    }

    @Test
    fun `nothing kept and a photo was omitted — the honest note`() {
        assertEquals(
            PhotoPreviewSource.OMITTED_NOTE,
            PhotoPreview.choose(hasThumbnail = false, hasCompletePhoto = false, photoOmitted = true)
        )
    }

    @Test
    fun `no photo at all — or the full photo has not arrived yet — shows nothing`() {
        assertEquals(
            PhotoPreviewSource.NONE,
            PhotoPreview.choose(hasThumbnail = false, hasCompletePhoto = false, photoOmitted = false)
        )
    }
}
