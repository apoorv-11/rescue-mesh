package com.rescuemesh.mesh

/**
 * Photo delivery receipts (photo-transfer design, device fix 14 Nov):
 * when a peer's PHOTO_ACK COMPLETE confirms it holds a record's full
 * photo, the sender records that fact here so the record's card can say
 * "Photo delivered ✓" — without it, the sender could see the chunks go
 * out but never whether the PHOTO (not just the record) arrived.
 *
 * The seam mirrors [ServerAckStore]: [RelayEngine] is written against
 * this interface and defaults to [NoOpPhotoDeliveryStore], so tests and
 * pre-photo wiring are untouched; production wires the Room-backed
 * implementation (table `photo_delivery`). Recording is idempotent by
 * (messageId, peerDeviceId) — one receipt per record per peer, however
 * many sessions re-confirm it.
 */
interface PhotoDeliveryStore {
    /** Records that [peerDeviceId] confirmed holding the complete photo of [messageId]. */
    suspend fun recordPhotoDelivery(messageId: String, peerDeviceId: String, deliveredAtMs: Long)
}

/** Default: remembers nothing — delivery receipts simply do not exist yet. */
class NoOpPhotoDeliveryStore : PhotoDeliveryStore {
    override suspend fun recordPhotoDelivery(
        messageId: String,
        peerDeviceId: String,
        deliveredAtMs: Long
    ) = Unit
}
