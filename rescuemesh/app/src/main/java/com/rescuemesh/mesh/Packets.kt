package com.rescuemesh.mesh

import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.domain.LocalState

/**
 * Relay packets (batch contracts §4): UTF-8 JSON, one Nearby bytes payload
 * each — HELLO / MSG / ACK / DONE, plus PHOTO_CHUNK / PHOTO_ACK for the
 * full-photo transfer (photo-transfer design, 14 Nov 2026).
 *
 * Codec choice: packets are built and parsed with the project's own
 * [JsonParser] + [CanonicalJson] over plain maps — NO new dependency, and
 * the envelope's `hopTrace`/`payload` travel as parsed JSON VALUES, exactly
 * as the contracts require (integers stay integers; canonical JSON has no
 * floats). Encoding is deterministic (canonical form), which also makes
 * packet bytes easy to assert in tests.
 */
sealed interface RelayPacket {
    data class Hello(
        val deviceId: String,
        /** Base64url-no-pad raw Ed25519 public key of [deviceId]'s holder. */
        val publicKey: String,
        val gateway: Boolean,
        val inventory: List<String>,
        val serverAcked: List<ServerAck>,
        /**
         * Photo inventory (photo-transfer design): messageIds whose
         * COMPLETE, hash-verified full photo this phone holds. A peer is
         * offered a photo only for a record it stores and lacks here.
         * Defaults to empty so pre-photo builds' HELLOs still decode.
         */
        val photos: List<String> = emptyList()
    ) : RelayPacket

    data class Msg(val envelope: MessageEntity) : RelayPacket

    data class Ack(
        val messageId: String,
        /** "STORED" | "DUPLICATE" | "REJECTED". */
        val status: String
    ) : RelayPacket

    /**
     * One slice of a full photo (photo-transfer design): [data] is the
     * Base64url of a 16 KiB slice of the JPEG named by [messageId] (=
     * the owning record's id), [index] of [total] slices. The sender
     * closes each round with an END marker — [index] ==
     * [PacketCodec.PHOTO_END_INDEX] and [data] null — at which point the
     * receiver assembles + verifies what it holds and answers with a
     * [PhotoAck]. A photo NEVER rides inside the signed envelope; only
     * its signed `photoRef` does.
     */
    data class PhotoChunk(
        val messageId: String,
        val index: Int,
        val total: Int,
        val data: String?
    ) : RelayPacket {
        val isEndMarker: Boolean get() = index == PacketCodec.PHOTO_END_INDEX
    }

    /**
     * The receiver's answer to an END marker: COMPLETE when the photo
     * assembled and verified against the signed photoRef (the receiver
     * is now a store-and-forward holder), otherwise MISSING with the
     * chunk indices it still needs (the full range when assembly failed
     * verification — resend from scratch).
     */
    data class PhotoAck(
        val messageId: String,
        /** "COMPLETE" | "MISSING". */
        val status: String,
        val missing: List<Int>
    ) : RelayPacket

    data object Done : RelayPacket
}

object PacketCodec {
    const val TYPE_HELLO = "HELLO"
    const val TYPE_MSG = "MSG"
    const val TYPE_ACK = "ACK"
    const val TYPE_DONE = "DONE"
    const val TYPE_PHOTO_CHUNK = "PHOTO_CHUNK"
    const val TYPE_PHOTO_ACK = "PHOTO_ACK"

    const val ACK_STORED = "STORED"
    const val ACK_DUPLICATE = "DUPLICATE"
    const val ACK_REJECTED = "REJECTED"

    const val PHOTO_COMPLETE = "COMPLETE"
    const val PHOTO_MISSING = "MISSING"

    /** HELLO caps from the contracts: at most 200 ids / 200 acks on the wire. */
    const val MAX_INVENTORY = 200
    const val MAX_SERVER_ACKED = 200
    /** HELLO photo inventory cap (photo-transfer design): at most 200 ids. */
    const val MAX_PHOTOS = 200

    /**
     * Photo slice size (photo-transfer design): 16 KiB of JPEG per chunk
     * — ≈ 21 848 Base64url chars, ≈ 22.5 KB on the wire, comfortably
     * inside the 30 000-byte packet guard. A 512 000-byte photo is
     * exactly 32 chunks.
     */
    const val PHOTO_CHUNK_BYTES = 16 * 1024

    /** The index of a round's END marker chunk (carries no data). */
    const val PHOTO_END_INDEX = -1

    /** How many chunks a photo of [sizeBytes] makes; at least 1 for any real photo. */
    fun photoChunkCount(sizeBytes: Int): Int =
        if (sizeBytes <= 0) 0 else (sizeBytes + PHOTO_CHUNK_BYTES - 1) / PHOTO_CHUNK_BYTES

    fun encode(packet: RelayPacket): ByteArray {
        val map: Map<String, Any?> = when (packet) {
            is RelayPacket.Hello -> linkedMapOf(
                "type" to TYPE_HELLO,
                "deviceId" to packet.deviceId,
                "publicKey" to packet.publicKey,
                "gateway" to packet.gateway,
                "inventory" to packet.inventory.take(MAX_INVENTORY),
                "serverAcked" to packet.serverAcked.take(MAX_SERVER_ACKED).map { serverAckToMap(it) },
                "photos" to packet.photos.take(MAX_PHOTOS)
            )
            is RelayPacket.Msg -> linkedMapOf(
                "type" to TYPE_MSG,
                "envelope" to envelopeToMap(packet.envelope)
            )
            is RelayPacket.Ack -> linkedMapOf(
                "type" to TYPE_ACK,
                "messageId" to packet.messageId,
                "status" to packet.status
            )
            is RelayPacket.PhotoChunk -> {
                val fields = linkedMapOf<String, Any?>(
                    "type" to TYPE_PHOTO_CHUNK,
                    "messageId" to packet.messageId,
                    "index" to packet.index,
                    "total" to packet.total
                )
                // The END marker omits `data` entirely (never an empty string).
                packet.data?.let { fields["data"] = it }
                fields
            }
            is RelayPacket.PhotoAck -> linkedMapOf(
                "type" to TYPE_PHOTO_ACK,
                "messageId" to packet.messageId,
                "status" to packet.status,
                "missing" to packet.missing
            )
            RelayPacket.Done -> linkedMapOf("type" to TYPE_DONE)
        }
        return CanonicalJson.canonicalBytes(map)
    }

    /** Decodes one packet; null for anything malformed or unknown (callers ignore it). */
    fun decode(bytes: ByteArray): RelayPacket? {
        val root = try {
            JsonParser.parse(bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return null
        }
        val map = root as? Map<*, *> ?: return null
        return when (map["type"] as? String) {
            TYPE_HELLO -> {
                val deviceId = map["deviceId"] as? String ?: return null
                val publicKey = map["publicKey"] as? String ?: return null
                val inventory = (map["inventory"] as? List<*>)?.map { it as? String ?: return null }
                    ?: return null
                val ackedValues = map["serverAcked"] as? List<*> ?: return null
                val serverAcked = ackedValues.map { serverAckFromValue(it) ?: return null }
                // `photos` is additive: a HELLO from a pre-photo build has
                // no such key and simply advertises no photos.
                val photos = (map["photos"] as? List<*>)?.mapNotNull { it as? String }
                    ?: emptyList()
                RelayPacket.Hello(
                    deviceId = deviceId,
                    publicKey = publicKey,
                    gateway = map["gateway"] as? Boolean ?: false,
                    inventory = inventory,
                    serverAcked = serverAcked,
                    photos = photos
                )
            }
            TYPE_MSG -> {
                val envelope = envelopeFromValue(map["envelope"]) ?: return null
                RelayPacket.Msg(envelope)
            }
            TYPE_ACK -> RelayPacket.Ack(
                messageId = map["messageId"] as? String ?: return null,
                status = map["status"] as? String ?: return null
            )
            TYPE_PHOTO_CHUNK -> {
                val messageId = map["messageId"] as? String ?: return null
                val index = (map["index"] as? Number)?.toInt() ?: return null
                val total = (map["total"] as? Number)?.toInt() ?: return null
                if (total <= 0 || index < PHOTO_END_INDEX) return null
                val data = map["data"] as? String
                // A data chunk must carry data; only the END marker may not.
                if (index != PHOTO_END_INDEX && data == null) return null
                RelayPacket.PhotoChunk(messageId, index, total, data)
            }
            TYPE_PHOTO_ACK -> {
                val missing = (map["missing"] as? List<*>)?.map {
                    (it as? Number)?.toInt() ?: return null
                } ?: emptyList()
                RelayPacket.PhotoAck(
                    messageId = map["messageId"] as? String ?: return null,
                    status = map["status"] as? String ?: return null,
                    missing = missing
                )
            }
            TYPE_DONE -> RelayPacket.Done
            else -> null
        }
    }

    fun serverAckToMap(ack: ServerAck): Map<String, Any?> = linkedMapOf(
        "messageId" to ack.messageId,
        "status" to ack.status,
        "issuedAt" to ack.issuedAt,
        "signature" to ack.signature
    )

    fun serverAckFromValue(value: Any?): ServerAck? {
        val map = value as? Map<*, *> ?: return null
        return ServerAck(
            messageId = map["messageId"] as? String ?: return null,
            status = map["status"] as? String ?: return null,
            issuedAt = (map["issuedAt"] as? Number)?.toLong() ?: return null,
            signature = map["signature"] as? String ?: return null
        )
    }

    /** The 11 envelope fields as a wire map; hopTrace/payload as parsed JSON values. */
    fun envelopeToMap(entity: MessageEntity): Map<String, Any?> = linkedMapOf(
        "messageId" to entity.messageId,
        "eventType" to entity.eventType,
        "originDeviceId" to entity.originDeviceId,
        "createdAt" to entity.createdAt,
        "expiresAt" to entity.expiresAt,
        "priority" to entity.priority,
        "hopLimit" to entity.hopLimit,
        "hopCount" to entity.hopCount,
        "hopTrace" to JsonParser.parse(entity.hopTrace),
        "payload" to JsonParser.parse(entity.payload),
        "signature" to entity.signature
    )

    /**
     * Structural check + rebuild of an envelope from a wire value: all 11
     * fields present with the right types, integer times, priority 1..3,
     * payload an object, hopTrace an array. Returns null on any violation.
     * The rebuilt entity is phone-local bookkeeping only: QUEUED / not own —
     * the relay engine adjusts both when it stores the record.
     */
    fun envelopeFromValue(value: Any?): MessageEntity? {
        val map = value as? Map<*, *> ?: return null
        val messageId = map["messageId"] as? String ?: return null
        val eventType = map["eventType"] as? String ?: return null
        val originDeviceId = map["originDeviceId"] as? String ?: return null
        val createdAt = (map["createdAt"] as? Number)?.toLong() ?: return null
        val expiresAt = (map["expiresAt"] as? Number)?.toLong() ?: return null
        val priority = (map["priority"] as? Number)?.toInt() ?: return null
        if (priority !in 1..3) return null
        val hopLimit = (map["hopLimit"] as? Number)?.toInt() ?: return null
        val hopCount = (map["hopCount"] as? Number)?.toInt() ?: return null
        if (hopCount < 0 || hopCount > hopLimit) return null
        val hopTrace = map["hopTrace"] as? List<*> ?: return null
        val payload = map["payload"] as? Map<*, *> ?: return null
        val signature = map["signature"] as? String ?: return null
        return MessageEntity(
            messageId = messageId,
            eventType = eventType,
            originDeviceId = originDeviceId,
            createdAt = createdAt,
            expiresAt = expiresAt,
            priority = priority,
            hopLimit = hopLimit,
            hopCount = hopCount,
            hopTrace = CanonicalJson.canonicalize(hopTrace),
            payload = CanonicalJson.canonicalize(payload),
            signature = signature,
            localState = LocalState.QUEUED.name,
            isOwn = false
        )
    }
}
