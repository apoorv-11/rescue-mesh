package com.rescuemesh.data

import com.rescuemesh.InMemoryPhotoDeliveryDao
import com.rescuemesh.data.local.PhotoDeliveryEntity
import com.rescuemesh.data.repository.RoomPhotoDeliveryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Room-backed photo delivery store (device fix, 14 Nov): a peer's
 * PHOTO_ACK COMPLETE becomes one durable receipt per (record, peer) —
 * re-confirmations in later sessions never duplicate it and never move
 * the first confirmed time.
 */
class PhotoDeliveryStoreTest {

    @Test
    fun `a delivery is stored with its peer and time`() = runBlocking {
        val dao = InMemoryPhotoDeliveryDao()
        val store = RoomPhotoDeliveryStore(dao)
        store.recordPhotoDelivery("msg-1", "peer-b", 1_700_000_100_000L)
        assertEquals(
            listOf(PhotoDeliveryEntity("msg-1", "peer-b", 1_700_000_100_000L)),
            dao.getForMessage("msg-1")
        )
        assertEquals(1, dao.countForMessage("msg-1"))
    }

    @Test
    fun `the same pair recorded again is deduped — first time wins`() = runBlocking {
        val dao = InMemoryPhotoDeliveryDao()
        val store = RoomPhotoDeliveryStore(dao)
        store.recordPhotoDelivery("msg-1", "peer-b", 1_000L)
        store.recordPhotoDelivery("msg-1", "peer-b", 2_000L)
        assertEquals(1, dao.countForMessage("msg-1"))
        assertEquals(1_000L, dao.getForMessage("msg-1").single().deliveredAt)
    }

    @Test
    fun `different peers and different records are separate receipts`() = runBlocking {
        val dao = InMemoryPhotoDeliveryDao()
        val store = RoomPhotoDeliveryStore(dao)
        store.recordPhotoDelivery("msg-1", "peer-b", 1_000L)
        store.recordPhotoDelivery("msg-1", "peer-c", 1_100L)
        store.recordPhotoDelivery("msg-2", "peer-b", 1_200L)
        assertEquals(2, dao.countForMessage("msg-1"))
        assertEquals(1, dao.countForMessage("msg-2"))
        assertEquals(
            listOf("peer-b", "peer-c"),
            dao.getForMessage("msg-1").map { it.peerDeviceId }
        )
    }
}
