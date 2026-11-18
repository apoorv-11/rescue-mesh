package com.rescuemesh.mesh

/**
 * Server acknowledgements (batch contracts §4): the server signs one ack
 * per accepted record with its sync key; phones gossip verified acks in
 * HELLO so a record the server already has stops spreading.
 *
 * This is the Prompt 5 SEAM only. The Room-backed store (table `server_ack`)
 * and the real verifier (against the cached server public key) arrive with
 * Prompt 9 / GatewaySync; [RelayEngine] is written against this interface
 * so that wave only has to wire a different implementation in.
 */
data class ServerAck(
    val messageId: String,
    /** "ACCEPTED" or "ALREADY_EXISTS". */
    val status: String,
    val issuedAt: Long,
    /** Base64url-no-pad Ed25519 signature by the SERVER sync key over {issuedAt, messageId, status}. */
    val signature: String
)

interface ServerAckStore {
    /** Acks this phone can gossip in HELLO, newest first. */
    suspend fun allAcks(): List<ServerAck>

    /** Records an ack learned from a peer or from the server (idempotent by messageId). */
    suspend fun recordAck(ack: ServerAck)

    /**
     * True only when [ack]'s signature verifies under the cached server
     * public key. Unverifiable acks are ignored by callers (safe default).
     */
    suspend fun isAckVerified(ack: ServerAck): Boolean
}

/**
 * Default until Prompt 9: holds nothing, verifies nothing — peer acks are
 * simply ignored, which the contracts define as the safe default.
 */
class NoOpServerAckStore : ServerAckStore {
    override suspend fun allAcks(): List<ServerAck> = emptyList()
    override suspend fun recordAck(ack: ServerAck) = Unit
    override suspend fun isAckVerified(ack: ServerAck): Boolean = false
}
