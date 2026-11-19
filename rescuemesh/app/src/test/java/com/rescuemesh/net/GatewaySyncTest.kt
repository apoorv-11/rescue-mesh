package com.rescuemesh.net

import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import com.rescuemesh.InMemoryMessageDao
import com.rescuemesh.InMemoryMetricsDao
import com.rescuemesh.InMemoryServerAckDao
import com.rescuemesh.InMemoryServerKeyCache
import com.rescuemesh.TestIdentity
import com.rescuemesh.TestTink
import com.rescuemesh.crypto.CanonicalJson
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.repository.RoomServerAckStore
import com.rescuemesh.domain.MessageFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Prompt 9 gateway sync (contracts §6) against a hand-written fake of the
 * [SyncApi] seam: GATEWAY hop appends for relayed records, verified-ack
 * marking, unverified-ack refusal, REJECTED terminality, the device-token
 * 401 fallback, and ALREADY_EXISTS. The fake server signs acks with a real
 * Tink keypair, exactly like the real server's sync key.
 */
class GatewaySyncTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            TestTink.register()
        }
    }

    private val clock = 1_700_000_000_000L
    private val gateway = TestIdentity()
    private val origin = TestIdentity()
    private val server = TestIdentity()
    private val impostor = TestIdentity()

    /** Signs an ack the way the server does: canonical {issuedAt, messageId, status} under its sync key. */
    private fun signAck(signerIdentity: TestIdentity, messageId: String, status: String): ServerAckDto {
        val signer = signerIdentity.keysetHandle.getPrimitive(
            RegistryConfiguration.get(), PublicKeySign::class.java
        )
        val bytes = CanonicalJson.canonicalBytes(
            linkedMapOf<String, Any?>(
                "issuedAt" to clock,
                "messageId" to messageId,
                "status" to status
            )
        )
        return ServerAckDto(messageId, status, clock, DeviceIds.base64UrlEncode(signer.sign(bytes)))
    }

    private inner class FakeSyncApi : SyncApi {
        val batchCalls = mutableListOf<Pair<String, SyncBatchRequest>>()
        val deviceTokenCalls = mutableListOf<String>()
        val photoCalls = mutableListOf<Triple<String, String, ByteArray>>()
        var onPhoto: (String, String, ByteArray) -> ApiResult<Unit> =
            { _, _, _ -> ApiResult.Success(Unit) }
        var onBatch: (SyncBatchRequest) -> ApiResult<SyncBatchResponse> = { request ->
            ApiResult.Success(
                SyncBatchResponse(
                    serverTime = clock,
                    results = request.items.map {
                        SyncItemResult(
                            it.envelope.messageId, GatewaySync.STATUS_ACCEPTED,
                            serverAck = signAck(server, it.envelope.messageId, GatewaySync.STATUS_ACCEPTED)
                        )
                    }
                )
            )
        }
        var onDeviceToken: (String) -> ApiResult<TokenResponse> = {
            ApiResult.Success(TokenResponse("device-token"))
        }

        override suspend fun syncPubkey(): ApiResult<SyncPubkeyResponse> =
            ApiResult.Success(
                SyncPubkeyResponse(server.deviceId.take(16), DeviceIds.base64UrlEncode(server.rawPublicKey))
            )

        override suspend fun syncBatch(
            authorization: String,
            request: SyncBatchRequest
        ): ApiResult<SyncBatchResponse> {
            batchCalls += authorization to request
            return onBatch(request)
        }

        override suspend fun deviceToken(deviceId: String): ApiResult<TokenResponse> {
            deviceTokenCalls += deviceId
            return onDeviceToken(deviceId)
        }

        override suspend fun uploadPhoto(
            authorization: String,
            messageId: String,
            sha256: String,
            jpegBytes: ByteArray
        ): ApiResult<Unit> {
            photoCalls += Triple(authorization, messageId, jpegBytes)
            return onPhoto(messageId, sha256, jpegBytes)
        }
    }

    private class Fixture(
        val messageDao: InMemoryMessageDao,
        val metricsDao: InMemoryMetricsDao,
        val ackDao: InMemoryServerAckDao,
        val keyCache: InMemoryServerKeyCache,
        val api: FakeSyncApi,
        val sync: GatewaySync
    )

    private fun fixture(userToken: String? = "user-token"): Fixture {
        val messageDao = InMemoryMessageDao()
        val metricsDao = InMemoryMetricsDao()
        val ackDao = InMemoryServerAckDao()
        val keyCache = InMemoryServerKeyCache()
        val api = FakeSyncApi()
        val checker = InternetChecker(baseUrlProvider = { "http://server.test" }, healthProbe = { true })
        val sync = GatewaySync(
            api = api,
            baseUrlProvider = { "http://server.test" },
            tokenProvider = { userToken },
            messageDao = messageDao,
            metricsDao = metricsDao,
            ackStore = RoomServerAckStore(ackDao, keyCache),
            keyCache = keyCache,
            hopSigner = gateway.hopSigner,
            ownDeviceId = gateway.deviceId,
            ownPublicKey = gateway.rawPublicKey,
            internetChecker = checker,
            networkTypeProvider = { "WIFI" },
            nowMs = { clock }
        )
        return Fixture(messageDao, metricsDao, ackDao, keyCache, api, sync)
    }

    private var idCounter = 0

    /** A record this gateway phone ORIGINATED (entry 0 by the gateway, state CREATED). */
    private fun ownRecord(): MessageEntity {
        val factory = MessageFactory(nowMs = { clock }, newId = { "own-%04d".format(++idCounter) })
        val draft = factory.createSos(gateway.deviceId, "MEDICAL")
        val entry0 = gateway.hopSigner.originEntry(draft.messageId, draft.createdAt)
        val traced = draft.copy(hopTrace = HopTrace.traceToJson(listOf(entry0)))
        return gateway.envelopeSigner.signedCopy(traced)
    }

    /** A record ANOTHER phone originated, stored here as a relay copy (state QUEUED, not own). */
    private fun relayedRecord(): MessageEntity {
        val factory = MessageFactory(nowMs = { clock }, newId = { "relayed-%04d".format(++idCounter) })
        val draft = factory.createSos(origin.deviceId, "FIRE")
        val entry0 = origin.hopSigner.originEntry(draft.messageId, draft.createdAt)
        val traced = draft.copy(hopTrace = HopTrace.traceToJson(listOf(entry0)))
        return origin.envelopeSigner.signedCopy(traced).copy(localState = "QUEUED", isOwn = false)
    }

    @Test
    fun `relayed copy gains a GATEWAY entry before upload, own copy is unchanged`() = runBlocking {
        val f = fixture()
        val own = ownRecord()
        val relayed = relayedRecord()
        f.messageDao.insertIfAbsent(own)
        f.messageDao.insertIfAbsent(relayed)

        f.sync.syncNow()

        assertEquals(1, f.api.batchCalls.size)
        val items = f.api.batchCalls[0].second.items
        assertEquals(2, items.size)
        val uploadedRelayed = items.first { it.envelope.messageId == relayed.messageId }
        val uploadedOwn = items.first { it.envelope.messageId == own.messageId }

        // Relayed: trace grew by the GATEWAY entry (WIFI), origin key = entry 0's pk.
        val relayedTrace = HopTrace.parseTrace(uploadedRelayed.envelope.hopTrace.toString())!!
        assertEquals(2, relayedTrace.size)
        assertEquals(HopTrace.ROLE_GATEWAY, relayedTrace[1].role)
        assertEquals(gateway.deviceId, relayedTrace[1].deviceId)
        assertEquals("WIFI", relayedTrace[1].net)
        assertEquals(1, uploadedRelayed.envelope.hopCount)
        assertEquals(DeviceIds.base64UrlEncode(origin.rawPublicKey), uploadedRelayed.originPublicKey)
        // The GATEWAY append was persisted to the stored row too.
        val storedRelayed = f.messageDao.getById(relayed.messageId)!!
        assertEquals(1, storedRelayed.hopCount)
        assertEquals(2, HopTrace.parseTrace(storedRelayed.hopTrace)?.size)

        // Own: uploaded exactly as stored, with our own pubkey.
        val ownTrace = HopTrace.parseTrace(uploadedOwn.envelope.hopTrace.toString())!!
        assertEquals(1, ownTrace.size)
        assertEquals(0, uploadedOwn.envelope.hopCount)
        assertEquals(DeviceIds.base64UrlEncode(gateway.rawPublicKey), uploadedOwn.originPublicKey)
    }

    @Test
    fun `ACCEPTED with a verified ack marks SERVER_SYNCED and stores the ack`() = runBlocking {
        val f = fixture()
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)

        val summary = f.sync.syncNow()

        assertEquals("SERVER_SYNCED", f.messageDao.getById(own.messageId)?.localState)
        val storedAck = f.ackDao.getById(own.messageId)
        assertNotNull(storedAck)
        assertEquals(GatewaySync.STATUS_ACCEPTED, storedAck?.status)
        assertTrue(summary.contains("1 new"))
        // The server pubkey was cached during the run.
        assertEquals(server.deviceId.take(16), f.keyCache.cached()?.first)
    }

    @Test
    fun `ack with the WRONG signature does not mark synced and is counted unverified`() = runBlocking {
        val f = fixture()
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)
        f.api.onBatch = { request ->
            ApiResult.Success(
                SyncBatchResponse(
                    serverTime = clock,
                    results = request.items.map {
                        SyncItemResult(
                            it.envelope.messageId, GatewaySync.STATUS_ACCEPTED,
                            // Signed by an impostor key, not the server's.
                            serverAck = signAck(impostor, it.envelope.messageId, GatewaySync.STATUS_ACCEPTED)
                        )
                    }
                )
            )
        }

        val summary = f.sync.syncNow()

        assertEquals("CREATED", f.messageDao.getById(own.messageId)?.localState)
        assertNull(f.ackDao.getById(own.messageId))
        assertTrue(summary.contains("ack unverified for 1"))
    }

    @Test
    fun `REJECTED marks the record terminal and it is never selected again`() = runBlocking {
        val f = fixture()
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)
        f.api.onBatch = { request ->
            ApiResult.Success(
                SyncBatchResponse(
                    serverTime = clock,
                    results = request.items.map {
                        SyncItemResult(it.envelope.messageId, GatewaySync.STATUS_REJECTED, reason = "bad-signature")
                    }
                )
            )
        }

        f.sync.syncNow()
        assertEquals("REJECTED", f.messageDao.getById(own.messageId)?.localState)
        // A per-record SYNC metrics row carries the server's reason.
        val rejectRow = f.metricsDao.rows.firstOrNull {
            it.event == "SYNC" && !it.success && it.messageId == own.messageId
        }
        assertEquals("bad-signature", rejectRow?.detail)

        val callsAfterFirstRun = f.api.batchCalls.size
        f.sync.syncNow()
        assertEquals("second run must upload nothing", callsAfterFirstRun, f.api.batchCalls.size)
    }

    @Test
    fun `401 falls back to a device token and retries the batch with it`() = runBlocking {
        val f = fixture(userToken = "expired-user-token")
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)
        var attempts = 0
        f.api.onBatch = { request ->
            attempts++
            if (attempts == 1) {
                ApiResult.HttpError(401, "Unauthorized")
            } else {
                ApiResult.Success(
                    SyncBatchResponse(
                        serverTime = clock,
                        results = request.items.map {
                            SyncItemResult(
                                it.envelope.messageId, GatewaySync.STATUS_ACCEPTED,
                                serverAck = signAck(server, it.envelope.messageId, GatewaySync.STATUS_ACCEPTED)
                            )
                        }
                    )
                )
            }
        }

        f.sync.syncNow()

        assertEquals(listOf(gateway.deviceId), f.api.deviceTokenCalls)
        assertEquals(2, f.api.batchCalls.size)
        assertEquals("Bearer expired-user-token", f.api.batchCalls[0].first)
        assertEquals("Bearer device-token", f.api.batchCalls[1].first)
        assertEquals("SERVER_SYNCED", f.messageDao.getById(own.messageId)?.localState)
    }

    @Test
    fun `no user token goes straight to the device token`() = runBlocking {
        val f = fixture(userToken = null)
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)

        f.sync.syncNow()

        assertEquals(listOf(gateway.deviceId), f.api.deviceTokenCalls)
        assertEquals("Bearer device-token", f.api.batchCalls[0].first)
        assertEquals("SERVER_SYNCED", f.messageDao.getById(own.messageId)?.localState)
    }

    @Test
    fun `ALREADY_EXISTS with a verified ack also marks synced`() = runBlocking {
        val f = fixture()
        val own = ownRecord()
        f.messageDao.insertIfAbsent(own)
        f.api.onBatch = { request ->
            ApiResult.Success(
                SyncBatchResponse(
                    serverTime = clock,
                    results = request.items.map {
                        SyncItemResult(
                            it.envelope.messageId, GatewaySync.STATUS_ALREADY_EXISTS,
                            serverAck = signAck(server, it.envelope.messageId, GatewaySync.STATUS_ALREADY_EXISTS)
                        )
                    }
                )
            )
        }

        val summary = f.sync.syncNow()

        assertEquals("SERVER_SYNCED", f.messageDao.getById(own.messageId)?.localState)
        assertEquals(GatewaySync.STATUS_ALREADY_EXISTS, f.ackDao.getById(own.messageId)?.status)
        assertTrue(summary.contains("1 already on server"))
    }

    @Test
    fun `batch request JSON carries the contract field names`() {
        val f = fixture()
        val own = ownRecord()
        val request = SyncBatchRequest(
            deviceId = gateway.deviceId,
            items = listOf(
                SyncBatchItem(own.toSyncEnvelopeDto(Json), DeviceIds.base64UrlEncode(gateway.rawPublicKey))
            )
        )
        val text = Json.encodeToString(SyncBatchRequest.serializer(), request)
        assertTrue(text.contains("\"deviceId\":\"${gateway.deviceId}\""))
        assertTrue(text.contains("\"originPublicKey\""))
        assertTrue(text.contains("\"hopTrace\":[{"))
        assertTrue(text.contains("\"payload\":{"))
        assertTrue(text.contains("\"messageId\":\"${own.messageId}\""))
    }
}
