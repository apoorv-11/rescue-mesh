package com.rescuemesh.net

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the server's account API. Field names match the server's
 * Java records EXACTLY (rescuemesh-server: AuthController, RolesController,
 * ProfileController, MeController, RoleApplicationView) — a rename here
 * breaks the contract silently, so the JSON round-trip tests pin the names.
 *
 * Two deliberate choices:
 * - [RegisterRequest] has NO role field: the server forces CITIZEN for every
 *   self-registration (auth v2) and ignores any requested role; not sending
 *   one makes that impossible to get wrong on this side.
 * - Signup's deviceId is always this phone's originDeviceId, filled in from
 *   DeviceIdentity by the caller — it is never a user-typed field.
 */

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val deviceId: String
)

@Serializable
data class RegisterResponse(
    val username: String,
    val role: String,
    val deviceId: String? = null
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class VerifyEmailRequest(
    val username: String,
    val code: String
)

@Serializable
data class ResendVerificationRequest(
    val username: String
)

/** Body of POST /auth/resend-verification: `{"status": "verification code sent"}`. */
@Serializable
data class StatusResponse(
    val status: String? = null
)

@Serializable
data class TokenResponse(
    val token: String,
    val tokenType: String = "Bearer"
)

/** GET /me — the current token's claims; deviceId is null until a device is bound. */
@Serializable
data class MeResponse(
    val username: String,
    val role: String? = null,
    val deviceId: String? = null
)

/** POST /roles/apply — requestedRole is VOLUNTEER or COORDINATOR from this client. */
@Serializable
data class ApplyRoleRequest(
    val requestedRole: String,
    val organization: String? = null,
    val reason: String? = null
)

/** One row of GET /roles/my-applications (server RoleApplicationView). */
@Serializable
data class RoleApplicationView(
    val id: Long,
    val username: String,
    val email: String? = null,
    val requestedRole: String,
    val organization: String? = null,
    val reason: String? = null,
    val status: String,
    val reviewNote: String? = null,
    val reviewedBy: String? = null,
    val createdAt: String? = null,
    val reviewedAt: String? = null
)

@Serializable
data class RedeemCodeRequest(
    val code: String
)

@Serializable
data class BindDeviceRequest(
    val deviceId: String
)

/** POST /profile/bind-device returns `{"username": ..., "deviceId": ...}`. */
@Serializable
data class BindDeviceResponse(
    val username: String,
    val deviceId: String
)

/** The server's uniform error body: `{"error": "..."}`. */
@Serializable
data class ErrorBody(
    val error: String? = null
)

// ------------------------------------------------------- gateway sync (P9)

/** GET /health — `{"status": "UP", "service": "rescuemesh-server"}` (public). */
@Serializable
data class HealthResponse(
    val status: String? = null
)

/** POST /auth/device body: the deviceId alone is the (skeleton) credential. */
@Serializable
data class DeviceTokenRequest(
    val deviceId: String
)

/** GET /sync/pubkey (public): the server's ack-signing key + its id. */
@Serializable
data class SyncPubkeyResponse(
    val keyId: String,
    val publicKey: String
)

/**
 * The §4 server ack object as it travels inside /sync/batch results:
 * `{"messageId","status","issuedAt","signature"}` — signature by the server
 * sync key over the canonical {issuedAt, messageId, status}.
 */
@Serializable
data class ServerAckDto(
    val messageId: String,
    /** "ACCEPTED" or "ALREADY_EXISTS" — echoes the item result status. */
    val status: String,
    val issuedAt: Long,
    val signature: String
)

/**
 * One envelope inside a /sync/batch request: the 11 contract fields, with
 * hopTrace/payload as parsed JSON VALUES (never re-serialised text), built
 * from the stored MessageEntity by parsing its stored JSON text.
 */
@Serializable
data class SyncEnvelopeDto(
    val messageId: String,
    val eventType: String,
    val originDeviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: Int,
    val hopLimit: Int,
    val hopCount: Int,
    val hopTrace: kotlinx.serialization.json.JsonElement,
    val payload: kotlinx.serialization.json.JsonElement,
    val signature: String
)

/** One upload item: the envelope plus its origin's raw public key (Base64url-no-pad). */
@Serializable
data class SyncBatchItem(
    val envelope: SyncEnvelopeDto,
    val originPublicKey: String
)

/** POST /sync/batch body (contracts §5): uploader deviceId + items (batches of 50). */
@Serializable
data class SyncBatchRequest(
    val deviceId: String,
    val items: List<SyncBatchItem>
)

/** One per-item result of POST /sync/batch; reason only for REJECTED, serverAck only for ACCEPTED / ALREADY_EXISTS. */
@Serializable
data class SyncItemResult(
    val messageId: String,
    /** "ACCEPTED" | "ALREADY_EXISTS" | "REJECTED". */
    val status: String,
    val reason: String? = null,
    val serverAck: ServerAckDto? = null
)

/** POST /sync/batch response body. */
@Serializable
data class SyncBatchResponse(
    val serverTime: Long,
    val results: List<SyncItemResult>
)
