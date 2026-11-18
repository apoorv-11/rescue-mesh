package com.rescuemesh.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Nearby Connections implementation of [MeshTransport] (Phase 1 spike, Prompt 2).
 *
 * - Strategy P2P_CLUSTER, one fixed service id.
 * - The advertised endpoint name is the short "RM-" label derived from the
 *   originDeviceId (Prompt 4) — never a person's name
 *   or other personal data.
 * - The spike connects on an explicit user tap, so two phones never request a
 *   connection at the same moment. The initiator rule and the HELLO/MSG/ACK
 *   relay protocol are Phase 2 (Prompt 5); this class only moves raw bytes.
 * - Error honesty (gate fix, 14 Nov): start() awaits advertising + discovery
 *   and throws on failure, setting isRunning only after both succeed;
 *   connect() awaits the request and throws on failure. Nothing is swallowed.
 *
 * UNVERIFIED on real hardware from this build environment: Nearby needs
 * physical phones with Google Play Services. Verify with the Phase 1 gate in
 * the README before trusting any behaviour here.
 */
class NearbyTransport(context: Context) : MeshTransport {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingPayload> = _incoming.asSharedFlow()

    /**
     * Lossless hand-off from the GMS payload callback into [incoming].
     *
     * The callback used to `tryEmit` straight into a 16-slot SharedFlow and
     * ignore the result: whenever the collectors (the relay engine doing
     * crypto + Room writes, the debug screen's list on the main thread)
     * were busy for a burst of packets, the buffer filled and packets
     * vanished silently — no metric, no log, and no ACK for the sender,
     * whose record then looked stuck in QUEUED while smaller records
     * squeaked through (TG-4 device test, 14 Nov 2026: exactly the
     * largest packet — a Missing-person MSG carrying a ~12 KB photo
     * thumbnail — was the one that never settled). [FakeMeshTransport]
     * never modelled this: it delivers with a suspending `emit` into a
     * replay-64 flow, i.e. losslessly, which is why the JVM suite was
     * green while the device failed.
     *
     * The channel is UNLIMITED, so the callback's `trySend` cannot fail;
     * one drain collector forwards into the SharedFlow with a suspending
     * `emit`, so backpressure lands in the channel instead of dropping
     * packets, and per-endpoint packet order is preserved (single FIFO).
     */
    private val incomingChannel = Channel<IncomingPayload>(Channel.UNLIMITED)
    private val incomingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        incomingScope.launch {
            for (payload in incomingChannel) {
                _incoming.emit(payload)
            }
        }
    }

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var localDeviceId: String = "unknown"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            // UNLIMITED channel: trySend cannot fail, so a received payload
            // is never dropped here (see the incomingChannel note above).
            incomingChannel.trySend(IncomingPayload(endpointId, bytes, System.currentTimeMillis()))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Send timing is measured from the sendPayload task in send(); nothing needed here in the spike.
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Spike policy: accept connections from any RescueMesh peer.
            client.acceptConnection(endpointId, payloadCallback)
            upsertPeer(endpointId, info.endpointName, connected = false)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val existing = _peers.value.firstOrNull { it.endpointId == endpointId }
                upsertPeer(endpointId, existing?.deviceName ?: endpointId, connected = true)
            } else {
                // A rejected/failed connection must not linger as "connected":
                // drop the peer back to discovered-not-connected so the UI is honest.
                _peers.update { list ->
                    list.map { if (it.endpointId == endpointId) it.copy(connected = false) else it }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            _peers.update { list -> list.map { if (it.endpointId == endpointId) it.copy(connected = false) else it } }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo) {
            upsertPeer(endpointId, info.endpointName, connected = false)
        }

        override fun onEndpointLost(endpointId: String) {
            val current = _peers.value.firstOrNull { it.endpointId == endpointId }
            if (current?.connected != true) {
                _peers.update { list -> list.filterNot { it.endpointId == endpointId } }
            }
        }
    }

    override suspend fun start(localDeviceId: String) {
        this.localDeviceId = localDeviceId
        val strategy = Strategy.P2P_CLUSTER
        var advertisingStarted = false
        try {
            // Both calls are AWAITED. Previously they were fire-and-forget and
            // _isRunning was set to true unconditionally, so a failure (e.g.
            // Bluetooth off, permission denied) never reached the UI.
            client.startAdvertising(
                localDeviceId,
                SERVICE_ID,
                connectionCallback,
                AdvertisingOptions.Builder().setStrategy(strategy).build()
            ).awaitOrThrow()
            advertisingStarted = true
            client.startDiscovery(
                SERVICE_ID,
                discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(strategy).build()
            ).awaitOrThrow()
        } catch (e: CancellationException) {
            rollbackPartialStart(advertisingStarted)
            throw e
        } catch (e: Exception) {
            rollbackPartialStart(advertisingStarted)
            throw e
        }
        // Only reached when advertising AND discovery both actually started.
        _isRunning.value = true
    }

    /** Undoes whichever half of start() succeeded, and marks the mesh stopped. */
    private fun rollbackPartialStart(advertisingStarted: Boolean) {
        if (advertisingStarted) {
            runCatching { client.stopAdvertising() }
        }
        // Safe even if discovery never started.
        runCatching { client.stopDiscovery() }
        _isRunning.value = false
    }

    override suspend fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        _peers.value = emptyList()
        _isRunning.value = false
    }

    override suspend fun connect(endpointId: String) {
        // AWAITED, and a failure now THROWS to the caller instead of being
        // swallowed: requestConnection succeeding only means the request was
        // accepted — the peer becomes "connected" later, via onConnectionResult.
        client.requestConnection(localDeviceId, endpointId, connectionCallback).awaitOrThrow()
    }

    override suspend fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        _peers.update { list -> list.map { if (it.endpointId == endpointId) it.copy(connected = false) else it } }
    }

    override suspend fun send(endpointId: String, bytes: ByteArray): SendResult {
        val started = System.currentTimeMillis()
        // NOTE on timing: this measures until the sendPayload task completes
        // (payload handed to Nearby), not until the other phone displays it.
        // T1 must be timed on the receiving phone — see the gate checklist.
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(bytes)).awaitOrThrow()
            SendResult(endpointId, System.currentTimeMillis() - started, success = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult(
                endpointId,
                System.currentTimeMillis() - started,
                success = false,
                error = e.message
            )
        }
    }

    private fun upsertPeer(endpointId: String, deviceName: String, connected: Boolean) {
        _peers.update { list ->
            val existing = list.firstOrNull { it.endpointId == endpointId }
            val peer = MeshPeer(
                endpointId = endpointId,
                deviceName = deviceName,
                connected = connected || (existing?.connected ?: false)
            )
            if (existing == null) list + peer else list.map { if (it.endpointId == endpointId) peer else it }
        }
    }

    companion object {
        /** Fixed Nearby service id for RescueMesh. Must be identical on every phone. */
        const val SERVICE_ID = "com.rescuemesh.MESH"
    }
}
