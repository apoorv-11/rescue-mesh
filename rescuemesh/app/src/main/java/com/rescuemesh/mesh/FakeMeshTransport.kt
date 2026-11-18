package com.rescuemesh.mesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory transport for unit tests and Compose previews.
 * Loopback mode: bytes sent to a connected fake peer are delivered straight
 * back through [incoming], as if the peer echoed them.
 *
 * The class is `open` and exposes [deliverIncoming] / [setPeerConnected] so
 * tests can pair two fakes into a two-phone link (see RelayEngineTest's
 * PairedFakeTransport). Default loopback behaviour is unchanged.
 *
 * [incoming] has a replay buffer: a real link delivers payloads via
 * callbacks once connected, but a replay-0 SharedFlow silently drops
 * anything emitted before the collector subscribes — an artifact that made
 * paired-engine tests race at startup (a HELLO sent a millisecond before
 * the peer engine's collector was up vanished, and the session never
 * established). Replay makes the fake behave like the reliable link it
 * models; consumers that collect once (the engine) are unaffected, and
 * duplicate replays are absorbed by the protocol's own dedup.
 */
open class FakeMeshTransport(
    private val fakePeers: List<MeshPeer> = listOf(
        MeshPeer(endpointId = "fake-endpoint-1", deviceName = "fake-device-1", connected = false)
    )
) : MeshTransport {

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingPayload>(replay = 64, extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingPayload> = _incoming.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val sentPayloads = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun start(localDeviceId: String) {
        _peers.value = fakePeers
        _isRunning.value = true
    }

    override suspend fun stop() {
        _peers.value = emptyList()
        _isRunning.value = false
    }

    override suspend fun connect(endpointId: String) {
        _peers.value = _peers.value.map {
            if (it.endpointId == endpointId) it.copy(connected = true) else it
        }
    }

    override suspend fun disconnect(endpointId: String) {
        _peers.value = _peers.value.map {
            if (it.endpointId == endpointId) it.copy(connected = false) else it
        }
    }

    override suspend fun send(endpointId: String, bytes: ByteArray): SendResult {
        val peer = _peers.value.firstOrNull { it.endpointId == endpointId }
        if (peer?.connected != true) {
            return SendResult(endpointId, 0, success = false, error = "PEER_NOT_CONNECTED")
        }
        sentPayloads += endpointId to bytes
        deliverIncoming(endpointId, bytes)
        return SendResult(endpointId, 1, success = true)
    }

    /**
     * Test-pairing hook: delivers bytes as if they arrived from [endpointId].
     * Loopback [send] uses exactly this, so pairing changes nothing locally.
     */
    suspend fun deliverIncoming(endpointId: String, bytes: ByteArray) {
        _incoming.emit(IncomingPayload(endpointId, bytes, System.currentTimeMillis()))
    }

    /** Test-pairing hook: flips one peer's connected flag (a remote connect/disconnect). */
    fun setPeerConnected(endpointId: String, connected: Boolean) {
        _peers.value = _peers.value.map {
            if (it.endpointId == endpointId) it.copy(connected = connected) else it
        }
    }
}
