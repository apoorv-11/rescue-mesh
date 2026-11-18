package com.rescuemesh.data.repository

import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.data.local.ServerAckDao
import com.rescuemesh.data.local.ServerAckEntity
import com.rescuemesh.mesh.ServerAck
import com.rescuemesh.mesh.ServerAckStore
import com.rescuemesh.net.ServerKeyCache
import java.security.GeneralSecurityException

/**
 * The Room-backed [ServerAckStore] (Prompt 9 — fulfils the Prompt 5 seam).
 *
 * Verification (contracts §4): the ack's signature must verify under the
 * CACHED server public key over the canonical JSON of
 * {issuedAt, messageId, status} — reusing the project's CanonicalJson +
 * EnvelopeSigner.verifierFor, no new canonicalisation path. No cached key
 * (never synced) → nothing verifies (the contracts' safe default).
 *
 * [recordAck] stores ONLY verified acks: RelayEngine calls it for every
 * gossiped ack BEFORE checking [isAckVerified] itself, so gating here is
 * what guarantees the `server_ack` table — and therefore this phone's own
 * HELLO gossip — only ever carries acks the server really signed.
 */
class RoomServerAckStore(
    private val serverAckDao: ServerAckDao,
    private val keyCache: ServerKeyCache
) : ServerAckStore {

    override suspend fun allAcks(): List<ServerAck> =
        serverAckDao.getAll().map { ServerAck(it.messageId, it.status, it.issuedAt, it.signature) }

    override suspend fun recordAck(ack: ServerAck) {
        if (!isAckVerified(ack)) return
        serverAckDao.upsert(ServerAckEntity(ack.messageId, ack.status, ack.issuedAt, ack.signature))
    }

    override suspend fun isAckVerified(ack: ServerAck): Boolean {
        val publicKeyB64 = keyCache.cached()?.second ?: return false
        val rawPublicKey = try {
            DeviceIds.base64UrlDecode(publicKeyB64)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (rawPublicKey.size != DeviceIds.RAW_PUBLIC_KEY_BYTES) return false
        val signature = try {
            DeviceIds.base64UrlDecode(ack.signature)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (signature.size != EnvelopeSigner.SIGNATURE_BYTES) return false
        // The §4 ack signed set: the ack minus `signature`, canonical form.
        val signedFields = linkedMapOf<String, Any?>(
            "issuedAt" to ack.issuedAt,
            "messageId" to ack.messageId,
            "status" to ack.status
        )
        return try {
            EnvelopeSigner.verifierFor(rawPublicKey)
                .verify(signature, CanonicalJson.canonicalBytes(signedFields))
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
