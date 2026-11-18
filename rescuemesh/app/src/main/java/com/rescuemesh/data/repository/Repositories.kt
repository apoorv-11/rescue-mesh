package com.rescuemesh.data.repository

import com.rescuemesh.data.local.MessageDao
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsDao
import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.data.local.PeerDao
import com.rescuemesh.data.local.PeerEntity
import com.rescuemesh.data.local.ProfileDao
import com.rescuemesh.data.local.ProfileEntity
import com.rescuemesh.crypto.DeviceIdentity
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PhotoRef
import com.rescuemesh.domain.PhotoStore
import com.rescuemesh.domain.Role
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first rule (the plan's Rule 1): every write goes to Room first.
 * Nothing in this file — or anywhere in Phase 1 — makes a network call.
 * Signing (Prompt 4) is local cryptography, so it changes nothing about that.
 */

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val deviceIdProvider: () -> String
) {
    fun observeProfile(): Flow<ProfileEntity?> = profileDao.observeProfile()

    suspend fun getProfile(): ProfileEntity? = profileDao.getProfile()

    /**
     * Creates the single local profile on first onboarding. The deviceId is
     * always the [DeviceIdentity] originDeviceId — since Prompt 4 there is no
     * random-UUID device id anymore (see migrateDeviceIdIfNeeded).
     */
    suspend fun saveProfile(displayName: String, role: Role): ProfileEntity {
        val existing = profileDao.getProfile()
        val profile = ProfileEntity(
            displayName = displayName.trim(),
            role = role.name,
            deviceId = deviceIdProvider(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        profileDao.upsert(profile)
        return profile
    }

    /**
     * Mirrors a role the SERVER has confirmed (via GET /me) into the local
     * profile, so Home and the Account screen always show one role.
     *
     * Called only after a successful server read — never on logout, never
     * when the network fails, never on an HTTP error. The rule behind that:
     * a granted role is a fact about the person, so a dead server (or
     * signing out) must not un-grant it locally; only a positive server
     * answer may overwrite it. Returns true when the stored role changed.
     */
    suspend fun mirrorServerRole(role: Role): Boolean {
        val existing = profileDao.getProfile() ?: return false
        if (existing.role == role.name) return false
        profileDao.updateRole(role.name)
        return true
    }

    /**
     * Phase 1 -> Prompt 4 upgrade: early builds stored a random UUID as the
     * profile's deviceId. That data is Phase 1 test data, and the identity is
     * now derived from the device key, so a stored id that differs from the
     * identity's originDeviceId is replaced with it. Runs at app start;
     * a no-op once migrated.
     */
    suspend fun migrateDeviceIdIfNeeded() {
        val existing = profileDao.getProfile() ?: return
        val identityDeviceId = deviceIdProvider()
        if (existing.deviceId != identityDeviceId) {
            profileDao.upsert(existing.copy(deviceId = identityDeviceId))
        }
    }
}

/**
 * Result of a guarded creation (batch contracts §4.9). Creation either
 * succeeds with the stored record, or is REFUSED with a user-readable
 * reason the UI shows in its status line — it never throws into the UI.
 */
sealed interface MessageCreationResult {
    data class Created(val message: MessageEntity) : MessageCreationResult
    data class Refused(val reason: String) : MessageCreationResult
}

class MessageRepository(
    private val messageDao: MessageDao,
    private val signer: EnvelopeSigner,
    private val hopSigner: HopSigner? = null,
    private val factory: MessageFactory = MessageFactory(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val photoStore: PhotoStore? = null
) {
    fun observeMessages(): Flow<List<MessageEntity>> = messageDao.observeAll()

    /** One record type, newest first — the Prompt 6 lists and the Found form's case picker. */
    fun observeByType(eventType: String): Flow<List<MessageEntity>> = messageDao.observeByType(eventType)

    /**
     * THE single creation funnel (Prompt 5): every locally created record —
     * quick SOS, I Am Safe, and the Prompt 6 form paths later — goes through
     * here: factory draft → hop entry 0 attached (role ORIGIN, at =
     * createdAt, prevSig "") → envelope signed → inserted into Room.
     *
     * Entry 0 is attached only when this repository has a [HopSigner] whose
     * deviceId matches the record's origin (in production both come from
     * the same DeviceIdentity). Without one, the draft's empty trace is
     * stored as-is — the pre-Prompt-5 behaviour, kept for tests that build
     * the repository without an identity.
     */
    private suspend fun createAndStore(draft: MessageEntity): MessageEntity {
        val withEntry0 = if (hopSigner != null && hopSigner.deviceId == draft.originDeviceId) {
            val entry0 = hopSigner.originEntry(draft.messageId, draft.createdAt)
            draft.copy(hopTrace = HopTrace.traceToJson(listOf(entry0)), hopCount = 0)
        } else {
            draft
        }
        val signed = signer.signedCopy(withEntry0)
        messageDao.insertIfAbsent(signed)
        return signed
    }

    /**
     * Two-tap SOS is enforced in the UI; this is the second tap's action.
     *
     * SOS origination guard (contracts §4.9): at most [MAX_SOS_PER_WINDOW]
     * new SOS per device per rolling 60 s, counted here in the funnel so
     * every creation path is covered. Excess creation is refused with a
     * typed result — never an exception.
     */
    suspend fun sendSos(originDeviceId: String, category: String? = null): MessageCreationResult {
        sosGuardRefusal(originDeviceId)?.let { return it }
        return MessageCreationResult.Created(createAndStore(factory.createSos(originDeviceId, category)))
    }

    /** The §4.9 guard, shared by every SOS creation path (quick SOS and the Prompt 6 form). */
    private suspend fun sosGuardRefusal(originDeviceId: String): MessageCreationResult.Refused? {
        val windowStart = nowMs() - SOS_GUARD_WINDOW_MS
        return if (messageDao.countOwnSosSince(originDeviceId, windowStart) >= MAX_SOS_PER_WINDOW) {
            MessageCreationResult.Refused(SOS_GUARD_MESSAGE)
        } else {
            null
        }
    }

    /** One-tap I Am Safe; funnelled (entry 0 + sign + store) like SOS. */
    suspend fun sendSafetyCheckin(originDeviceId: String): MessageEntity =
        createAndStore(factory.createSafetyCheckin(originDeviceId))

    // ------------------------------------------------ Prompt 6 form paths
    // All of these go through the same creation funnel as quick SOS
    // (entry 0 → sign → insert, state CREATED). Payload shapes: factory /
    // contracts §7.

    /** SOS form submission; the §4.9 rate guard applies exactly as for quick SOS. */
    suspend fun sendSosDetailed(
        originDeviceId: String,
        category: String,
        description: String,
        peopleCount: Int,
        injured: Int? = null,
        needs: List<String> = emptyList(),
        vulnerable: List<String> = emptyList(),
        location: com.rescuemesh.domain.PayloadLocation? = null
    ): MessageCreationResult {
        sosGuardRefusal(originDeviceId)?.let { return it }
        return MessageCreationResult.Created(
            createAndStore(
                factory.createSosDetailed(
                    originDeviceId, category, description, peopleCount,
                    injured, needs, vulnerable, location
                )
            )
        )
    }

    suspend fun reportMissingPerson(
        originDeviceId: String,
        name: String? = null,
        age: Int? = null,
        description: String,
        lastSeenArea: String,
        lastSeenAtMs: Long,
        photoThumbB64: String? = null,
        photoOmitted: Boolean = false,
        fullPhotoJpeg: ByteArray? = null
    ): MessageEntity {
        if (fullPhotoJpeg == null) {
            return createAndStore(
                factory.createMissingPerson(
                    originDeviceId, name, age, description, lastSeenArea,
                    lastSeenAtMs, photoThumbB64, photoOmitted
                )
            )
        }
        // Full photo (photo-transfer design): the signed photoRef carries
        // photoId = the record's own messageId, so the id is fixed FIRST
        // and the draft built around it; the JPEG bytes go to PhotoStore
        // under that id once the record itself is stored (Room first).
        val messageId = UUID.randomUUID().toString()
        val photoFactory = MessageFactory(nowMs = nowMs, newId = { messageId })
        val stored = createAndStore(
            photoFactory.createMissingPerson(
                originDeviceId, name, age, description, lastSeenArea,
                lastSeenAtMs, photoThumbB64, photoOmitted,
                photoRef = PhotoRef.of(messageId, fullPhotoJpeg)
            )
        )
        photoStore?.saveComplete(messageId, fullPhotoJpeg)
        return stored
    }

    suspend fun reportPersonFound(
        originDeviceId: String,
        caseId: String,
        note: String,
        location: com.rescuemesh.domain.PayloadLocation? = null,
        photoThumbB64: String? = null,
        photoOmitted: Boolean = false,
        fullPhotoJpeg: ByteArray? = null
    ): MessageEntity {
        if (fullPhotoJpeg == null) {
            return createAndStore(
                factory.createPersonFound(
                    originDeviceId, caseId, note, location,
                    photoThumbB64, photoOmitted
                )
            )
        }
        // Same full-photo path as Missing: fixed id → signed photoRef
        // (photoId = THIS record's id) → record stored → bytes to PhotoStore.
        val messageId = UUID.randomUUID().toString()
        val photoFactory = MessageFactory(nowMs = nowMs, newId = { messageId })
        val stored = createAndStore(
            photoFactory.createPersonFound(
                originDeviceId, caseId, note, location,
                photoThumbB64, photoOmitted,
                photoRef = PhotoRef.of(messageId, fullPhotoJpeg)
            )
        )
        photoStore?.saveComplete(messageId, fullPhotoJpeg)
        return stored
    }

    suspend fun postAnnouncement(
        originDeviceId: String,
        category: String,
        text: String,
        validUntilMs: Long
    ): MessageEntity = createAndStore(factory.createAnnouncement(originDeviceId, category, text, validUntilMs))

    suspend fun postResourceUpdate(
        originDeviceId: String,
        resourceType: String,
        quantity: Int,
        note: String? = null,
        location: com.rescuemesh.domain.PayloadLocation? = null
    ): MessageEntity = createAndStore(
        factory.createResourceUpdate(originDeviceId, resourceType, quantity, note, location)
    )

    /** Stores a record received from a peer. Returns false when it was a duplicate (FR-06). */
    suspend fun storeReceived(message: MessageEntity): Boolean =
        messageDao.insertIfAbsent(message) != -1L

    suspend fun count(): Int = messageDao.count()

    companion object {
        const val MAX_SOS_PER_WINDOW = 5
        const val SOS_GUARD_WINDOW_MS = 60_000L
        const val SOS_GUARD_MESSAGE = "Too many SOS — wait a moment"
    }
}

/**
 * Metrics → CSV for the mesh debug screen's export action (contracts §6).
 * Header: id,event,messageId,peerId,startedAt,durationMs,hopCount,success,detail.
 * Basic RFC-4180 quoting: a field containing a comma, quote or newline is
 * wrapped in double quotes with inner quotes doubled. Pure — unit-tested.
 */
fun metricsToCsv(rows: List<MetricsEntity>): String = buildString {
    append("id,event,messageId,peerId,startedAt,durationMs,hopCount,success,detail\n")
    for (row in rows) {
        append(row.id).append(',')
        append(csvField(row.event)).append(',')
        append(csvField(row.messageId)).append(',')
        append(csvField(row.peerId)).append(',')
        append(row.startedAt).append(',')
        append(row.durationMs).append(',')
        append(row.hopCount).append(',')
        append(row.success).append(',')
        append(csvField(row.detail)).append('\n')
    }
}

private fun csvField(value: String?): String {
    if (value == null) return ""
    return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}

class PeerRepository(private val peerDao: PeerDao) {
    fun observePeers(): Flow<List<PeerEntity>> = peerDao.observeAll()

    suspend fun onPeerSeen(endpointId: String, deviceName: String, connected: Boolean) {
        val now = System.currentTimeMillis()
        val existing = peerDao.getByEndpointId(endpointId)
        peerDao.upsert(
            PeerEntity(
                endpointId = endpointId,
                deviceName = deviceName,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastConnectedAt = if (connected) now else existing?.lastConnectedAt,
                connectionState = if (connected) "CONNECTED" else "DISCOVERED"
            )
        )
    }

    suspend fun onPeerState(endpointId: String, state: String) {
        peerDao.updateState(endpointId, state, System.currentTimeMillis())
    }
}

class MetricsRepository(private val metricsDao: MetricsDao) {
    fun observeRecent(limit: Int = 20): Flow<List<MetricsEntity>> = metricsDao.observeRecent(limit)

    /** Every metrics row, oldest first — the Prompt 9 CSV export source. */
    suspend fun allForExport(): List<MetricsEntity> = metricsDao.getAllForExport()

    suspend fun record(
        event: String,
        messageId: String?,
        peerId: String?,
        startedAt: Long,
        durationMs: Long,
        hopCount: Int = 0,
        success: Boolean,
        detail: String? = null
    ) {
        metricsDao.insert(
            MetricsEntity(
                event = event,
                messageId = messageId,
                peerId = peerId,
                startedAt = startedAt,
                durationMs = durationMs,
                hopCount = hopCount,
                success = success,
                detail = detail
            )
        )
    }
}
