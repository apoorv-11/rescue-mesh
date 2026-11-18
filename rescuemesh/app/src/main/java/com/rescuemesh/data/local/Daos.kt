package com.rescuemesh.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun getProfile(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    /** Role-only update of the single profile row (server-role mirror). No schema change — the role column already exists. */
    @Query("UPDATE profile SET role = :role WHERE id = 1")
    suspend fun updateRole(role: String)
}

@Dao
interface MessageDao {
    /**
     * Dedup rule FR-06: messageId is the primary key and inserts use IGNORE,
     * so the same record arriving twice is stored exactly once.
     * Returns the row id, or -1 when the insert was ignored as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY priority ASC, createdAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    /** One record-type list (Prompt 6 lists screen): newest first. */
    @Query("SELECT * FROM messages WHERE eventType = :eventType ORDER BY createdAt DESC")
    fun observeByType(eventType: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /** Relay queue order for later phases: priority first, then oldest first. */
    @Query(
        "SELECT * FROM messages WHERE localState IN ('CREATED','QUEUED','FAILED_RETRYABLE') " +
            "AND expiresAt > :nowMs ORDER BY priority ASC, createdAt ASC LIMIT :limit"
    )
    suspend fun findPendingForRelay(nowMs: Long, limit: Int): List<MessageEntity>

    /**
     * Relay-engine candidates (Prompt 5): like [findPendingForRelay] but
     * PEER_ACKED records are included — a record is not done spreading at
     * one peer's ACK, it is offered onward to other peers too (the engine
     * excludes per-peer the ids that peer already has / has ACKed).
     */
    @Query(
        "SELECT * FROM messages WHERE localState IN ('CREATED','QUEUED','FAILED_RETRYABLE','PEER_ACKED') " +
            "AND expiresAt > :nowMs ORDER BY priority ASC, createdAt ASC LIMIT :limit"
    )
    suspend fun findRelayCandidates(nowMs: Long, limit: Int): List<MessageEntity>

    /**
     * Gateway-sync selection (contracts §6): states CREATED, QUEUED,
     * PEER_ACKED, FAILED_RETRYABLE and not expired, in the shared queue
     * order (priority ASC, then oldest first). Mirrors [findRelayCandidates]'
     * ordering with the sync state set. Terminal states (SERVER_SYNCED,
     * EXPIRED, REJECTED) and in-flight SENDING are never selected.
     */
    @Query(
        "SELECT * FROM messages WHERE localState IN ('CREATED','QUEUED','PEER_ACKED','FAILED_RETRYABLE') " +
            "AND expiresAt > :nowMs ORDER BY priority ASC, createdAt ASC LIMIT :limit"
    )
    suspend fun findPendingForSync(nowMs: Long, limit: Int): List<MessageEntity>

    /** Persists a hop-trace update (the GATEWAY entry GatewaySync appends before upload, §6). */
    @Query("UPDATE messages SET hopTrace = :hopTrace, hopCount = :hopCount WHERE messageId = :messageId")
    suspend fun updateHopTrace(messageId: String, hopTrace: String, hopCount: Int)

    /** Message ids this phone holds, for the HELLO inventory (queue order, capped by the caller). */
    @Query("SELECT messageId FROM messages ORDER BY priority ASC, createdAt ASC LIMIT :limit")
    suspend fun findInventoryIds(limit: Int): List<String>

    /**
     * Full rows behind the HELLO inventory (same queue order, capped):
     * the relay engine filters these to relay-eligible ids before
     * advertising, so records no peer could ever accept (legacy
     * pre-identity records) are never offered.
     */
    @Query("SELECT * FROM messages ORDER BY priority ASC, createdAt ASC LIMIT :limit")
    suspend fun findInventory(limit: Int): List<MessageEntity>

    /** SOS origination guard (contracts §4.9): own SOS created strictly after [sinceMs]. */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE isOwn = 1 AND eventType = 'SOS' " +
            "AND originDeviceId = :originDeviceId AND createdAt > :sinceMs"
    )
    suspend fun countOwnSosSince(originDeviceId: String, sinceMs: Long): Int

    /**
     * TTL purge (contracts §4.8): records whose TTL has passed move to
     * EXPIRED (marked, not deleted). Terminal SERVER_SYNCED / REJECTED
     * records are never touched.
     */
    @Query(
        "UPDATE messages SET localState = 'EXPIRED' WHERE expiresAt <= :nowMs " +
            "AND localState IN ('CREATED','QUEUED','SENDING','PEER_ACKED','FAILED_RETRYABLE')"
    )
    suspend fun markExpired(nowMs: Long): Int

    /** All records the TTL purge may expire — the engine applies TTL test mode on top. */
    @Query(
        "SELECT * FROM messages WHERE localState IN ('CREATED','QUEUED','SENDING','PEER_ACKED','FAILED_RETRYABLE')"
    )
    suspend fun findPurgeable(): List<MessageEntity>

    /** Crash/restart hygiene: a record left SENDING by a dead engine goes back to QUEUED. */
    @Query("UPDATE messages SET localState = 'QUEUED' WHERE localState = 'SENDING'")
    suspend fun resetSendingToQueued(): Int

    @Query("UPDATE messages SET localState = :state WHERE messageId = :messageId")
    suspend fun updateState(messageId: String, state: String)

    @Query("DELETE FROM messages WHERE expiresAt <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int
}

@Dao
interface PeerDao {
    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE endpointId = :endpointId")
    suspend fun getByEndpointId(endpointId: String): PeerEntity?

    @Query("UPDATE peers SET connectionState = :state, lastSeenAt = :seenAt WHERE endpointId = :endpointId")
    suspend fun updateState(endpointId: String, state: String, seenAt: Long)
}

@Dao
interface PhotoDeliveryDao {
    /**
     * Insert-or-ignore: the (messageId, peerDeviceId) pair is the primary
     * key, so a peer re-confirming the same photo in a later session is
     * a no-op and the FIRST delivery time is kept. Returns the row id,
     * or -1 when the insert was ignored as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(delivery: PhotoDeliveryEntity): Long

    /** Every receipt, newest first — the record lists reduce this to the delivered messageId set. */
    @Query("SELECT * FROM photo_delivery ORDER BY deliveredAt DESC")
    fun observeAll(): Flow<List<PhotoDeliveryEntity>>

    @Query("SELECT * FROM photo_delivery WHERE messageId = :messageId ORDER BY deliveredAt ASC")
    suspend fun getForMessage(messageId: String): List<PhotoDeliveryEntity>

    @Query("SELECT COUNT(*) FROM photo_delivery WHERE messageId = :messageId")
    suspend fun countForMessage(messageId: String): Int
}

@Dao
interface ServerAckDao {
    /** Insert-or-replace: one ack per messageId; re-recording the same ack is a no-op in effect. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ack: ServerAckEntity)

    /** All stored (verified) acks, newest first — the HELLO gossip set (contracts §4 caps it at 200). */
    @Query("SELECT * FROM server_ack ORDER BY issuedAt DESC LIMIT 200")
    suspend fun getAll(): List<ServerAckEntity>

    @Query("SELECT * FROM server_ack WHERE messageId = :messageId")
    suspend fun getById(messageId: String): ServerAckEntity?
}

@Dao
interface MetricsDao {
    @Insert
    suspend fun insert(metric: MetricsEntity): Long

    @Query("SELECT * FROM metrics ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MetricsEntity>>

    @Query("SELECT * FROM metrics ORDER BY startedAt ASC")
    suspend fun getAllForExport(): List<MetricsEntity>
}
