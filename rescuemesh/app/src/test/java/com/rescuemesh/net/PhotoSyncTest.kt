package com.rescuemesh.net

import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.InMemoryPhotoUploadDao
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PhotoRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gateway photo upload (FIX5): record first, photo behind. Only
 * SERVER_SYNCED records are candidates; the bytes are checked against
 * the signed photoRef before upload; success is recorded once and
 * never re-sent; a photo failure never touches the record's state.
 */
class PhotoSyncTest {

    private val clock = 1_700_000_000_000L
    private val jpeg = ByteArray(1024) { (it % 251).toByte() }

    private class FakeApi(
        var onPhoto: (String) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    ) : SyncApi {
        val photoCalls = mutableListOf<String>()
        override suspend fun syncPubkey(): ApiResult<SyncPubkeyResponse> =
            throw UnsupportedOperationException("not used here")

        override suspend fun syncBatch(
            authorization: String,
            request: SyncBatchRequest
        ): ApiResult<SyncBatchResponse> =
            throw UnsupportedOperationException("not used here")

        override suspend fun deviceToken(deviceId: String): ApiResult<TokenResponse> =
            throw UnsupportedOperationException("not used here")

        override suspend fun uploadPhoto(
            authorization: String,
            messageId: String,
            sha256: String,
            jpegBytes: ByteArray
        ): ApiResult<Unit> {
            photoCalls += messageId
            return onPhoto(messageId)
        }
    }

    /** A SERVER_SYNCED Missing record whose payload carries a photoRef for [jpeg]. */
    private fun syncedRecordWithPhoto(messageDao: InMemoryMessageDao, id: String) {
        val ref = PhotoRef.of(id, jpeg)
        val factory = MessageFactory(nowMs = { clock }, newId = { id })
        val entity = factory.createMissingPerson(
            originDeviceId = "origin-device",
            description = "Test person",
            lastSeenArea = "Test area",
            lastSeenAtMs = clock,
            photoRef = ref
        ).copy(localState = "SERVER_SYNCED")
        runBlocking { messageDao.insertIfAbsent(entity) }
    }

    private fun sync(
        api: FakeApi,
        messageDao: InMemoryMessageDao,
        uploadDao: InMemoryPhotoUploadDao,
        bytes: (String) -> ByteArray? = { jpeg }
    ) = PhotoSync(
        api = api,
        messageDao = messageDao,
        uploadDao = uploadDao,
        photoBytes = bytes,
        nowMs = { clock }
    )

    @Test
    fun `uploads a synced photo once and records the receipt`() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val uploadDao = InMemoryPhotoUploadDao()
        val api = FakeApi()
        syncedRecordWithPhoto(messageDao, "msg-1")

        val first = sync(api, messageDao, uploadDao).uploadPendingPhotos("Bearer t")
        assertEquals(1, first.uploaded)
        assertEquals(1, api.photoCalls.size)
        assertEquals(1, uploadDao.countForMessage("msg-1"))

        // Second run: already uploaded, never re-sent.
        val second = sync(api, messageDao, uploadDao).uploadPendingPhotos("Bearer t")
        assertEquals(0, second.uploaded)
        assertEquals(1, api.photoCalls.size)
    }

    @Test
    fun `a photo failure leaves the record SERVER_SYNCED and retries later`() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val uploadDao = InMemoryPhotoUploadDao()
        val api = FakeApi { ApiResult.HttpError(500, "boom") }
        syncedRecordWithPhoto(messageDao, "msg-1")

        val summary = sync(api, messageDao, uploadDao).uploadPendingPhotos("Bearer t")
        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.failed)
        assertEquals(0, uploadDao.countForMessage("msg-1"))
        assertEquals("SERVER_SYNCED", messageDao.getById("msg-1")!!.localState)
    }

    @Test
    fun `404 stops the run and marks the server as not supporting photos yet`() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val uploadDao = InMemoryPhotoUploadDao()
        val api = FakeApi { ApiResult.HttpError(404, "not found") }
        syncedRecordWithPhoto(messageDao, "msg-1")
        syncedRecordWithPhoto(messageDao, "msg-2")

        val summary = sync(api, messageDao, uploadDao).uploadPendingPhotos("Bearer t")
        assertEquals(1, summary.unsupported)
        // Only the first photo was attempted — no hammering.
        assertEquals(1, api.photoCalls.size)
        assertEquals(0, uploadDao.countForMessage("msg-1"))
    }

    @Test
    fun `bytes that do not match the signed ref are never uploaded`() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val uploadDao = InMemoryPhotoUploadDao()
        val api = FakeApi()
        syncedRecordWithPhoto(messageDao, "msg-1")

        val summary = sync(api, messageDao, uploadDao, bytes = { ByteArray(512) { 7 } })
            .uploadPendingPhotos("Bearer t")
        assertEquals(0, summary.uploaded)
        assertTrue(api.photoCalls.isEmpty())
    }

    @Test
    fun `records without a local photo are skipped silently`() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val uploadDao = InMemoryPhotoUploadDao()
        val api = FakeApi()
        syncedRecordWithPhoto(messageDao, "msg-1")

        val summary = sync(api, messageDao, uploadDao, bytes = { null })
            .uploadPendingPhotos("Bearer t")
        assertEquals(0, summary.uploaded)
        assertTrue(api.photoCalls.isEmpty())
    }
}
