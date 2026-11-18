package com.rescuemesh.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rescuemesh.crypto.DeviceIds
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.max

/**
 * Missing-person photo thumbnails (contracts §7 photo rule): downscale so
 * the longest side is at most [MAX_SIDE_PX] px, re-encode as JPEG, and
 * include the Base64url string in the payload ONLY when it is at most
 * [MAX_ENCODED_CHARS] chars — otherwise the caller omits `photoThumb` and
 * sets `photoOmitted=true`.
 *
 * Fitting the cap is a LADDER, not a single shot (device fix, 14 Nov):
 * a detailed photo's 320 px / q70 thumbnail can exceed 12 000 chars, and
 * the old single-encode path then produced NO thumbnail at all — the
 * record (e.g. one carrying a full photo) rendered with no picture while
 * "Full photo saved ✓" showed. [encodeThumbnail] now walks [LADDER] —
 * quality down first, then dimensions down — and [chooseThumbnail]
 * picks the first candidate that fits; `photoOmitted` is set only when
 * even the floor rung cannot fit.
 *
 * The decisions (scale factor, scaled size, the size cap, the choice)
 * are pure functions so they are JVM-unit-tested; only [encodeThumbnail]
 * touches Android Bitmap APIs.
 */
object PhotoThumb {
    const val MAX_SIDE_PX = 320
    const val MAX_ENCODED_CHARS = 12_000
    const val JPEG_QUALITY = 70

    /** One rung of the fit ladder: re-encode at this longest side and JPEG quality. */
    data class ThumbStep(val maxSidePx: Int, val quality: Int)

    /**
     * The fit ladder, best first. The first rung (320 px / q70) is the
     * historical single-shot behaviour, so any thumbnail that used to be
     * produced is produced identically; later rungs exist only to rescue
     * detailed photos whose first encoding busts the cap. The last rung
     * (128 px / q35) is the floor: a photo that fits nowhere is omitted.
     */
    val LADDER: List<ThumbStep> = listOf(
        ThumbStep(320, 70), ThumbStep(320, 55), ThumbStep(320, 40),
        ThumbStep(256, 55), ThumbStep(256, 40),
        ThumbStep(192, 50), ThumbStep(192, 35),
        ThumbStep(160, 40),
        ThumbStep(128, 35)
    )

    /**
     * The fit decision, pure: the FIRST candidate whose encoded length
     * is within the §7 cap, or null when none fits (the caller then sets
     * photoOmitted). Candidates are produced best-first by [encodeThumbnail].
     */
    fun chooseThumbnail(candidates: List<String>): String? =
        candidates.firstOrNull { fitsCap(it) }

    /**
     * Scale factor that brings the longest side down to [maxSidePx]:
     * 1.0 when the image already fits (never upscale), otherwise
     * maxSide / longestSide.
     */
    fun scaleFactor(width: Int, height: Int, maxSidePx: Int = MAX_SIDE_PX): Double {
        val longest = max(width, height)
        if (longest <= maxSidePx || longest <= 0) return 1.0
        return maxSidePx.toDouble() / longest.toDouble()
    }

    /** The dimensions after applying [scaleFactor] (each side at least 1 px). */
    fun scaledDimensions(width: Int, height: Int, maxSidePx: Int = MAX_SIDE_PX): Pair<Int, Int> {
        val factor = scaleFactor(width, height, maxSidePx)
        if (factor >= 1.0) return width to height
        return max(1, floor(width * factor).toInt()) to max(1, floor(height * factor).toInt())
    }

    /** The §7 cap: the encoded string is includable only at or under [MAX_ENCODED_CHARS]. */
    fun fitsCap(encoded: String): Boolean = encoded.length <= MAX_ENCODED_CHARS

    /**
     * Full Android path: decode [imageBytes] (JPEG/PNG from the picker)
     * ONCE, then walk [LADDER]: scale to the rung's longest side,
     * re-encode JPEG at the rung's quality, Base64url-encode, and let
     * [chooseThumbnail] decide after each rung (so the walk stops at
     * the first fit and never encodes more than needed). Returns null
     * only when the image cannot be decoded or NO rung fits the cap
     * (the caller then sets photoOmitted=true). Runs Bitmap work — call
     * off the main thread.
     */
    fun encodeThumbnail(imageBytes: ByteArray): String? {
        // Bounds first, so a huge picker image is never fully decoded just
        // to learn its size (the decode below still loads it once, and
        // every ladder rung scales from that one master).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val master = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        try {
            val candidates = mutableListOf<String>()
            for (step in LADDER) {
                val (targetW, targetH) = scaledDimensions(master.width, master.height, step.maxSidePx)
                val scaled = if (master.width == targetW && master.height == targetH) {
                    master
                } else {
                    Bitmap.createScaledBitmap(master, targetW, targetH, true)
                }
                val out = ByteArrayOutputStream()
                val compressed = scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, out)
                if (scaled !== master) scaled.recycle()
                if (!compressed) continue
                candidates += DeviceIds.base64UrlEncode(out.toByteArray())
                chooseThumbnail(candidates)?.let { return it }
            }
            return null
        } finally {
            master.recycle()
        }
    }
}
