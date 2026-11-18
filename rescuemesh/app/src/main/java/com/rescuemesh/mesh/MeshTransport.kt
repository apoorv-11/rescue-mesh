package com.rescuemesh.mesh

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A peer this phone can currently see or is connected to. */
data class MeshPeer(
    val endpointId: String,
    val deviceName: String,
    val connected: Boolean
)

/** Bytes received from a peer. Phase 1 spike carries a plain test payload. */
data class IncomingPayload(
    val endpointId: String,
    val bytes: ByteArray,
    val receivedAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingPayload) return false
        return endpointId == other.endpointId &&
            receivedAtMs == other.receivedAtMs &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = endpointId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}

/** Result of one send: how long the transfer took, for the Metrics table. */
data class SendResult(
    val endpointId: String,
    val durationMs: Long,
    val success: Boolean,
    val error: String? = null
)

/**
 * The one boundary between RescueMesh and any radio technology.
 * The UI and (later) the relay engine talk only to this interface;
 * Nearby Connections lives in [NearbyTransport] behind it.
 * The Phase 1 fallback in the plan (raw BLE GATT) would be a second
 * implementation of this same interface, with nothing above it changing.
 */
interface MeshTransport {
    /** Peers currently discovered, with their connection state. */
    val peers: StateFlow<List<MeshPeer>>

    /** Payloads received from connected peers. */
    val incoming: SharedFlow<IncomingPayload>

    /** True while advertising and/or discovering. */
    val isRunning: StateFlow<Boolean>

    /**
     * Starts advertising this device and discovering peers.
     * [localDeviceId] is advertised as the endpoint name — callers pass the
     * short "RM-" label derived from the device id (Prompt 4), not personal data,
     * never a person's name or other personal data.
     *
     * Contract: implementations must throw if advertising or discovery fails
     * to start, and [isRunning] may become true only after the transport has
     * actually started. Callers rely on this to show start failures.
     */
    suspend fun start(localDeviceId: String)

    /** Stops advertising, discovery and all connections. */
    suspend fun stop()

    /**
     * Requests a connection to a discovered peer (user tap in the spike UI).
     * Throws if the request itself fails; a successful return means the
     * request was accepted, and the peer turns "connected" asynchronously.
     */
    suspend fun connect(endpointId: String)

    suspend fun disconnect(endpointId: String)

    /** Sends one bytes payload to a connected peer and reports the time it took. */
    suspend fun send(endpointId: String, bytes: ByteArray): SendResult
}
