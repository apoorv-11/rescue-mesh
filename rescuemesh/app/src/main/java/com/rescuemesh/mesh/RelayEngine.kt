package com.rescuemesh.mesh

import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageDao
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsDao
import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.domain.LocalState
import com.rescuemesh.domain.PhotoRef
import com.rescuemesh.domain.PhotoStore
import com.rescuemesh.domain.SavedLocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Relay engine configuration (batch contracts §4 defaults). [nowMs] and
 * [backoffBaseMs] are injectable so JVM tests can drive timeouts, backoff
 * and TTL behaviour with short clocks instead of the production values;
 * production keeps the contract defaults (10 s ACK timeout, backoff
 * `min(2^attempts × 1 s, 30 s)`, 15 s purge, 30 s cooldown, 35 000-byte
 * packet guard, max 3 attempts per peer per record per session).
 */
data class RelayConfig(
    val purgeIntervalMs: Long = 15_000,
    val ackTimeoutMs: Long = 10_000,
    val ttlTestMode: Boolean = false,
    val maxAttemptsPerPeer: Int = 3,
    val cooldownMs: Long = 30_000,
    /**
     * FIX10 (19 Nov 2026): raised 30,000 → 35,000 so the worst-case
     * record fits at the new 50-hop limit (FIX9 measured it: a Missing
     * record with a full 12,000-char photoThumb is 32,798 bytes at 50
     * hops — 2,798 over the old guard — so the mesh dropped it on send
     * AND on receipt and it stopped relaying after hopCount 42). 35,000
     * leaves ~2,200 bytes of headroom for long free-text fields (name /
     * area / note have no length cap, unlike the 280-char SOS
     * description). This ONE value is the guard everywhere: the MSG
     * send check, the incoming-packet drop, the HELLO fallback and the
     * photo-chunk check all read it, so raising it here raises all of
     * them together. The photoThumb cap (12,000) needs no change — it
     * already fits under this guard.
     *
     * One honest caveat: this is the APP's guard. The bytes underneath
     * travel as one Nearby `Payload.fromBytes` (see NearbyTransport);
     * if Play Services itself refuses a ~33–35 KB byte payload, the
     * send fails at the transport instead (SendResult.success = false,
     * retried like any failed attempt) — the device gate for this fix
     * is two phones relaying a max-thumb Missing record, which proves
     * the transport accepts it.
     */
    val packetSizeLimit: Int = 35_000,
    val backoffBaseMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    /** Photo transfer (design doc): wait for a PHOTO_ACK after an END marker. */
    val photoAckTimeoutMs: Long = 20_000,
    /** Photo transfer: at most this many chunk rounds per photo per session. */
    val maxPhotoRounds: Int = 3,
    val nowMs: () -> Long = { System.currentTimeMillis() }
)

/**
 * The Prompt 5 relay engine: HELLO / MSG / ACK / DONE sessions over a
 * [MeshTransport], implementing batch contracts §4 exactly.
 *
 * Plain Kotlin with constructor injection — no Android types — so the whole
 * protocol is JVM-testable with paired FakeMeshTransports and in-memory
 * DAO fakes. [RelayService] is the thin Android wrapper that runs one of
 * these over the app's shared transport.
 *
 * Per-peer session: on connect both sides send HELLO immediately; a session
 * is established once the peer's HELLO arrives (its deviceId ↔ publicKey
 * binding is verified first — mismatch disconnects the peer). The peer's
 * gossiped server acks are applied before anything is sent. Records go one
 * MSG at a time in queue order (priority ASC, createdAt ASC), each waiting
 * for its ACK; then the photo phase offers the full photos (PHOTO_CHUNK /
 * PHOTO_ACK, photo-transfer design) for the records the peer now stores;
 * when a side's queue for the peer is empty it sends DONE, and when both
 * sides have sent DONE the engine disconnects and cools the peer down for
 * [RelayConfig.cooldownMs].
 */
class RelayEngine(
    private val transport: MeshTransport,
    private val messageDao: MessageDao,
    private val metricsDao: MetricsDao,
    private val hopSigner: HopSigner,
    private val ownDeviceId: String,
    private val ownPublicKey: ByteArray,
    private val serverAckStore: ServerAckStore = NoOpServerAckStore(),
    /**
     * Full-photo store (photo-transfer design). Null disables the photo
     * phase entirely — records relay exactly as before (older tests and
     * any embedding without a files dir); production always wires one.
     */
    private val photoStore: PhotoStore? = null,
    /**
     * Photo delivery receipts (photo-transfer design): a peer's
     * PHOTO_ACK COMPLETE is recorded here so the sender's record card
     * can show "Photo delivered ✓". Defaults to the no-op store —
     * engines built without it (older tests, embeddings) behave exactly
     * as before.
     */
    private val photoDeliveryStore: PhotoDeliveryStore = NoOpPhotoDeliveryStore(),
    private val gatewayProvider: () -> Boolean = { false },
    private val config: RelayConfig = RelayConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * FIX9 Kaam 4 (19 Nov 2026): the saved last-known location, read —
     * never awaited — when this phone appends its RELAY entry to a
     * record it stores. Null keeps the pre-FIX9 behaviour (entries
     * without loc). Production wires `locationStore::savedLocation`.
     */
    private val locationProvider: SavedLocationProvider? = null
) {
    private val ownLabel: String = DeviceIds.advertisedLabel(ownDeviceId)
    private val ownPublicKeyBase64Url: String = DeviceIds.base64UrlEncode(ownPublicKey)

    @Volatile
    private var scope: CoroutineScope? = null

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val connectInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Advertised labels whose HELLO failed the deviceId ↔ publicKey binding
     * (§4.2). Remembered for the engine's lifetime: an invalid peer is never
     * auto-reconnected and is disconnected again if it reconnects — without
     * this, the initiator rule would instantly re-dial a rejected peer.
     */
    private val invalidPeerLabels = ConcurrentHashMap.newKeySet<String>()

    /**
     * (messageId, peerDeviceId) pairs whose photo delivery this engine
     * has already recorded: a delivery is written ONCE per pair however
     * many sessions re-confirm it (the durable store dedupes across
     * restarts the same way). In-memory only, so it grows by one small
     * pair per delivered photo for the engine's lifetime.
     */
    private val photoDeliveriesRecorded = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    val isRunning: Boolean get() = scope != null

    private inner class PeerSession(val endpointId: String) {
        @Volatile var peerDeviceId: String? = null
        @Volatile var helloReceived: Boolean = false
        @Volatile var invalid: Boolean = false
        @Volatile var weSentDone: Boolean = false
        @Volatile var peerSentDone: Boolean = false
        @Volatile var finished: Boolean = false
        val inventory: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** MessageIds whose COMPLETE photo the peer advertised in its HELLO. */
        val photoInventory: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val pendingPhotoAcks = ConcurrentHashMap<String, CompletableDeferred<RelayPacket.PhotoAck>>()
        val ackedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val exhaustedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val oversizedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val attempts = ConcurrentHashMap<String, Int>()
        val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<String>>()
        @Volatile var sendJob: Job? = null
    }

    /**
     * Starts the engine (idempotent): brings the transport up if it is not
     * running, then collects peers + incoming packets and runs the TTL
     * purge loop. Records a dead engine left in SENDING go back to QUEUED.
     */
    fun start() {
        synchronized(this) {
            if (scope != null) return
            val engineScope = CoroutineScope(SupervisorJob() + dispatcher)
            scope = engineScope
            // Sequential init: the transport is fully up BEFORE the peers /
            // incoming collectors subscribe, so no peer transition or packet
            // can race ahead of the transport's own start.
            engineScope.launch {
                runCatching { messageDao.resetSendingToQueued() }
                if (!transport.isRunning.value) {
                    runCatching { transport.start(ownLabel) }
                }
                engineScope.launch { collectPeers() }
                engineScope.launch { collectIncoming() }
                engineScope.launch { purgeLoop() }
            }
        }
    }

    /** Stops the engine: sessions are dropped, the transport is left as-is. */
    fun stop() {
        synchronized(this) {
            scope?.cancel()
            scope = null
            sessions.clear()
            connectInFlight.clear()
        }
    }

    private fun now(): Long = config.nowMs()

    // ---------------------------------------------------------------- peers

    private suspend fun collectPeers() {
        var previousConnected = emptySet<String>()
        transport.peers.collect { peers ->
            val connectedNow = peers.filter { it.connected }.map { it.endpointId }.toSet()
            for (endpointId in connectedNow - previousConnected) onPeerConnected(endpointId)
            for (endpointId in previousConnected - connectedNow) onPeerDisconnected(endpointId)
            previousConnected = connectedNow
            for (peer in peers) {
                if (!peer.connected) maybeAutoConnect(peer)
            }
        }
    }

    /**
     * Initiator rule (§4.1): the lexicographically smaller advertised label
     * connects; the other side only accepts. Cooldown (§4.7) suppresses
     * re-connects — it is keyed by peer deviceId, and the label checked here
     * is derived from that same deviceId, so the label IS the pre-HELLO key.
     */
    private fun maybeAutoConnect(peer: MeshPeer) {
        if (ownLabel >= peer.deviceName) return
        if (peer.deviceName in invalidPeerLabels) return
        if ((cooldownUntilMs[peer.deviceName] ?: 0L) > now()) return
        if (!connectInFlight.add(peer.endpointId)) return
        val engineScope = scope ?: return
        engineScope.launch {
            try {
                transport.connect(peer.endpointId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectInFlight.remove(peer.endpointId)
            }
        }
    }

    private fun onPeerConnected(endpointId: String) {
        val session = sessions.getOrPut(endpointId) { PeerSession(endpointId) }
        // A tombstoned peer that reconnects anyway is dropped at once.
        val label = transport.peers.value.firstOrNull { it.endpointId == endpointId }?.deviceName
        if (label != null && label in invalidPeerLabels) {
            session.invalid = true
            val engineScope = scope ?: return
            engineScope.launch { runCatching { transport.disconnect(endpointId) } }
            return
        }
        val engineScope = scope ?: return
        // §4.2: BOTH sides send HELLO immediately on connect — whether we
        // initiated the connection or the peer connected to us.
        engineScope.launch { runCatching { sendHello(session) } }
    }

    private fun onPeerDisconnected(endpointId: String) {
        connectInFlight.remove(endpointId)
        sessions.remove(endpointId)?.let { session ->
            // §4.7 cooldown, stamped HERE as well as in maybeFinishSession.
            // The two collectors race at session end: maybeFinishSession
            // only stamps once THIS engine has processed the peer's DONE,
            // but the peers collector can observe the disconnect first —
            // and maybeAutoConnect runs in the same emission, so without a
            // stamp here it would re-dial the peer immediately, violating
            // the 30 s post-session wait. Only an established session
            // (HELLO received) earns the stamp: a peer we never exchanged
            // HELLO with was never in a session and may be re-dialled.
            if (session.helloReceived) {
                session.peerDeviceId?.let { deviceId ->
                    val until = now() + config.cooldownMs
                    cooldownUntilMs[deviceId] = until
                    cooldownUntilMs[DeviceIds.advertisedLabel(deviceId)] = until
                }
            }
            session.sendJob?.cancel()
            session.pendingAcks.values.forEach { it.cancel() }
            session.pendingAcks.clear()
            session.pendingPhotoAcks.values.forEach { it.cancel() }
            session.pendingPhotoAcks.clear()
        }
    }

    private suspend fun sendHello(session: PeerSession) {
        // Inventory advertises only relay-eligible records: a legacy record
        // no peer could ever accept (RelayRecordValidation) is never
        // advertised, so it is never asked for or offered either.
        val inventory = runCatching {
            messageDao.findInventory(PacketCodec.MAX_INVENTORY)
                .filter { RelayRecordValidation.isRelayEligible(it) }
                .map { it.messageId }
        }.getOrDefault(emptyList())
        val acks = runCatching { serverAckStore.allAcks() }.getOrDefault(emptyList())
        val photos = runCatching { photoInventoryForHello() }.getOrDefault(emptyList())
        var bytes = PacketCodec.encode(
            RelayPacket.Hello(
                ownDeviceId, ownPublicKeyBase64Url, gatewayProvider(), inventory, acks,
                photos = photos
            )
        )
        if (bytes.size > config.packetSizeLimit) {
            // Oversize guard fallback: a smaller HELLO beats none at all.
            bytes = PacketCodec.encode(
                RelayPacket.Hello(
                    ownDeviceId, ownPublicKeyBase64Url, gatewayProvider(),
                    inventory.take(50), emptyList(), photos = photos.take(50)
                )
            )
        }
        if (bytes.size <= config.packetSizeLimit) {
            transport.send(session.endpointId, bytes)
        }
    }

    /**
     * HELLO photo inventory: messageIds of stored records whose payload
     * carries a photoRef AND whose complete, verified photo this phone
     * actually holds in [photoStore]. Advertising anything less (a ref
     * without the bytes, unverified parts) would break the
     * store-and-forward guarantee — holders only ever advertise what they
     * can send in full.
     */
    private suspend fun photoInventoryForHello(): List<String> {
        val store = photoStore ?: return emptyList()
        return messageDao.findInventory(PacketCodec.MAX_INVENTORY)
            .filter { PhotoRef.parse(it.payload) != null }
            .map { it.messageId }
            .filter { store.hasComplete(it) }
            .take(PacketCodec.MAX_PHOTOS)
    }

    // -------------------------------------------------------------- incoming

    private suspend fun collectIncoming() {
        transport.incoming.collect { payload ->
            // §4 size guard: an oversize packet is ignored — no ACK, nothing.
            if (payload.bytes.size > config.packetSizeLimit) return@collect
            val packet = PacketCodec.decode(payload.bytes) ?: return@collect
            val session = sessions.getOrPut(payload.endpointId) { PeerSession(payload.endpointId) }
            if (session.invalid) return@collect
            try {
                when (packet) {
                    is RelayPacket.Hello -> handleHello(session, packet)
                    is RelayPacket.Msg -> handleMsg(session, packet.envelope)
                    is RelayPacket.Ack -> session.pendingAcks[packet.messageId]?.complete(packet.status)
                    is RelayPacket.PhotoChunk -> handlePhotoChunk(session, packet)
                    is RelayPacket.PhotoAck -> session.pendingPhotoAcks[packet.messageId]?.complete(packet)
                    RelayPacket.Done -> {
                        session.peerSentDone = true
                        maybeFinishSession(session)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One bad packet must never kill the packet collector.
            }
        }
    }

    private suspend fun handleHello(session: PeerSession, hello: RelayPacket.Hello) {
        val peerPublicKey = try {
            DeviceIds.base64UrlDecode(hello.publicKey)
        } catch (e: IllegalArgumentException) {
            null
        }
        // §4.2: the deviceId ↔ publicKey binding MUST hold, else the peer's
        // packets are ignored and the peer is disconnected.
        if (peerPublicKey == null || !DeviceIds.isValidOriginDeviceId(hello.deviceId, peerPublicKey)) {
            session.invalid = true
            invalidPeerLabels += DeviceIds.advertisedLabel(hello.deviceId)
            runCatching { transport.disconnect(session.endpointId) }
            return
        }
        session.peerDeviceId = hello.deviceId
        session.helloReceived = true
        session.inventory.addAll(hello.inventory)
        session.photoInventory.addAll(hello.photos)
        // §4.3: apply the peer's serverAcked BEFORE sending anything.
        for (ack in hello.serverAcked) {
            runCatching { serverAckStore.recordAck(ack) }
            val verified = runCatching { serverAckStore.isAckVerified(ack) }.getOrDefault(false)
            if (verified) {
                val held = messageDao.getById(ack.messageId)
                if (held != null && held.localState != LocalState.SERVER_SYNCED.name) {
                    messageDao.updateState(ack.messageId, LocalState.SERVER_SYNCED.name)
                }
            }
        }
        if (session.sendJob?.isActive != true) {
            val engineScope = scope ?: return
            session.sendJob = engineScope.launch { runCatching { sendLoop(session) } }
        }
    }

    // ------------------------------------------------------------------ send

    private suspend fun sendLoop(session: PeerSession) {
        while (isPeerConnected(session.endpointId)) {
            val candidate = nextCandidate(session) ?: break
            // A false return means a failed attempt (backoff already applied
            // inside sendOne); the loop picks the next candidate — or the
            // same one again, attempts permitting.
            sendOne(session, candidate)
        }
        // Photo phase (photo-transfer design): AFTER the record queue, so
        // a record's PEER_ACKED never waits for its photo. Photos follow
        // for the records this peer stores but whose photo it lacks.
        // A photo failure must never eat the DONE below — hence runCatching.
        if (isPeerConnected(session.endpointId)) {
            runCatching { photoPhase(session) }
        }
        // §4.7: our queue for this peer is empty → DONE. The flag is set
        // only AFTER the send has been handed to the transport: the
        // incoming collector can process the peer's DONE concurrently and
        // call maybeFinishSession, and if weSentDone were already true at
        // that moment it would disconnect the endpoint before this DONE
        // ever went out — leaving the peer waiting for a DONE that can
        // no longer arrive.
        if (isPeerConnected(session.endpointId) && !session.weSentDone) {
            runCatching { transport.send(session.endpointId, PacketCodec.encode(RelayPacket.Done)) }
            session.weSentDone = true
            maybeFinishSession(session)
        }
    }

    /** Next record to offer this peer, in queue order, honouring every §4.4 skip rule. */
    private suspend fun nextCandidate(session: PeerSession): MessageEntity? {
        val nowMs = now()
        val candidates = messageDao.findRelayCandidates(nowMs, CANDIDATE_LIMIT)
        for (summary in candidates) {
            val fresh = messageDao.getById(summary.messageId) ?: continue
            if (!isSendableTo(fresh, session, nowMs)) continue
            // The engine adopts CREATED records into the relay queue.
            if (fresh.localState == LocalState.CREATED.name) {
                messageDao.updateState(fresh.messageId, LocalState.QUEUED.name)
            }
            return fresh
        }
        return null
    }

    private fun isSendableTo(entity: MessageEntity, session: PeerSession, nowMs: Long): Boolean {
        if (isExpiredForRelay(entity, nowMs)) return false
        if (entity.hopCount >= entity.hopLimit) return false
        if (entity.localState in NEVER_SEND_STATES) return false
        // Legacy / invalid-origin records can never be accepted by any
        // peer (missing entry 0, empty or unverifiable origin identity) —
        // they stay on this phone but are never relayed.
        if (!RelayRecordValidation.isRelayEligible(entity)) return false
        if (entity.messageId in session.inventory) return false
        if (entity.messageId in session.ackedThisSession) return false
        if (entity.messageId in session.exhaustedThisSession) return false
        if (entity.messageId in session.oversizedThisSession) return false
        if ((session.attempts[entity.messageId] ?: 0) >= config.maxAttemptsPerPeer) return false
        return true
    }

    /**
     * Sends one MSG and waits for its ACK. Returns true when the record is
     * settled for this peer (ACKed, exhausted, or unsendable); false after a
     * failed attempt that may be retried (backoff already applied).
     */
    private suspend fun sendOne(session: PeerSession, entity: MessageEntity): Boolean {
        val bytes = PacketCodec.encode(RelayPacket.Msg(entity))
        if (bytes.size > config.packetSizeLimit) {
            // A packet larger than the guard is never sent (§4) — and this
            // record will never fit, so don't burn attempts on it.
            session.oversizedThisSession += entity.messageId
            return true
        }
        messageDao.updateState(entity.messageId, LocalState.SENDING.name)
        val deferred = CompletableDeferred<String>()
        session.pendingAcks[entity.messageId] = deferred
        val startedAt = now()
        // The deferred stays registered until the ACK arrives or times out.
        val result = transport.send(session.endpointId, bytes)
        if (!result.success) {
            session.pendingAcks.remove(entity.messageId)
            return registerFailedAttempt(session, entity)
        }
        val ackStatus = withTimeoutOrNull(config.ackTimeoutMs) {
            try {
                deferred.await()
            } catch (e: CancellationException) {
                null
            }
        }
        session.pendingAcks.remove(entity.messageId)
        return when (ackStatus) {
            PacketCodec.ACK_STORED, PacketCodec.ACK_DUPLICATE -> {
                metricsDao.insert(
                    MetricsEntity(
                        event = EVENT_RELAY,
                        messageId = entity.messageId,
                        peerId = session.peerDeviceId ?: session.endpointId,
                        startedAt = startedAt,
                        durationMs = now() - startedAt,
                        hopCount = entity.hopCount,
                        success = true,
                        detail = ackStatus
                    )
                )
                session.ackedThisSession += entity.messageId
                val fresh = messageDao.getById(entity.messageId)
                if (fresh != null && fresh.localState != LocalState.SERVER_SYNCED.name) {
                    messageDao.updateState(entity.messageId, LocalState.PEER_ACKED.name)
                }
                true
            }
            PacketCodec.ACK_REJECTED -> handleRejected(session, entity, startedAt)
            else -> registerFailedAttempt(session, entity) // timeout or transport loss
        }
    }

    /**
     * ACK REJECTED: the peer refused this record. If the refusal is
     * DETERMINISTIC — this phone reaches the same verdict locally via
     * [RelayRecordValidation.deterministicInvalidReason] (missing/invalid
     * entry 0, key mismatch, bad origin signature) — the record can never
     * be accepted by any peer, so it is marked REJECTED (terminal): it
     * leaves the relay queue permanently instead of looping through
     * backoff and re-offers every session. A rejection this phone cannot
     * reproduce locally is receiver-specific (expired on the peer's
     * clock, the peer's hop budget) and keeps the ordinary §4.6 failed
     * attempt / backoff path.
     */
    private suspend fun handleRejected(session: PeerSession, entity: MessageEntity, startedAt: Long): Boolean {
        val reason = RelayRecordValidation.deterministicInvalidReason(entity)
            ?: return registerFailedAttempt(session, entity)
        metricsDao.insert(
            MetricsEntity(
                event = EVENT_RELAY,
                messageId = entity.messageId,
                peerId = session.peerDeviceId ?: session.endpointId,
                startedAt = startedAt,
                durationMs = now() - startedAt,
                hopCount = entity.hopCount,
                success = false,
                detail = "rejected:$reason"
            )
        )
        val fresh = messageDao.getById(entity.messageId)
        if (fresh != null && fresh.localState !in NEVER_SEND_STATES) {
            messageDao.updateState(entity.messageId, LocalState.REJECTED.name)
        }
        return true
    }

    /**
     * §4.6 failed attempt: the record returns to QUEUED, attempts for this
     * (peer, record) grow, and at the per-session cap the record is marked
     * FAILED_RETRYABLE (eligible again in a later session). Between attempts
     * the loop backs off `min(2^attempts × backoffBase, maxBackoff)`.
     */
    private suspend fun registerFailedAttempt(session: PeerSession, entity: MessageEntity): Boolean {
        val attempts = (session.attempts[entity.messageId] ?: 0) + 1
        session.attempts[entity.messageId] = attempts
        if (attempts >= config.maxAttemptsPerPeer) {
            session.exhaustedThisSession += entity.messageId
            val fresh = messageDao.getById(entity.messageId)
            if (fresh != null && fresh.localState !in NEVER_SEND_STATES) {
                messageDao.updateState(entity.messageId, LocalState.FAILED_RETRYABLE.name)
            }
            return true
        }
        val fresh = messageDao.getById(entity.messageId)
        if (fresh != null && fresh.localState == LocalState.SENDING.name) {
            messageDao.updateState(entity.messageId, LocalState.QUEUED.name)
        }
        val backoffMs = minOf(config.backoffBaseMs shl attempts, config.maxBackoffMs)
        if (backoffMs > 0) delay(backoffMs)
        return false
    }

    // ------------------------------------------------------------ photos

    /**
     * Photo phase (photo-transfer design §2): for every record this peer
     * stores — its HELLO inventory, plus everything it ACKed during this
     * session's record phase — offer the full photo when (a) the record's
     * signed payload carries a photoRef, (b) the peer's HELLO photo
     * inventory says it lacks the photo, and (c) WE hold the complete,
     * verified bytes. Anything else is silently skipped: photo problems
     * never affect records.
     */
    private suspend fun photoPhase(session: PeerSession) {
        val store = photoStore ?: return
        val peerRecords = session.inventory + session.ackedThisSession
        for (messageId in peerRecords) {
            if (!isPeerConnected(session.endpointId)) return
            if (messageId in session.photoInventory) continue
            val record = messageDao.getById(messageId) ?: continue
            if (isExpiredForRelay(record, now())) continue
            val ref = PhotoRef.parse(record.payload) ?: continue
            if (ref.photoId != messageId) continue
            if (!store.hasComplete(messageId)) continue
            runCatching { sendPhoto(session, messageId, ref) }
        }
    }

    /**
     * Sends one photo in rounds: round 1 sends every chunk, later rounds
     * resend only the indices the peer's PHOTO_ACK MISSING named, at most
     * [RelayConfig.maxPhotoRounds] rounds. Each round closes with an END
     * marker and waits for the PHOTO_ACK. A timeout, a failed send, or a
     * COMPLETE ends the transfer for this session — the parts the peer
     * did collect stay on its disk and a later session resumes.
     */
    private suspend fun sendPhoto(session: PeerSession, messageId: String, ref: PhotoRef) {
        val store = photoStore ?: return
        val bytes = store.readComplete(messageId) ?: return
        // The bytes on disk must be the bytes the signed ref describes;
        // anything else is never put on the wire.
        if (bytes.size != ref.sizeBytes) return
        val total = PacketCodec.photoChunkCount(bytes.size)
        if (total <= 0) return
        var toSend: List<Int> = (0 until total).toList()
        var round = 0
        while (round < config.maxPhotoRounds) {
            round++
            val deferred = CompletableDeferred<RelayPacket.PhotoAck>()
            session.pendingPhotoAcks[messageId] = deferred
            var sentOk = true
            for (index in toSend) {
                val from = index * PacketCodec.PHOTO_CHUNK_BYTES
                val slice = bytes.copyOfRange(
                    from, minOf(bytes.size, from + PacketCodec.PHOTO_CHUNK_BYTES)
                )
                val packetBytes = PacketCodec.encode(
                    RelayPacket.PhotoChunk(
                        messageId, index, total, DeviceIds.base64UrlEncode(slice)
                    )
                )
                if (packetBytes.size > config.packetSizeLimit ||
                    !transport.send(session.endpointId, packetBytes).success
                ) {
                    sentOk = false
                    break
                }
            }
            if (sentOk) {
                val endBytes = PacketCodec.encode(
                    RelayPacket.PhotoChunk(messageId, PacketCodec.PHOTO_END_INDEX, total, null)
                )
                if (!transport.send(session.endpointId, endBytes).success) sentOk = false
            }
            if (!sentOk) {
                session.pendingPhotoAcks.remove(messageId)
                return
            }
            val ack = withTimeoutOrNull(config.photoAckTimeoutMs) {
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    null
                }
            }
            session.pendingPhotoAcks.remove(messageId)
            when {
                ack == null -> return // timeout or disconnect: next session resumes
                ack.status == PacketCodec.PHOTO_COMPLETE -> {
                    notePhotoDelivered(session, messageId)
                    return
                }
                ack.status == PacketCodec.PHOTO_MISSING -> {
                    val missing = ack.missing
                        .filter { it in 0 until total }
                        .distinct()
                    if (missing.isEmpty()) return
                    toSend = missing
                }
                else -> return
            }
        }
    }

    /**
     * Records a confirmed photo delivery (the peer's PHOTO_ACK COMPLETE)
     * in [photoDeliveryStore], once per (messageId, peer) pair for this
     * engine's lifetime — the store dedupes durably across restarts.
     * Best-effort by design: a store failure must never disturb the
     * photo transfer itself, so it is swallowed here (the card simply
     * keeps not showing the receipt until a later session re-confirms).
     */
    private suspend fun notePhotoDelivered(session: PeerSession, messageId: String) {
        val peerDeviceId = session.peerDeviceId ?: return
        if (!photoDeliveriesRecorded.add(messageId to peerDeviceId)) return
        try {
            photoDeliveryStore.recordPhotoDelivery(messageId, peerDeviceId, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Receipts are advisory UI state; never fail a transfer over one.
        }
    }

    /**
     * Receiver side of the photo transfer. Chunks are accepted ONLY for a
     * record this phone stores whose signed payload carries the matching
     * photoRef — an unauthenticated photo is never written. Data chunks
     * become part files; the END marker triggers assembly against the
     * signed ref and the PHOTO_ACK reply: COMPLETE (we are now a
     * store-and-forward holder), MISSING with the gaps still needed, or
     * MISSING with the full range when assembly failed verification
     * (PhotoStore has discarded the corrupt parts — resend from scratch).
     */
    private suspend fun handlePhotoChunk(session: PeerSession, packet: RelayPacket.PhotoChunk) {
        val store = photoStore ?: return
        val record = messageDao.getById(packet.messageId) ?: return
        val ref = PhotoRef.parse(record.payload) ?: return
        if (ref.photoId != packet.messageId) return
        // FIX8 (19 Nov 2026): a photo already complete + verified on this
        // phone takes no more packets. A stale retransmit after completion
        // used to write orphan parts back into the parts dir (and a late
        // END re-ran assembly with the parts dir already gone, so the peer
        // answered MISSING for the full range and the sender resent every
        // chunk — the 500 KB relay test's exact chunk count failed on it).
        // Dropping the packet is correct: the complete file already stands
        // and is what store-and-forward advertises.
        if (store.hasComplete(packet.messageId)) return
        if (isExpiredForRelay(record, now())) return
        val expectedTotal = PacketCodec.photoChunkCount(ref.sizeBytes)
        if (packet.isEndMarker) {
            val missing = store.missingIndices(packet.messageId, expectedTotal)
            when {
                missing.isNotEmpty() -> replyPhotoAck(
                    session, packet.messageId, PacketCodec.PHOTO_MISSING, missing
                )
                store.assembleIfComplete(
                    packet.messageId, expectedTotal, ref.sizeBytes, ref.sha256
                ) -> replyPhotoAck(
                    session, packet.messageId, PacketCodec.PHOTO_COMPLETE, emptyList()
                )
                else -> replyPhotoAck(
                    session, packet.messageId, PacketCodec.PHOTO_MISSING,
                    (0 until expectedTotal).toList()
                )
            }
            return
        }
        if (packet.total != expectedTotal) return
        if (packet.index !in 0 until expectedTotal) return
        val data = packet.data ?: return
        val slice = try {
            DeviceIds.base64UrlDecode(data)
        } catch (e: IllegalArgumentException) {
            return
        }
        if (slice.isEmpty() || slice.size > PacketCodec.PHOTO_CHUNK_BYTES) return
        store.writePart(packet.messageId, packet.index, slice)
    }

    private suspend fun replyPhotoAck(
        session: PeerSession,
        messageId: String,
        status: String,
        missing: List<Int>
    ) {
        runCatching {
            transport.send(
                session.endpointId,
                PacketCodec.encode(RelayPacket.PhotoAck(messageId, status, missing))
            )
        }
    }

    // --------------------------------------------------------------- receive

    /**
     * §4.5 receive path, checks in contract order. Every rejection before
     * insert answers ACK REJECTED and counts the failure in Metrics.
     */
    private suspend fun handleMsg(session: PeerSession, envelope: MessageEntity) {
        val nowMs = now()
        suspend fun reject(reason: String) {
            metricsDao.insert(
                MetricsEntity(
                    event = EVENT_RELAY,
                    messageId = envelope.messageId,
                    peerId = session.peerDeviceId ?: session.endpointId,
                    startedAt = nowMs,
                    durationMs = 0,
                    hopCount = envelope.hopCount,
                    success = false,
                    detail = reason
                )
            )
            runCatching {
                transport.send(
                    session.endpointId,
                    PacketCodec.encode(RelayPacket.Ack(envelope.messageId, PacketCodec.ACK_REJECTED))
                )
            }
        }

        val trace = HopTrace.parseTrace(envelope.hopTrace) ?: return reject("malformed-trace")
        val entry0 = trace.firstOrNull() ?: return reject("missing-entry0")
        // Entry 0 must be the ORIGIN entry of the envelope's origin, and its
        // pk must hash to that origin id — both fatal (§4.5). Entry 0's own
        // signature is only flagged (HopTrace.validateTrace), never fatal.
        if (entry0.role != HopTrace.ROLE_ORIGIN || entry0.deviceId != envelope.originDeviceId) {
            return reject("bad-entry0")
        }
        val originPublicKey = try {
            DeviceIds.base64UrlDecode(entry0.pk)
        } catch (e: IllegalArgumentException) {
            return reject("bad-entry0")
        }
        if (!DeviceIds.isValidOriginDeviceId(envelope.originDeviceId, originPublicKey)) {
            return reject("key-mismatch")
        }
        if (!EnvelopeSigner.verifyEnvelope(envelope, originPublicKey)) {
            return reject("bad-signature")
        }
        if (isExpiredForRelay(envelope, nowMs)) return reject("expired")
        // Hop drop rule (§2): appending our entry would exceed the hop limit.
        if (trace.size > envelope.hopLimit) return reject("hop-limit")
        if (messageDao.getById(envelope.messageId) != null) {
            runCatching {
                transport.send(
                    session.endpointId,
                    PacketCodec.encode(RelayPacket.Ack(envelope.messageId, PacketCodec.ACK_DUPLICATE))
                )
            }
            return
        }
        // Append OUR relay entry after validation, before storing (§2), and
        // persist-then-ACK: the ACK only goes out once Room has the record.
        // FIX9 Kaam 4: the entry carries this phone's saved last-known
        // location (loc is optional — without a saved fix the entry is
        // exactly as before, and old entries stay valid).
        val ownEntry = hopSigner.appendEntry(
            messageId = envelope.messageId,
            trace = trace,
            role = HopTrace.ROLE_RELAY,
            at = nowMs,
            loc = runCatching { locationProvider?.invoke()?.toHopLoc() }.getOrNull()
        )
        val newTrace = trace + ownEntry
        val stored = envelope.copy(
            hopTrace = HopTrace.traceToJson(newTrace),
            hopCount = newTrace.size - 1,
            localState = LocalState.QUEUED.name,
            isOwn = envelope.originDeviceId == ownDeviceId
        )
        val rowId = messageDao.insertIfAbsent(stored)
        val status = if (rowId == -1L) PacketCodec.ACK_DUPLICATE else PacketCodec.ACK_STORED
        runCatching {
            transport.send(session.endpointId, PacketCodec.encode(RelayPacket.Ack(envelope.messageId, status)))
        }
    }

    // ----------------------------------------------------------------- misc

    /** §4.7: both sides DONE → disconnect + cooldown keyed by peer deviceId. */
    private fun maybeFinishSession(session: PeerSession) {
        if (!session.weSentDone || !session.peerSentDone || session.finished) return
        session.finished = true
        val until = now() + config.cooldownMs
        session.peerDeviceId?.let { deviceId ->
            cooldownUntilMs[deviceId] = until
            cooldownUntilMs[DeviceIds.advertisedLabel(deviceId)] = until
        }
        val engineScope = scope ?: return
        engineScope.launch { runCatching { transport.disconnect(session.endpointId) } }
    }

    private fun isPeerConnected(endpointId: String): Boolean =
        transport.peers.value.any { it.endpointId == endpointId && it.connected }

    /** Expiry on THIS phone's clock; TTL test mode expires 60 s after creation (§4.8). */
    private fun isExpiredForRelay(entity: MessageEntity, nowMs: Long): Boolean =
        if (config.ttlTestMode) {
            entity.createdAt + TTL_TEST_MODE_LIFETIME_MS <= nowMs
        } else {
            entity.expiresAt <= nowMs
        }

    private suspend fun purgeLoop() {
        while (true) {
            delay(config.purgeIntervalMs)
            runCatching { runPurgeOnce() }
        }
    }

    /** One TTL purge tick (§4.8) — also called directly by tests. */
    suspend fun runPurgeOnce() {
        val nowMs = now()
        if (config.ttlTestMode) {
            for (entity in messageDao.findPurgeable()) {
                if (entity.createdAt + TTL_TEST_MODE_LIFETIME_MS <= nowMs) {
                    messageDao.updateState(entity.messageId, LocalState.EXPIRED.name)
                    // Photo-transfer design: an expired record's photo
                    // (complete file + in-flight parts) dies with it.
                    photoStore?.deleteFor(entity.messageId)
                }
            }
        } else {
            val expiring = messageDao.findPurgeable().filter { it.expiresAt <= nowMs }
            messageDao.markExpired(nowMs)
            if (photoStore != null) {
                for (entity in expiring) photoStore.deleteFor(entity.messageId)
            }
        }
    }

    companion object {
        const val EVENT_RELAY = "RELAY"
        const val TTL_TEST_MODE_LIFETIME_MS = 60_000L
        private const val CANDIDATE_LIMIT = 200
        private val NEVER_SEND_STATES = setOf(
            LocalState.SERVER_SYNCED.name,
            LocalState.EXPIRED.name,
            LocalState.REJECTED.name
        )
    }
}
