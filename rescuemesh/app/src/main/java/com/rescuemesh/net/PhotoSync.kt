package com.rescuemesh.net

import com.rescuemesh.data.local.MessageDao
import com.rescuemesh.data.local.PhotoUploadDao
import com.rescuemesh.data.local.PhotoUploadEntity
import com.rescuemesh.domain.PhotoRef
import com.rescuemesh.domain.PhotoStore

/**
 * Gateway photo upload (FIX5, requirements §"PHOTO UPLOAD").
 *
 * Audit, 19 Nov 2026: this did NOT exist in FIX4. Records reached the
 * server via /sync/batch, but a record's full photo only ever travelled
 * phone-to-phone (PHOTO_CHUNK) and sat in [PhotoStore] — the dashboard
 * case view could only ever show the small `photoThumb` from the
 * payload, never the real photo.
 *
 * Rules, in order:
 * 1. Record first, photo behind: only records already SERVER_SYNCED are
 *    candidates. A photo failure NEVER changes a record's state and
 *    NEVER blocks or delays the record batch — this runs after it.
 * 2. Authenticated by the origin: the uploaded bytes are checked
 *    against the signed `photoRef` (SHA-256 + size) from the record's
 *    own payload before upload, so a corrupt local file is skipped,
 *    never sent.
 * 3. One upload per record: success is recorded in `photo_upload`
 *    (Room) and never re-sent. Failures stay unmarked and retry on a
 *    later sync run.
 * 4. Server not ready yet: 404/405 means the server has not
 *    implemented POST /sync/photo — stop this run's photo phase (do
 *    not hammer it with every photo) and report it; the next run
 *    tries again. 401 also stops the run (the token is the problem,
 *    not the photos).
 *
 * Android-free apart from injected providers, so the whole flow is
 * JVM-testable with a fake [SyncApi] and in-memory DAOs.
 */
class PhotoSync(
    private val api: SyncApi,
    private val messageDao: MessageDao,
    private val uploadDao: PhotoUploadDao,
    /** Complete JPEG bytes this phone holds for [messageId], or null. */
    private val photoBytes: (String) -> ByteArray?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    data class Summary(
        val uploaded: Int,
        val failed: Int,
        val unsupported: Int,
        val checked: Int
    ) {
        /** True when the phase did anything worth reporting. */
        val hasActivity: Boolean get() = uploaded > 0 || failed > 0 || unsupported > 0
    }

    suspend fun uploadPendingPhotos(authorization: String): Summary {
        var uploaded = 0
        var failed = 0
        var unsupported = 0
        var checked = 0
        val candidates = messageDao.findSynced(BATCH)
        for (entity in candidates) {
            if (uploadDao.countForMessage(entity.messageId) > 0) continue
            val ref = PhotoRef.parse(entity.payload) ?: continue
            val bytes = photoBytes(ref.photoId) ?: continue
            checked++
            // The signed ref is the authority: size + digest must match.
            if (bytes.size != ref.sizeBytes || PhotoStore.sha256Hex(bytes) != ref.sha256) continue
            when (val result = api.uploadPhoto(authorization, ref.photoId, ref.sha256, bytes)) {
                is ApiResult.Success -> {
                    uploadDao.upsert(PhotoUploadEntity(ref.photoId, nowMs()))
                    uploaded++
                }
                is ApiResult.HttpError -> when (result.code) {
                    404, 405 -> {
                        // Server has no photo endpoint yet: stop here,
                        // report it once, retry on the next sync run.
                        unsupported++
                        break
                    }
                    401 -> {
                        failed++
                        break
                    }
                    else -> failed++
                }
                is ApiResult.Unreachable -> failed++
            }
        }
        return Summary(uploaded, failed, unsupported, checked)
    }

    companion object {
        const val BATCH = 50
    }
}
