package com.rescuemesh.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rescuemesh.RelayService
import com.rescuemesh.RescueMeshApp
import com.rescuemesh.data.repository.MessageCreationResult
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsEntity
import com.rescuemesh.data.local.PeerEntity
import com.rescuemesh.data.local.ProfileEntity
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.data.repository.metricsToCsv
import com.rescuemesh.domain.PayloadLocation
import com.rescuemesh.domain.Role
import com.rescuemesh.mesh.IncomingPayload
import com.rescuemesh.mesh.MeshPeer
import com.rescuemesh.mesh.NearbyPermissions
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RescueMeshApp
    private val transport = app.meshTransport

    val profile: StateFlow<ProfileEntity?> = app.profileRepository.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> = app.messageRepository.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storedPeers: StateFlow<List<PeerEntity>> = app.peerRepository.observePeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentMetrics: StateFlow<List<MetricsEntity>> = app.metricsRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _meshCounts = MutableStateFlow(MeshCounts.ZERO)

    /**
     * Totals for the Mesh tab's Sent / Relayed / Dropped strip
     * ([MeshCounts]), counted from ALL metrics rows — [recentMetrics]
     * only carries the latest 20, which would undercount a busy
     * session. Refreshed whenever the recent list emits (any new
     * metrics row also changes that list). Additive only: nothing else
     * reads or depends on this flow.
     */
    val meshCounts: StateFlow<MeshCounts> = _meshCounts.asStateFlow()

    val livePeers: StateFlow<List<MeshPeer>> = transport.peers
    val meshRunning: StateFlow<Boolean> = transport.isRunning

    private val _receivedTexts = MutableStateFlow<List<String>>(emptyList())
    /** Test payloads received over the mesh (spike screen), newest first. */
    val receivedTexts: StateFlow<List<String>> = _receivedTexts.asStateFlow()

    private val _lastSendInfo = MutableStateFlow<String?>(null)
    /** Human-readable result of the last spike send, including the time it took. */
    val lastSendInfo: StateFlow<String?> = _lastSendInfo.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Permission gate (8038 fix): the ViewModel has no Activity, so when a
    // start is blocked by missing Nearby permissions it explains on the
    // status line and emits here; the mesh debug screen collects this and
    // fires the system permission prompt via its onRequestPermissions
    // callback. One-shot, no replay: a screen that appears later must not
    // re-prompt for an old block.
    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<Unit> = _permissionRequests.asSharedFlow()

    /** True once a permission prompt has been triggered from a blocked start. */
    private var permissionPromptShown = false

    /**
     * True when every Nearby runtime permission is granted. When any are
     * missing: does NOT let the caller start anything (Nearby would fail
     * with a raw "8038: MISSING_PERMISSION_…"), explains the missing set on
     * the status line, and asks the screen to show the system prompt. A
     * repeat block adds the Settings path, for the "don't ask again" case
     * where the prompt itself no longer appears.
     */
    private fun permissionsReadyOrExplain(): Boolean {
        val missing = NearbyPermissions.missingPermissions(app)
        if (missing.isEmpty()) return true
        val settingsHint = if (permissionPromptShown) {
            " If the prompt no longer appears, allow them in Settings → Apps → RescueMesh → Permissions."
        } else {
            ""
        }
        permissionPromptShown = true
        _statusMessage.value =
            "Missing permissions: ${NearbyPermissions.friendlyLabels(missing)} — " +
                    "tap 1. Grant permissions and allow them, then try again.$settingsHint"
        _permissionRequests.tryEmit(Unit)
        return false
    }

    // Relay (Prompt 5): the debug screen toggles the RelayService. The
    // service is the real owner of the engine; these flows just reflect the
    // user's last toggle in this process.
    private val _relayOn = MutableStateFlow(false)
    val relayOn: StateFlow<Boolean> = _relayOn.asStateFlow()

    private val _ttlTestMode = MutableStateFlow(
        app.getSharedPreferences(RelayService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(RelayService.KEY_TTL_TEST_MODE, false)
    )
    /** TTL test mode: records expire 60 s after creation (gate tests only). */
    val ttlTestMode: StateFlow<Boolean> = _ttlTestMode.asStateFlow()

    fun setRelayEnabled(enabled: Boolean) {
        if (enabled && !permissionsReadyOrExplain()) {
            // Blocked: the gate explained and asked for the prompt. Keep
            // the toggle honest — the relay is NOT running.
            _relayOn.value = false
            return
        }
        if (enabled) {
            RelayService.start(app)
            _statusMessage.value = "Relay on — the relay service is running"
        } else {
            RelayService.stop(app)
            _statusMessage.value = "Relay off"
        }
        _relayOn.value = enabled
    }

    /** Persists the flag RelayService reads at engine creation; restarts the service if it is running so the new flag applies. */
    fun setTtlTestMode(enabled: Boolean) {
        app.getSharedPreferences(RelayService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(RelayService.KEY_TTL_TEST_MODE, enabled).apply()
        _ttlTestMode.value = enabled
        if (_relayOn.value) {
            if (!permissionsReadyOrExplain()) {
                // Permissions were revoked while the relay was on: do not
                // restart the service into an 8038. The gate's status
                // message (already set) wins over the TTL one below.
                _relayOn.value = false
                return
            }
            RelayService.stop(app)
            RelayService.start(app)
        }
        _statusMessage.value = if (enabled) {
            "TTL test mode ON — records expire 60 s after creation"
        } else {
            "TTL test mode off"
        }
    }

    private var discoveryStartedAtMs: Long = 0

    init {
        viewModelScope.launch {
            recentMetrics.collect {
                _meshCounts.value = MeshCounts.count(app.metricsRepository.allForExport())
            }
        }
        viewModelScope.launch {
            transport.incoming.collect { payload: IncomingPayload ->
                val text = payload.bytes.toString(Charsets.UTF_8)
                // Bounded: a relay session can carry ~12 KB packets (photo
                // thumbnails), and prepending each one to an ever-growing
                // list on the main thread gets slower with every packet.
                // The newest 50 are plenty for a debug screen.
                _receivedTexts.value = (listOf(text) + _receivedTexts.value).take(50)
            }
        }
        viewModelScope.launch {
            transport.peers.collect { peers ->
                peers.forEach { peer ->
                    val wasStored = storedPeers.value.any { it.endpointId == peer.endpointId }
                    app.peerRepository.onPeerSeen(peer.endpointId, peer.deviceName, peer.connected)
                    if (!wasStored && discoveryStartedAtMs > 0) {
                        app.metricsRepository.record(
                            event = "DISCOVERY",
                            messageId = null,
                            peerId = peer.endpointId,
                            startedAt = discoveryStartedAtMs,
                            durationMs = System.currentTimeMillis() - discoveryStartedAtMs,
                            success = true,
                            detail = "peer found"
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------ UI redesign
    // Presentation state only: the tab router and the entry-screen SOS
    // countdown. Navigation never touches the mesh, the relay, the
    // repositories or the account layer — it only moves the user's
    // eyes. The reducer itself is pure (AppNav) and unit-tested.

    private val _navState = MutableStateFlow(NavState())
    /** Router state; the default tab is ACCOUNT — the app opens on the entry/landing screen once a profile exists. */
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    fun selectTab(tab: AppTab) {
        _navState.value = AppNav.selectTab(_navState.value, tab)
    }

    fun pushSubScreen(screen: SubScreen) {
        _navState.value = AppNav.push(_navState.value, screen)
    }

    fun popSubScreen() {
        _navState.value = AppNav.pop(_navState.value)
    }

    fun popToTabRoot() {
        _navState.value = AppNav.popToRoot(_navState.value)
    }

    /**
     * System Back: pops a pushed sub-screen and returns true; at a tab
     * root returns false (Back is not handled — it never switches tabs
     * or swallows the press).
     */
    fun handleBack(): Boolean {
        val next = AppNav.back(_navState.value) ?: return false
        _navState.value = next
        return true
    }

    private var sosCountdownJob: Job? = null

    private val _sosCountdownEndsAtMs = MutableStateFlow<Long?>(null)
    /** Deadline (epoch ms) of the running entry-screen SOS countdown, or null when no countdown is running. */
    val sosCountdownEndsAtMs: StateFlow<Long?> = _sosCountdownEndsAtMs.asStateFlow()

    /**
     * Swipe-right on the Account landing: starts the 3-second
     * cancellable countdown. When it elapses, EXACTLY the Home
     * quick-SOS path runs ([sendSos] — same repository funnel, same
     * rate guard, same status line) and the app lands on the Home tab.
     */
    fun startSosCountdown() {
        if (sosCountdownJob?.isActive == true) return
        _sosCountdownEndsAtMs.value = System.currentTimeMillis() + SosCountdown.DURATION_MS
        sosCountdownJob = viewModelScope.launch {
            delay(SosCountdown.DURATION_MS)
            _sosCountdownEndsAtMs.value = null
            selectTab(AppTab.HOME)
            sendSos()
        }
    }

    /** Tap (or swipe) on the countdown overlay: the SOS is NOT sent. */
    fun cancelSosCountdown() {
        sosCountdownJob?.cancel()
        sosCountdownJob = null
        _sosCountdownEndsAtMs.value = null
    }

    fun saveProfile(displayName: String, role: Role) {
        viewModelScope.launch {
            app.profileRepository.saveProfile(displayName, role)
            _statusMessage.value = "Profile saved on this phone"
        }
    }

    /** SOS second tap (the first tap is the confirm state in the UI). Writes to Room first. */
    fun sendSos() {
        val current = profile.value ?: return
        viewModelScope.launch {
            // Prompt 5: creation goes through the repository funnel, which
            // enforces the SOS rate guard (max 5 per rolling 60 s) and
            // refuses with a typed result instead of throwing.
            when (val result = app.messageRepository.sendSos(current.deviceId)) {
                is MessageCreationResult.Created ->
                    _statusMessage.value = "SOS saved on this phone (Room). It will relay when a peer is near."
                is MessageCreationResult.Refused ->
                    _statusMessage.value = result.reason
            }
        }
    }

    /** I Am Safe: exactly one tap. Writes to Room first. */
    fun sendIAmSafe() {
        val current = profile.value ?: return
        viewModelScope.launch {
            app.messageRepository.sendSafetyCheckin(current.deviceId)
            _statusMessage.value = "I Am Safe saved on this phone (Room)."
        }
    }

    // ------------------------------------------------------------ Prompt 6
    // Records & forms: per-type Room flows for the lists / Found picker, and
    // the form submissions. Every submission goes through the repository
    // creation funnel; the result is reported back via [onResult] so the
    // form can navigate on Created and keep the refusal reason visible.

    private val byTypeFlows = mutableMapOf<String, StateFlow<List<MessageEntity>>>()

    /** Records of one event type (e.g. "SOS"), newest first. */
    fun messagesOfType(eventType: String): StateFlow<List<MessageEntity>> =
        byTypeFlows.getOrPut(eventType) {
            app.messageRepository.observeByType(eventType)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun submitSosDetailed(
        category: String,
        description: String,
        peopleCount: Int,
        injured: Int?,
        needs: List<String>,
        vulnerable: List<String>,
        location: PayloadLocation?,
        onResult: (MessageCreationResult) -> Unit
    ) {
        val current = profile.value ?: return
        viewModelScope.launch {
            onResult(
                app.messageRepository.sendSosDetailed(
                    current.deviceId, category, description, peopleCount,
                    injured, needs, vulnerable, location
                )
            )
        }
    }

    fun submitMissingPerson(
        name: String?,
        age: Int?,
        description: String,
        lastSeenArea: String,
        lastSeenAtMs: Long,
        photoThumbB64: String?,
        photoOmitted: Boolean,
        fullPhotoJpeg: ByteArray? = null,
        onResult: (MessageCreationResult) -> Unit
    ) {
        val current = profile.value ?: return
        viewModelScope.launch {
            val stored = app.messageRepository.reportMissingPerson(
                current.deviceId, name, age, description, lastSeenArea,
                lastSeenAtMs, photoThumbB64, photoOmitted, fullPhotoJpeg
            )
            onResult(MessageCreationResult.Created(stored))
        }
    }

    fun submitPersonFound(
        caseId: String,
        note: String,
        location: PayloadLocation?,
        photoThumbB64: String? = null,
        photoOmitted: Boolean = false,
        fullPhotoJpeg: ByteArray? = null,
        foundArea: String? = null,
        onResult: (MessageCreationResult) -> Unit
    ) {
        val current = profile.value ?: return
        viewModelScope.launch {
            val stored = app.messageRepository.reportPersonFound(
                current.deviceId, caseId, note, location,
                photoThumbB64, photoOmitted, fullPhotoJpeg,
                foundArea = foundArea
            )
            onResult(MessageCreationResult.Created(stored))
        }
    }

    /** True when this phone holds the record's COMPLETE full photo (photo-transfer design). */
    fun hasFullPhoto(messageId: String): Boolean = app.photoStore.hasComplete(messageId)

    /** The complete full photo's JPEG bytes for display, or null when not held. */
    fun fullPhotoBytes(messageId: String): ByteArray? = app.photoStore.readComplete(messageId)

    /**
     * MessageIds whose full photo at least one peer has confirmed
     * receiving (PHOTO_ACK COMPLETE → `photo_delivery` row, written by
     * the relay engine). Record cards add "Photo delivered ✓" for these
     * — the sender-side proof that the photo, not just the record,
     * arrived.
     */
    val photoDeliveredIds: StateFlow<Set<String>> =
        app.database.photoDeliveryDao().observeAll()
            .map { rows -> rows.map { it.messageId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun submitAnnouncement(
        category: String,
        text: String,
        validUntilMs: Long,
        onResult: (MessageCreationResult) -> Unit
    ) {
        val current = profile.value ?: return
        viewModelScope.launch {
            val stored = app.messageRepository.postAnnouncement(current.deviceId, category, text, validUntilMs)
            onResult(MessageCreationResult.Created(stored))
        }
    }

    fun submitResourceUpdate(
        resourceType: String,
        quantity: Int,
        note: String?,
        location: PayloadLocation?,
        onResult: (MessageCreationResult) -> Unit
    ) {
        val current = profile.value ?: return
        viewModelScope.launch {
            val stored = app.messageRepository.postResourceUpdate(
                current.deviceId, resourceType, quantity, note, location
            )
            onResult(MessageCreationResult.Created(stored))
        }
    }

    /**
     * Metrics CSV export (Prompt 9, contracts §6): writes ALL metrics rows
     * to a CSV in the app's external files dir and puts the full path in
     * the status line. No share-sheet machinery.
     */
    fun exportMetricsCsv() {
        viewModelScope.launch {
            runCatching {
                val rows = app.metricsRepository.allForExport()
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                val file = File(dir, "rescuemesh-metrics-${System.currentTimeMillis()}.csv")
                file.writeText(metricsToCsv(rows))
                file.absolutePath
            }.onSuccess { path ->
                _statusMessage.value = "Metrics CSV written to $path"
            }.onFailure {
                _statusMessage.value = "Metrics export failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun startMesh() {
        val current = profile.value ?: return
        if (!permissionsReadyOrExplain()) return
        discoveryStartedAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            // NearbyTransport.start() now awaits advertising + discovery and
            // throws on failure, so this runCatching genuinely catches errors.
            // Prompt 4: advertise only the short "RM-" label, never the full
            // originDeviceId — the full id + public key travel in HELLO later.
            runCatching { transport.start(DeviceIds.advertisedLabel(current.deviceId)) }
                .onSuccess {
                    _statusMessage.value = "Mesh started — advertising and discovering"
                }
                .onFailure {
                    discoveryStartedAtMs = 0
                    _statusMessage.value =
                        "Mesh failed to start: ${it.message ?: it.javaClass.simpleName}"
                }
        }
    }

    fun stopMesh() {
        viewModelScope.launch { transport.stop() }
    }

    fun connectPeer(endpointId: String) {
        viewModelScope.launch {
            // Previously fire-and-forget: a failed requestConnection was
            // invisible. Now failures land in the status line the user sees.
            runCatching { transport.connect(endpointId) }
                .onSuccess {
                    _statusMessage.value =
                        "Connection requested — the peer card shows Connected when it is accepted"
                }
                .onFailure {
                    _statusMessage.value =
                        "Connect failed: ${it.message ?: it.javaClass.simpleName}"
                }
        }
    }

    /** Spike send: one plain test payload so two phones can prove they exchange bytes. */
    fun sendTestPayload(endpointId: String) {
        val deviceId = profile.value?.deviceId ?: "unknown"
        val text = "RescueMesh test from ${deviceId.take(8)} at ${System.currentTimeMillis()}"
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = transport.send(endpointId, text.toByteArray(Charsets.UTF_8))
            app.metricsRepository.record(
                event = "TRANSFER",
                messageId = null,
                peerId = endpointId,
                startedAt = started,
                durationMs = result.durationMs,
                success = result.success,
                detail = result.error ?: "bytes=${text.toByteArray(Charsets.UTF_8).size}"
            )
            _lastSendInfo.value = if (result.success) {
                "Sent ${text.toByteArray(Charsets.UTF_8).size} bytes in ${result.durationMs} ms"
            } else {
                "Send failed: ${result.error ?: "unknown error"}"
            }
        }
    }
}
