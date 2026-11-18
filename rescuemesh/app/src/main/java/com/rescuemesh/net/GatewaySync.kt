package com.rescuemesh.net

import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.HopSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageDao
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsDao
import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.domain.LocalState
import com.rescuemesh.mesh.ServerAck
import com.rescuemesh.mesh.ServerAckStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

/**
 * The narrow sync-API seam [GatewaySync] runs against. Production adapts
 * [ApiClient] (see [ApiClientSyncApi]); JVM tests hand-write a fake. The
 * health probe is deliberately NOT here — it belongs to [InternetChecker].
 */
interface SyncApi {
    suspend fun syncPubkey(): ApiResult<SyncPubkeyResponse>
    suspend fun syncBatch(authorization: String, request: SyncBatchRequest): ApiResult<SyncBatchResponse>
    suspend fun deviceToken(deviceId: String): ApiResult<TokenResponse>
}

/** Production [SyncApi]: a thin delegation to the shared [ApiClient]. */
class ApiClientSyncApi(private val client: ApiClient) : SyncApi {
    override suspend fun syncPubkey(): ApiResult<SyncPubkeyResponse> = client.syncPubkey()
    override suspend fun syncBatch(
        authorization: String,
        request: SyncBatchRequest
    ): ApiResult<SyncBatchResponse> = client.syncBatch(authorization, request)

    override suspend fun deviceToken(deviceId: String): ApiResult<TokenResponse> =
        client.deviceToken(deviceId)
}

/** Builds the wire envelope for one stored record: hopTrace/payload as parsed JSON values. */
fun MessageEntity.toSyncEnvelopeDto(json: Json): SyncEnvelopeDto = SyncEnvelopeDto(
    messageId = messageId,
    eventType = eventType,
    originDeviceId = originDeviceId,
    createdAt = createdAt,
    expiresAt = expiresAt,
    priority = priority,
    hopLimit = hopLimit,
    hopCount = hopCount,
    hopTrace = json.parseToJsonElement(hopTrace),
    payload = json.parseToJsonElement(payload),
    signature = signature
)

/**
 * Gateway sync (Prompt 9, contracts §6): uploads this phone's stored
 * records to the server in batches of 50 and applies the per-message
 * results — never blanket marking.
 *
 * Run outline (see [syncNow]): no URL → stop; health check fails → stop;
 * refresh the cached server pubkey; token = user token, falling back to a
 * per-run device token on absence/401 ("login needed" when both fail);
 * then batch until the §6 selection is exhausted. A record this phone did
 * not originate gains a GATEWAY hop entry (persisted AND uploaded) before
 * upload; ACCEPTED / ALREADY_EXISTS mark SERVER_SYNCED only when the
 * returned serverAck verifies under the cached server key — otherwise the
 * record is left untouched and counted "ack unverified". REJECTED is
 * terminal. An HTTP-level failure aborts the run with no state changes.
 *
 * Android-free apart from injected providers, so the whole flow is
 * JVM-testable with a fake [SyncApi] and in-memory DAOs.
 */
class GatewaySync(
    private val api: SyncApi,
    private val baseUrlProvider: () -> String?,
    private val tokenProvider: () -> String?,
    private val messageDao: MessageDao,
    private val metricsDao: MetricsDao,
    private val ackStore: ServerAckStore,
    private val keyCache: ServerKeyCache,
    private val hopSigner: HopSigner,
    private val ownDeviceId: String,
    private val ownPublicKey: ByteArray,
    private val internetChecker: InternetChecker,
    /** "WIFI" / "CELLULAR" of the active network, or null — GATEWAY entries only. */
    private val networkTypeProvider: () -> String? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ownPublicKeyB64: String = DeviceIds.base64UrlEncode(ownPublicKey)
    private val runMutex = Mutex()

    private val _status = MutableStateFlow("Not synced yet")
    /** Human-readable state of the last / current run — shown on the Account screen. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastSummary = MutableStateFlow<String?>(null)
    /** The counts line of the last completed run, or null before the first. */
    val lastSummary: StateFlow<String?> = _lastSummary.asStateFlow()

    /**
     * Runs one sync pass and returns its summary line (also in [status]).
     * Safe to call from every trigger (app start, network callback, relay
     * service start, manual button): concurrent calls collapse — a second
     * caller while a run is in flight just gets the current status.
     */
    suspend fun syncNow(): String {
        if (!runMutex.tryLock()) return _status.value
        try {
            return runSync()
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun runSync(): String {
        if (baseUrlProvider() == null) {
            return finish("No server URL set — save one on the Account screen first.")
        }
        if (!internetChecker.refresh()) {
            return finish("Offline — the server health check failed, nothing was uploaded.")
        }
        val runStartedAt = nowMs()

        // (c) Server pubkey: refresh the cache every run. A changed keyId
        // replaces the cache (older acks then stop verifying). A failed
        // fetch is survivable only when a cache already exists.
        when (val pubkeyResult = api.syncPubkey()) {
            is ApiResult.Success -> {
                val fetched = pubkeyResult.value
                if (keyCache.cached()?.first != fetched.keyId) {
                    keyCache.save(fetched.keyId, fetched.publicKey)
                }
            }
            else -> if (keyCache.cached() == null) {
                return finish(
                    "Could not fetch the server public key — acks could not be verified, nothing was uploaded.",
                    runStartedAt, success = false
                )
            }
        }

        // Token order (§6): user token; on absence or a 401 from the batch
        // call, a device token for THIS run only (never persisted here).
        var token = tokenProvider()
        var deviceTokenTried = false
        if (token == null) {
            deviceTokenTried = true
            token = fetchDeviceToken()
                ?: return finish(LOGIN_NEEDED_STATUS, runStartedAt, success = false)
        }

        var accepted = 0
        var alreadyExists = 0
        var rejected = 0
        var unverified = 0
        var uploaded = 0
        val attemptedThisRun = mutableSetOf<String>()

        while (true) {
            val candidates = messageDao.findPendingForSync(nowMs(), BATCH_SIZE)
                .filter { it.messageId !in attemptedThisRun }
            if (candidates.isEmpty()) break

            // Build this batch: GATEWAY hop appends for relayed records
            // happen BEFORE upload and are persisted to the stored row.
            val items = mutableListOf<SyncBatchItem>()
            val batchById = mutableMapOf<String, MessageEntity>()
            for (entity in candidates) {
                attemptedThisRun += entity.messageId
                val prepared = prepareForUpload(entity) ?: continue // unusable origin key — skip silently
                items += SyncBatchItem(prepared.toSyncEnvelopeDto(json), originPublicKeyFor(prepared))
                batchById[prepared.messageId] = prepared
            }
            if (items.isEmpty()) continue

            var response = api.syncBatch("Bearer $token", SyncBatchRequest(ownDeviceId, items))
            if (response is ApiResult.HttpError && response.code == 401 && !deviceTokenTried) {
                deviceTokenTried = true
                val deviceToken = fetchDeviceToken()
                    ?: return finish(LOGIN_NEEDED_STATUS, runStartedAt, success = false)
                token = deviceToken
                response = api.syncBatch("Bearer $token", SyncBatchRequest(ownDeviceId, items))
            }
            when (response) {
                is ApiResult.Success -> {
                    uploaded += items.size
                    for (result in response.value.results) {
                        when (result.status) {
                            STATUS_ACCEPTED, STATUS_ALREADY_EXISTS -> {
                                val ackDto = result.serverAck
                                val verified = ackDto != null && ackStore.isAckVerified(
                                    ServerAck(ackDto.messageId, ackDto.status, ackDto.issuedAt, ackDto.signature)
                                )
                                if (ackDto != null && verified) {
                                    ackStore.recordAck(
                                        ServerAck(ackDto.messageId, ackDto.status, ackDto.issuedAt, ackDto.signature)
                                    )
                                    messageDao.updateState(result.messageId, LocalState.SERVER_SYNCED.name)
                                    if (result.status == STATUS_ACCEPTED) accepted++ else alreadyExists++
                                } else {
                                    // Never mark on an unverifiable ack (§6).
                                    unverified++
                                }
                            }
                            STATUS_REJECTED -> {
                                messageDao.updateState(result.messageId, LocalState.REJECTED.name)
                                metricsDao.insert(
                                    MetricsEntity(
                                        event = EVENT_SYNC,
                                        messageId = result.messageId,
                                        peerId = null,
                                        startedAt = nowMs(),
                                        durationMs = 0,
                                        hopCount = batchById[result.messageId]?.hopCount ?: 0,
                                        success = false,
                                        detail = result.reason
                                    )
                                )
                                rejected++
                            }
                            else -> unverified++ // Unknown status: leave the record untouched.
                        }
                    }
                }
                is ApiResult.HttpError -> {
                    if (response.code == 401) {
                        return finish(LOGIN_NEEDED_STATUS, runStartedAt, success = false)
                    }
                    // HTTP-level failure: abort, states unchanged (§6).
                    return finish(
                        "Sync stopped: HTTP ${response.code} (${response.message}). Record states were left unchanged.",
                        runStartedAt, success = false
                    )
                }
                is ApiResult.Unreachable -> return finish(
                    "Sync stopped: ${response.detail} Record states were left unchanged.",
                    runStartedAt, success = false
                )
            }
        }

        val summary = if (uploaded == 0 && attemptedThisRun.isEmpty()) {
            "Nothing to sync — no stored records are waiting for the server."
        } else {
            "Synced: $accepted new, $alreadyExists already on server, " +
                "$rejected rejected, ack unverified for $unverified records."
        }
        return finish(summary, runStartedAt, success = true, countsSummary = summary)
    }

    /**
     * The copy of [entity] to upload. Own records go as stored. A relayed
     * record first gains this phone's GATEWAY hop entry — appended to the
     * STORED row (via the DAO) and to the uploaded copy, which are the same
     * updated entity. Returns null when the record can never pass the
     * server's checks (missing/invalid entry 0, or the GATEWAY append
     * would exceed the hop limit): skipped silently, state unchanged.
     */
    private suspend fun prepareForUpload(entity: MessageEntity): MessageEntity? {
        if (entity.originDeviceId == ownDeviceId) return entity
        val trace = HopTrace.parseTrace(entity.hopTrace) ?: return null
        val entry0 = trace.firstOrNull() ?: return null
        if (entry0.role != HopTrace.ROLE_ORIGIN || entry0.deviceId != entity.originDeviceId) return null
        val originKey = try {
            DeviceIds.base64UrlDecode(entry0.pk)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!DeviceIds.isValidOriginDeviceId(entity.originDeviceId, originKey)) return null
        // Appending would make hopCount = trace.size, which must stay <= hopLimit (§2 drop rule).
        if (trace.size > entity.hopLimit) return null
        val net = networkTypeProvider()?.takeIf { it == HopTrace.NET_WIFI || it == HopTrace.NET_CELLULAR }
        val gatewayEntry = hopSigner.appendEntry(
            messageId = entity.messageId,
            trace = trace,
            role = HopTrace.ROLE_GATEWAY,
            at = nowMs(),
            net = net
        )
        val newTrace = trace + gatewayEntry
        val updated = entity.copy(
            hopTrace = HopTrace.traceToJson(newTrace),
            hopCount = newTrace.size - 1
        )
        messageDao.updateHopTrace(updated.messageId, updated.hopTrace, updated.hopCount)
        return updated
    }

    /** Own pubkey for own records; hop entry 0's pk for relayed ones (already validated in [prepareForUpload]). */
    private fun originPublicKeyFor(entity: MessageEntity): String {
        if (entity.originDeviceId == ownDeviceId) return ownPublicKeyB64
        val entry0 = HopTrace.parseTrace(entity.hopTrace)?.firstOrNull()
        return entry0?.pk ?: ownPublicKeyB64
    }

    private suspend fun fetchDeviceToken(): String? =
        when (val result = api.deviceToken(ownDeviceId)) {
            is ApiResult.Success -> result.value.token
            else -> null
        }

    /** Sets the status line, records the per-run SYNC metrics row when a run really started, and returns the line. */
    private suspend fun finish(
        line: String,
        runStartedAt: Long? = null,
        success: Boolean = false,
        countsSummary: String? = null
    ): String {
        _status.value = line
        if (countsSummary != null) _lastSummary.value = countsSummary
        if (runStartedAt != null) {
            metricsDao.insert(
                MetricsEntity(
                    event = EVENT_SYNC,
                    messageId = null,
                    peerId = null,
                    startedAt = runStartedAt,
                    durationMs = nowMs() - runStartedAt,
                    hopCount = 0,
                    success = success,
                    detail = countsSummary ?: line
                )
            )
        }
        return line
    }

    companion object {
        const val BATCH_SIZE = 50
        const val EVENT_SYNC = "SYNC"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_ALREADY_EXISTS = "ALREADY_EXISTS"
        const val STATUS_REJECTED = "REJECTED"
        const val LOGIN_NEEDED_STATUS =
            "login needed — sign in on the Account screen (or bind this phone to an account) and sync again."
    }
}
