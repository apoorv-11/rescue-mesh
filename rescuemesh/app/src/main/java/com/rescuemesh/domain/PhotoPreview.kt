package com.rescuemesh.domain

/** What a Missing / Found record card shows in its photo slot. */
enum class PhotoPreviewSource {
    /** The payload `photoThumb`, decoded — the instant preview that travels with the record. */
    THUMBNAIL,

    /** No usable thumbnail, but this phone holds the COMPLETE full photo — preview from it. */
    FULL_PHOTO,

    /** A photo was picked for the record but nothing could be kept — the honest note. */
    OMITTED_NOTE,

    /** No photo on the record (or the full photo has not arrived yet) — show nothing. */
    NONE
}

/**
 * The record card's photo-preview choice (device fix, 14 Nov): the
 * first builds rendered ONLY the payload thumbnail, so a record whose
 * thumbnail did not survive creation showed no picture anywhere — not
 * even on the phone holding the verified full photo. The priority is:
 * thumbnail first (it is the signed preview every holder of the record
 * has), then the locally held complete photo, then the omitted note.
 *
 * Pure on purpose (booleans in, choice out) so the whole decision table
 * is JVM-unit-tested; the Compose card only decodes what this picks.
 * The raw Base64 string is never a display source.
 */
object PhotoPreview {
    fun choose(
        hasThumbnail: Boolean,
        hasCompletePhoto: Boolean,
        photoOmitted: Boolean
    ): PhotoPreviewSource = when {
        hasThumbnail -> PhotoPreviewSource.THUMBNAIL
        hasCompletePhoto -> PhotoPreviewSource.FULL_PHOTO
        photoOmitted -> PhotoPreviewSource.OMITTED_NOTE
        else -> PhotoPreviewSource.NONE
    }
}
