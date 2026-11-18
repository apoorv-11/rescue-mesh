package com.rescuemesh.data.repository

import com.rescuemesh.data.local.PhotoDeliveryDao
import com.rescuemesh.data.local.PhotoDeliveryEntity
import com.rescuemesh.mesh.PhotoDeliveryStore

/**
 * The Room-backed [PhotoDeliveryStore] (photo-transfer design, device
 * fix 14 Nov — fulfils the relay engine's seam the way
 * [RoomServerAckStore] fulfils the Prompt 5 one).
 *
 * Dedupe lives in the table itself: (messageId, peerDeviceId) is the
 * primary key and the DAO inserts with IGNORE, so a peer re-confirming
 * the same photo in a later session never creates a second row and the
 * FIRST confirmed delivery time is the one kept.
 */
class RoomPhotoDeliveryStore(
    private val photoDeliveryDao: PhotoDeliveryDao
) : PhotoDeliveryStore {

    override suspend fun recordPhotoDelivery(
        messageId: String,
        peerDeviceId: String,
        deliveredAtMs: Long
    ) {
        photoDeliveryDao.insertIfAbsent(
            PhotoDeliveryEntity(messageId, peerDeviceId, deliveredAtMs)
        )
    }
}
