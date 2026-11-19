package com.rescuemesh

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.PredefinedSignatureParameters
import com.google.crypto.tink.signature.SignatureConfig
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopSigner
import com.rescuemesh.data.local.MessageDao
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsDao
import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.mesh.FakeMeshTransport
import com.rescuemesh.mesh.MeshPeer
import com.rescuemesh.mesh.SendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Tink registration, once per test JVM (same pattern as EnvelopeSignerTest). */
object TestTink {
    private val registered = AtomicBoolean(false)
    fun register() {
        if (registered.compareAndSet(false, true)) {
            runCatching { SignatureConfig.register() }
        }
    }
}

/** One throwaway phone identity for tests: a real Tink Ed25519 keyset + derived ids. */
class TestIdentity {
    val keysetHandle: KeysetHandle = KeysetHandle.newBuilder()
        .addEntry(
            KeysetHandle.generateEntryFromParameters(PredefinedSignatureParameters.ED25519WithRawOutput)
                .withRandomId()
                .makePrimary()
        )
        .build()
    val rawPublicKey: ByteArray = EnvelopeSigner.rawPublicKeyBytes(keysetHandle)
    val deviceId: String = DeviceIds.originDeviceId(rawPublicKey)
    val label: String = DeviceIds.advertisedLabel(deviceId)
    val envelopeSigner: EnvelopeSigner = EnvelopeSigner.fromKeysetHandle(keysetHandle)
    val hopSigner: HopSigner = HopSigner.fromKeysetHandle(keysetHandle)
}

/**
 * In-memory [MessageDao] with the same semantics as the Room queries
 * (IGNORE inserts, queue order priority ASC / createdAt ASC, state filters).
 * Records the order records were first inserted in [insertOrder].
 */
class InMemoryMessageDao : MessageDao {
    private val rows = LinkedHashMap<String, MessageEntity>()
    private val mutex = Mutex()
    val insertOrder = mutableListOf<String>()
    private val allFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

    private fun refresh() {
        allFlow.value = rows.values.sortedWith(compareBy({ it.priority }, { -it.createdAt }))
    }

    override suspend fun insertIfAbsent(message: MessageEntity): Long = mutex.withLock {
        if (rows.containsKey(message.messageId)) return@withLock -1L
        rows[message.messageId] = message
        insertOrder += message.messageId
        refresh()
        rows.size.toLong()
    }

    override fun observeAll(): Flow<List<MessageEntity>> = allFlow

    override fun observeByType(eventType: String): Flow<List<MessageEntity>> =
        MutableStateFlow(
            rows.values.filter { it.eventType == eventType }.sortedByDescending { it.createdAt }
        )

    override suspend fun findPendingForSync(nowMs: Long, limit: Int): List<MessageEntity> = mutex.withLock {
        pending(setOf("CREATED", "QUEUED", "PEER_ACKED", "FAILED_RETRYABLE"), nowMs, limit)
    }

    override suspend fun findSynced(limit: Int): List<MessageEntity> = mutex.withLock {
        rows.values
            .filter { it.localState == "SERVER_SYNCED" }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    override suspend fun updateHopTrace(messageId: String, hopTrace: String, hopCount: Int) {
        mutex.withLock {
            val existing = rows[messageId] ?: return@withLock
            rows[messageId] = existing.copy(hopTrace = hopTrace, hopCount = hopCount)
            refresh()
        }
    }

    override suspend fun getById(messageId: String): MessageEntity? = mutex.withLock {
        rows[messageId]
    }

    override suspend fun count(): Int = mutex.withLock { rows.size }

    private fun pending(states: Set<String>, nowMs: Long, limit: Int): List<MessageEntity> =
        rows.values
            .filter { it.localState in states && it.expiresAt > nowMs }
            .sortedWith(compareBy({ it.priority }, { it.createdAt }))
            .take(limit)

    override suspend fun findPendingForRelay(nowMs: Long, limit: Int): List<MessageEntity> = mutex.withLock {
        pending(setOf("CREATED", "QUEUED", "FAILED_RETRYABLE"), nowMs, limit)
    }

    override suspend fun findRelayCandidates(nowMs: Long, limit: Int): List<MessageEntity> = mutex.withLock {
        pending(setOf("CREATED", "QUEUED", "FAILED_RETRYABLE", "PEER_ACKED"), nowMs, limit)
    }

    override suspend fun findInventoryIds(limit: Int): List<String> = mutex.withLock {
        rows.values
            .sortedWith(compareBy({ it.priority }, { it.createdAt }))
            .take(limit)
            .map { it.messageId }
    }

    override suspend fun findInventory(limit: Int): List<MessageEntity> = mutex.withLock {
        rows.values
            .sortedWith(compareBy({ it.priority }, { it.createdAt }))
            .take(limit)
    }

    override suspend fun countOwnSosSince(originDeviceId: String, sinceMs: Long): Int = mutex.withLock {
        rows.values.count {
            it.isOwn && it.eventType == "SOS" && it.originDeviceId == originDeviceId && it.createdAt > sinceMs
        }
    }

    override suspend fun markExpired(nowMs: Long): Int = mutex.withLock {
        var changed = 0
        for ((id, entity) in rows) {
            if (entity.expiresAt <= nowMs && entity.localState in PURGEABLE_STATES) {
                rows[id] = entity.copy(localState = "EXPIRED")
                changed++
            }
        }
        if (changed > 0) refresh()
        changed
    }

    override suspend fun findPurgeable(): List<MessageEntity> = mutex.withLock {
        rows.values.filter { it.localState in PURGEABLE_STATES }
    }

    override suspend fun resetSendingToQueued(): Int = mutex.withLock {
        var changed = 0
        for ((id, entity) in rows) {
            if (entity.localState == "SENDING") {
                rows[id] = entity.copy(localState = "QUEUED")
                changed++
            }
        }
        if (changed > 0) refresh()
        changed
    }

    override suspend fun updateState(messageId: String, state: String) {
        mutex.withLock {
            val existing = rows[messageId] ?: return@withLock
            rows[messageId] = existing.copy(localState = state)
            refresh()
        }
    }

    override suspend fun purgeExpired(nowMs: Long): Int = mutex.withLock {
        val doomed = rows.values.filter { it.expiresAt <= nowMs }.map { it.messageId }
        doomed.forEach { rows.remove(it) }
        if (doomed.isNotEmpty()) refresh()
        doomed.size
    }

    companion object {
        private val PURGEABLE_STATES = setOf("CREATED", "QUEUED", "SENDING", "PEER_ACKED", "FAILED_RETRYABLE")
    }
}

/** In-memory [com.rescuemesh.data.local.ServerAckDao] (REPLACE upserts, newest first). */
class InMemoryServerAckDao : com.rescuemesh.data.local.ServerAckDao {
    private val rows = LinkedHashMap<String, com.rescuemesh.data.local.ServerAckEntity>()
    private val mutex = Mutex()

    override suspend fun upsert(ack: com.rescuemesh.data.local.ServerAckEntity) {
        mutex.withLock { rows[ack.messageId] = ack }
    }

    override suspend fun getAll(): List<com.rescuemesh.data.local.ServerAckEntity> = mutex.withLock {
        rows.values.sortedByDescending { it.issuedAt }.take(200)
    }

    override suspend fun getById(messageId: String): com.rescuemesh.data.local.ServerAckEntity? =
        mutex.withLock { rows[messageId] }
}

/**
 * In-memory [com.rescuemesh.data.local.PhotoDeliveryDao] with the Room
 * semantics that matter: (messageId, peerDeviceId) is the primary key
 * and inserts IGNORE conflicts, so the first receipt for a pair wins.
 */
class InMemoryPhotoDeliveryDao : com.rescuemesh.data.local.PhotoDeliveryDao {
    private val rows = LinkedHashMap<Pair<String, String>, com.rescuemesh.data.local.PhotoDeliveryEntity>()
    private val mutex = Mutex()
    private val allFlow =
        MutableStateFlow<List<com.rescuemesh.data.local.PhotoDeliveryEntity>>(emptyList())

    private fun refresh() {
        allFlow.value = rows.values.sortedByDescending { it.deliveredAt }
    }

    override suspend fun insertIfAbsent(
        delivery: com.rescuemesh.data.local.PhotoDeliveryEntity
    ): Long = mutex.withLock {
        val key = delivery.messageId to delivery.peerDeviceId
        if (rows.containsKey(key)) return@withLock -1L
        rows[key] = delivery
        refresh()
        rows.size.toLong()
    }

    override fun observeAll(): Flow<List<com.rescuemesh.data.local.PhotoDeliveryEntity>> = allFlow

    override suspend fun getForMessage(messageId: String):
        List<com.rescuemesh.data.local.PhotoDeliveryEntity> = mutex.withLock {
            rows.values.filter { it.messageId == messageId }.sortedBy { it.deliveredAt }
        }

    override suspend fun countForMessage(messageId: String): Int = mutex.withLock {
        rows.values.count { it.messageId == messageId }
    }
}

/** In-memory [com.rescuemesh.data.local.PhotoUploadDao] (REPLACE upserts). */
class InMemoryPhotoUploadDao : com.rescuemesh.data.local.PhotoUploadDao {
    private val rows = LinkedHashMap<String, com.rescuemesh.data.local.PhotoUploadEntity>()
    private val mutex = Mutex()

    override suspend fun upsert(upload: com.rescuemesh.data.local.PhotoUploadEntity) {
        mutex.withLock { rows[upload.messageId] = upload }
    }

    override suspend fun getById(messageId: String): com.rescuemesh.data.local.PhotoUploadEntity? =
        mutex.withLock { rows[messageId] }

    override suspend fun countForMessage(messageId: String): Int =
        mutex.withLock { if (rows.containsKey(messageId)) 1 else 0 }
}

/** In-memory [com.rescuemesh.data.local.SavedGuideDao] for Prepare Saved tests. */
class InMemorySavedGuideDao : com.rescuemesh.data.local.SavedGuideDao {
    private val rows = LinkedHashMap<String, com.rescuemesh.data.local.SavedGuideEntity>()
    private val mutex = Mutex()
    private val allFlow =
        MutableStateFlow<List<com.rescuemesh.data.local.SavedGuideEntity>>(emptyList())

    private fun refresh() {
        allFlow.value = rows.values.sortedByDescending { it.savedAt }
    }

    override suspend fun upsert(saved: com.rescuemesh.data.local.SavedGuideEntity) {
        mutex.withLock { rows[saved.guideId] = saved; refresh() }
    }

    override suspend fun deleteById(guideId: String) {
        mutex.withLock { rows.remove(guideId); refresh() }
    }

    override fun observeAll(): Flow<List<com.rescuemesh.data.local.SavedGuideEntity>> = allFlow

    override suspend fun countForGuide(guideId: String): Int =
        mutex.withLock { if (rows.containsKey(guideId)) 1 else 0 }
}

/** In-memory [com.rescuemesh.net.ServerKeyCache] for gateway-sync / ack-store tests. */
class InMemoryServerKeyCache : com.rescuemesh.net.ServerKeyCache {
    private var value: Pair<String, String>? = null

    override fun cached(): Pair<String, String>? = value

    override fun save(keyId: String, publicKeyBase64Url: String) {
        value = keyId to publicKeyBase64Url
    }
}

/** In-memory [MetricsDao]; every inserted row lands in [rows]. */
class InMemoryMetricsDao : MetricsDao {
    val rows = mutableListOf<MetricsEntity>()
    private var nextId = 1L
    private val mutex = Mutex()

    override suspend fun insert(metric: MetricsEntity): Long = mutex.withLock {
        val stored = metric.copy(id = nextId++)
        rows += stored
        stored.id
    }

    override fun observeRecent(limit: Int): Flow<List<MetricsEntity>> =
        MutableStateFlow(rows.sortedByDescending { it.startedAt }.take(limit))

    override suspend fun getAllForExport(): List<MetricsEntity> = mutex.withLock {
        rows.sortedBy { it.startedAt }
    }
}

/**
 * A FakeMeshTransport wired to one partner fake: [send] delivers to the
 * LINK's incoming flow (addressed from [myEndpointOnLink] — the endpoint id
 * the partner knows us by), and [connect]/[disconnect] flip the partner's
 * view of us, like a real Nearby connection does on both phones.
 */
open class PairedFakeTransport(fakePeers: List<MeshPeer>) : FakeMeshTransport(fakePeers) {
    var link: PairedFakeTransport? = null
    var myEndpointOnLink: String = ""
    val connectCalls = mutableListOf<String>()

    override suspend fun connect(endpointId: String) {
        connectCalls += endpointId
        super.connect(endpointId)
        link?.setPeerConnected(myEndpointOnLink, true)
    }

    override suspend fun disconnect(endpointId: String) {
        super.disconnect(endpointId)
        link?.setPeerConnected(myEndpointOnLink, false)
    }

    override suspend fun send(endpointId: String, bytes: ByteArray): SendResult {
        val peer = peers.value.firstOrNull { it.endpointId == endpointId }
        if (peer?.connected != true) {
            return SendResult(endpointId, 0, success = false, error = "PEER_NOT_CONNECTED")
        }
        val target = link ?: return SendResult(endpointId, 0, success = false, error = "NO_LINK")
        sentPayloads += endpointId to bytes
        target.deliverIncoming(myEndpointOnLink, bytes)
        return SendResult(endpointId, 1, success = true)
    }
}

/** Two paired transports: A knows B as "ep-b", B knows A as "ep-a". */
fun pairedTransports(labelA: String, labelB: String): Pair<PairedFakeTransport, PairedFakeTransport> {
    val a = PairedFakeTransport(listOf(MeshPeer(endpointId = "ep-b", deviceName = labelB, connected = false)))
    val b = PairedFakeTransport(listOf(MeshPeer(endpointId = "ep-a", deviceName = labelA, connected = false)))
    a.myEndpointOnLink = "ep-a"
    b.myEndpointOnLink = "ep-b"
    a.link = b
    b.link = a
    return a to b
}

/**
 * Polls [condition] until it holds or the deadline passes; returns the final value.
 *
 * The default timeout is deliberately generous: these tests drive real
 * coroutine engines on wall-clock timers, and on a loaded machine thread
 * starvation stretches a session that takes ~1 s unloaded to many seconds.
 * The timeout is only an upper bound — a passing run returns as soon as the
 * condition holds, so the margin costs nothing when things are healthy.
 *
 * A [java.util.ConcurrentModificationException] from the condition counts as
 * "not yet": conditions poll observation lists (e.g. FakeMeshTransport's
 * sentPayloads) that engine threads append to concurrently, and a torn read
 * mid-append just means this poll round saw nothing.
 */
suspend fun awaitCondition(timeoutMs: Long = 60_000, pollMs: Long = 25, condition: suspend () -> Boolean): Boolean {
    suspend fun pollOnce(): Boolean = try {
        condition()
    } catch (e: java.util.ConcurrentModificationException) {
        false // torn read of a concurrently-appended observation list: "not yet"
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (pollOnce()) return true
        kotlinx.coroutines.delay(pollMs)
    }
    return pollOnce()
}
