package com.rescuemesh.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Retrofit service for the account endpoints. Paths are relative to the configured base URL. */
interface RescueMeshService {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<RegisterResponse>

    @POST("auth/verify-email")
    suspend fun verifyEmail(@Body body: VerifyEmailRequest): Response<TokenResponse>

    @POST("auth/resend-verification")
    suspend fun resendVerification(@Body body: ResendVerificationRequest): Response<StatusResponse>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @GET("me")
    suspend fun me(@Header("Authorization") authorization: String): Response<MeResponse>

    @POST("roles/apply")
    suspend fun applyRole(
        @Header("Authorization") authorization: String,
        @Body body: ApplyRoleRequest
    ): Response<RoleApplicationView>

    @GET("roles/my-applications")
    suspend fun myApplications(
        @Header("Authorization") authorization: String
    ): Response<List<RoleApplicationView>>

    @POST("profile/role-code")
    suspend fun redeemRoleCode(
        @Header("Authorization") authorization: String,
        @Body body: RedeemCodeRequest
    ): Response<TokenResponse>

    @POST("profile/bind-device")
    suspend fun bindDevice(
        @Header("Authorization") authorization: String,
        @Body body: BindDeviceRequest
    ): Response<BindDeviceResponse>

    /** Public liveness probe — the shared internet check (contracts §6). */
    @GET("health")
    suspend fun health(): Response<HealthResponse>

    /** Public: the server's ack-signing public key (contracts §5). */
    @GET("sync/pubkey")
    suspend fun syncPubkey(): Response<SyncPubkeyResponse>

    /** Device-token fallback for gateway sync: deviceId → JWT (no user account needed). */
    @POST("auth/device")
    suspend fun deviceToken(@Body body: DeviceTokenRequest): Response<TokenResponse>

    @POST("sync/batch")
    suspend fun syncBatch(
        @Header("Authorization") authorization: String,
        @Body body: SyncBatchRequest
    ): Response<SyncBatchResponse>
}

/**
 * Typed client over [RescueMeshService]. Every call is a suspend function
 * on [Dispatchers.IO] and returns an [ApiResult] — HTTP errors carry the
 * server's own `{"error": "..."}` message when it sent one, and a failed
 * connection is [ApiResult.Unreachable], never a thrown exception.
 *
 * The bearer token is read from [TokenStore] fresh on every authenticated
 * call (so a redeem/login that replaces it takes effect immediately).
 *
 * Deliberately NO HTTP logging interceptor at any level: bearer tokens and
 * passwords must never reach logcat.
 */
class ApiClient(
    private val serverConfig: ServerConfig,
    private val tokenStore: TokenStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Read timeout is generous on purpose: the free Render tier sleeps after
    // ~15 idle minutes and the first request can take about a minute to wake
    // it. On a LAN Docker server the same timeouts are simply never hit.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun register(
        username: String,
        email: String,
        password: String,
        deviceId: String
    ): ApiResult<RegisterResponse> =
        call { it.register(RegisterRequest(username, email, password, deviceId)) }

    suspend fun verifyEmail(username: String, code: String): ApiResult<TokenResponse> =
        call { it.verifyEmail(VerifyEmailRequest(username, code)) }

    suspend fun resendVerification(username: String): ApiResult<StatusResponse> =
        call { it.resendVerification(ResendVerificationRequest(username)) }

    suspend fun login(username: String, password: String): ApiResult<TokenResponse> =
        call { it.login(LoginRequest(username, password)) }

    suspend fun me(): ApiResult<MeResponse> =
        authedCall { service, auth -> service.me(auth) }

    suspend fun applyRole(
        requestedRole: String,
        organization: String?,
        reason: String?
    ): ApiResult<RoleApplicationView> =
        authedCall { service, auth ->
            service.applyRole(auth, ApplyRoleRequest(requestedRole, organization, reason))
        }

    suspend fun myApplications(): ApiResult<List<RoleApplicationView>> =
        authedCall { service, auth -> service.myApplications(auth) }

    suspend fun redeemRoleCode(code: String): ApiResult<TokenResponse> =
        authedCall { service, auth -> service.redeemRoleCode(auth, RedeemCodeRequest(code)) }

    suspend fun bindDevice(deviceId: String): ApiResult<BindDeviceResponse> =
        authedCall { service, auth -> service.bindDevice(auth, BindDeviceRequest(deviceId)) }

    // ------------------------------------------------ gateway sync (P9)
    // These take/return exactly what GatewaySync's SyncApi seam needs; the
    // batch call takes an EXPLICIT authorization because the token may be a
    // per-run device token rather than the stored user token.

    suspend fun health(): ApiResult<HealthResponse> =
        call { it.health() }

    suspend fun syncPubkey(): ApiResult<SyncPubkeyResponse> =
        call { it.syncPubkey() }

    suspend fun deviceToken(deviceId: String): ApiResult<TokenResponse> =
        call { it.deviceToken(DeviceTokenRequest(deviceId)) }

    suspend fun syncBatch(authorization: String, request: SyncBatchRequest): ApiResult<SyncBatchResponse> =
        call { it.syncBatch(authorization, request) }

    private fun serviceOrNull(): RescueMeshService? {
        val baseUrl = serverConfig.baseUrl() ?: return null
        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(RescueMeshService::class.java)
    }

    private fun bearerAuthorization(): String? = tokenStore.token()?.let { "Bearer $it" }

    private suspend fun <T> authedCall(
        block: suspend (RescueMeshService, String) -> Response<T>
    ): ApiResult<T> {
        val authorization = bearerAuthorization()
            ?: return ApiResult.HttpError(401, "Not logged in.")
        return call { service -> block(service, authorization) }
    }

    private suspend fun <T> call(block: suspend (RescueMeshService) -> Response<T>): ApiResult<T> {
        val service = serviceOrNull()
            ?: return ApiResult.Unreachable(
                "No server URL saved — save one on the Account screen first."
            )
        return withContext(Dispatchers.IO) {
            try {
                val response = block(service)
                if (response.isSuccessful) {
                    response.body()?.let { ApiResult.Success(it) }
                        ?: ApiResult.HttpError(
                            response.code(), "HTTP ${response.code()} (empty response)"
                        )
                } else {
                    val message = parseServerError(response.errorBody()?.string())
                        ?: "HTTP ${response.code()}"
                    ApiResult.HttpError(response.code(), message)
                }
            } catch (e: IOException) {
                ApiResult.Unreachable(
                    "Server unreachable — check the URL, that this phone is on the " +
                        "same network (LAN server), and that the server is running."
                )
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
