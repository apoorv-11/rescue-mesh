package com.rescuemesh.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for Phase 1 (Prompt 1).
 *
 * [MessageEntity] mirrors the mesh envelope. The 11 envelope properties use the
 * EXACT contract names and must never be renamed:
 * messageId, eventType, originDeviceId, createdAt, expiresAt, priority,
 * hopLimit, hopCount, hopTrace, payload, signature.
 * Extra properties (localState, isOwn) are phone-local bookkeeping only and are
 * never serialised into an envelope (that arrives with Prompt 4).
 *
 * Times are epoch milliseconds, on the phone and in the signed envelope
 * alike (Prompt 4 decision: integers only, never ISO strings, so canonical
 * JSON stays unambiguous). hopTrace and payload are stored as JSON text and
 * parsed into values when the envelope is signed/verified (crypto package).
 */

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
    val displayName: String,
    /** One of com.rescuemesh.domain.Role names. Stored as text so a bad value can never crash a read. */
    val role: String,
    /** Stable id of this device in the mesh; becomes originDeviceId on records this phone creates. */
    val deviceId: String,
    val createdAt: Long
) {
    companion object {
        /** The profile table holds exactly one row: the local user's profile (FR-01). */
        const val SINGLE_ID = 1
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["expiresAt"]),
        Index(value = ["localState", "priority", "createdAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val eventType: String,
    val originDeviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: Int,
    val hopLimit: Int,
    val hopCount: Int,
    val hopTrace: String,
    val payload: String,
    val signature: String,
    // ---- phone-local only, never part of the envelope ----
    val localState: String,
    val isOwn: Boolean
)

@Entity(tableName = "peers")
data class PeerEntity(
    /** Nearby endpoint id of the current / most recent connection. */
    @PrimaryKey val endpointId: String,
    /** Advertised name (the peer's "RM-" label since Prompt 4 — no personal data is advertised). */
    val deviceName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastConnectedAt: Long?,
    /** DISCOVERED, CONNECTING, CONNECTED, DISCONNECTED */
    val connectionState: String
)

/**
 * A photo delivery receipt (photo-transfer design, device fix 14 Nov):
 * one row per (record, peer) the first time that peer's PHOTO_ACK
 * COMPLETE confirms it holds the record's complete full photo. Written
 * only through com.rescuemesh.data.repository.RoomPhotoDeliveryStore;
 * the record lists read it to show "Photo delivered ✓" on the sender's
 * card. Phone-local bookkeeping only — never serialised into an
 * envelope or a packet.
 */
@Entity(tableName = "photo_delivery", primaryKeys = ["messageId", "peerDeviceId"])
data class PhotoDeliveryEntity(
    val messageId: String,
    /** The receiving peer's originDeviceId (from its verified HELLO). */
    val peerDeviceId: String,
    /** Epoch ms of the first COMPLETE confirmation from that peer. */
    val deliveredAt: Long
)

/**
 * A server acknowledgement for one record (batch contracts §4/§6, Prompt 9).
 * The server signs one ack per accepted record with its sync key; phones
 * store VERIFIED acks only and gossip them in HELLO so a record the server
 * already has stops spreading. Only ever written through
 * com.rescuemesh.data.repository.RoomServerAckStore, which verifies the
 * signature against the cached server public key before storing.
 */
@Entity(tableName = "server_ack")
data class ServerAckEntity(
    @PrimaryKey val messageId: String,
    /** "ACCEPTED" or "ALREADY_EXISTS" (echoes the /sync/batch result status). */
    val status: String,
    /** Epoch ms when the server issued the ack. */
    val issuedAt: Long,
    /** Base64url-no-pad Ed25519 signature by the server sync key over {issuedAt, messageId, status}. */
    val signature: String
)

/**
 * A gateway photo upload receipt (FIX5): one row per record whose full
 * photo was accepted by POST /sync/photo. Phone-local bookkeeping only
 * — never serialised into an envelope. A missing row means "not uploaded
 * yet" (or "no photo"); failures leave no row, so the next sync retries.
 */
@Entity(tableName = "photo_upload")
data class PhotoUploadEntity(
    @PrimaryKey val messageId: String,
    /** Epoch ms the server accepted the photo. */
    val uploadedAt: Long
)

/**
 * A saved Prepare guide (Prompt 10): one row per guide id the user
 * marked Saved. Phone-local only — guides are bundled content, never
 * synced. Survives app restart (Room, not memory).
 */
@Entity(tableName = "saved_guides")
data class SavedGuideEntity(
    @PrimaryKey val guideId: String,
    /** Epoch ms the guide was saved. */
    val savedAt: Long
)

@Entity(tableName = "metrics")
data class MetricsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** DISCOVERY, TRANSFER, SYNC, ... */
    val event: String,
    val messageId: String?,
    val peerId: String?,
    val startedAt: Long,
    /** Measured duration; transfer time is the ACK round trip / 2 in Phase 2. */
    val durationMs: Long,
    val hopCount: Int,
    val success: Boolean,
    val detail: String?
)
