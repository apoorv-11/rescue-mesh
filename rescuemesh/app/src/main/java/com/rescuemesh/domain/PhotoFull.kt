package com.rescuemesh.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Full-photo preparation (photo-transfer design §2): the picked image is
 * downscaled / re-encoded in steps until the JPEG is at most [MAX_BYTES]
 * (the user's "up to 500 KB" decision — exactly 512 000 bytes). If it
 * still does not fit at the floor settings (≈800 px, quality 50), the
 * photo is refused and the caller falls back to the thumbnail-only path
 * with a clear form message — a photo problem never blocks the record.
 *
 * The step ladder ([plan]) and the cap check are pure functions so they
 * are JVM-unit-tested; only [encodeFull] touches Android Bitmap APIs.
 */
object PhotoFull {
    /** The 500 KB cap, in bytes — a photo at exactly this size is kept. */
    const val MAX_BYTES = 512_000

    const val START_MAX_SIDE_PX = 1600
    const val FLOOR_MAX_SIDE_PX = 800
    const val START_QUALITY = 80
    const val FLOOR_QUALITY = 50

    /** One rung of the ladder: re-encode at this longest side and JPEG quality. */
    data class Step(val maxSidePx: Int, val quality: Int)

    /**
     * The quality ladder, best first: hold the resolution and step the
     * quality down, then step the resolution down and repeat. The last
     * rung is the floor (≈[FLOOR_MAX_SIDE_PX] px / q[FLOOR_QUALITY]).
     */
    private val LADDER: List<Step> = listOf(
        Step(1600, 80), Step(1600, 65), Step(1600, 50),
        Step(1280, 80), Step(1280, 65), Step(1280, 50),
        Step(1024, 80), Step(1024, 65), Step(1024, 50),
        Step(800, 80), Step(800, 65), Step(800, 50)
    )

    /**
     * The ladder adjusted to a source of [sourceWidth]×[sourceHeight]:
     * never upscale — each rung's side is capped at the source's longest
     * side and duplicate rungs collapse, so a small source gets a short
     * ladder that starts at its native size and still ends at the floor
     * quality.
     */
    fun plan(sourceWidth: Int, sourceHeight: Int): List<Step> {
        val longest = max(sourceWidth, sourceHeight)
        if (longest <= 0) return emptyList()
        return LADDER
            .map { Step(minOf(it.maxSidePx, longest), it.quality) }
            .distinct()
    }

    /** The cap: JPEG bytes are kept only at or under [MAX_BYTES]. */
    fun fitsCap(jpegBytes: ByteArray): Boolean = jpegBytes.size <= MAX_BYTES

    /**
     * Full Android path: decode [imageBytes] (JPEG/PNG from the picker),
     * walk the [plan] ladder and return the first JPEG encoding that fits
     * [MAX_BYTES]; null when the image cannot be decoded or even the
     * floor rung exceeds the cap (the caller then refuses the photo).
     * The decode is sample-sized so a multi-megapixel picker image is
     * never fully materialised just to be downscaled. Runs Bitmap work —
     * call off the main thread.
     */
    fun encodeFull(imageBytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val steps = plan(bounds.outWidth, bounds.outHeight)
        if (steps.isEmpty()) return null

        // Decode once, sampled down so the longest side is at most ~2× the
        // first rung's target — every rung then scales from this master.
        var sampleSize = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > 2 * START_MAX_SIDE_PX && sampleSize < 64) sampleSize *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val master = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
            ?: return null
        try {
            for (step in steps) {
                val (targetW, targetH) = PhotoThumb.scaledDimensions(
                    master.width, master.height, step.maxSidePx
                )
                val scaled = if (master.width == targetW && master.height == targetH) {
                    master
                } else {
                    Bitmap.createScaledBitmap(master, targetW, targetH, true)
                }
                val out = ByteArrayOutputStream()
                val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, out)
                if (scaled !== master) scaled.recycle()
                if (!compressed) continue
                val bytes = out.toByteArray()
                if (fitsCap(bytes)) return bytes
            }
            return null
        } finally {
            master.recycle()
        }
    }
}
