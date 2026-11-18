package com.rescuemesh.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rescuemesh.RescueMeshApp
import com.rescuemesh.net.ApiResult
import com.rescuemesh.net.MeResponse
import com.rescuemesh.net.RoleApplicationView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State and flows for the OPTIONAL server account layer (server testing).
 *
 * This ViewModel is strictly additive: nothing in the SOS / I Am Safe /
 * onboarding / mesh path consults it. No server URL, no token, or a dead
 * server only ever changes what the Account screen reports — the rest of
 * the app keeps working offline exactly as before.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RescueMeshApp
    private val api = app.apiClient
    private val serverConfig = app.serverConfig
    private val tokenStore = app.tokenStore
    private val profileRepository = app.profileRepository
    private val originDeviceId = app.deviceIdentity.originDeviceId

    private val _serverUrl = MutableStateFlow(serverConfig.baseUrl() ?: "")
    /** The saved server base URL ("" when not configured yet). */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _session = MutableStateFlow<MeResponse?>(null)
    /** The signed-in account as reported by GET /me, or null when signed out. */
    val session: StateFlow<MeResponse?> = _session.asStateFlow()

    private val _pendingVerificationUsername = MutableStateFlow<String?>(null)
    /** Username awaiting its email code (after signup or an unverified login). */
    val pendingVerificationUsername: StateFlow<String?> = _pendingVerificationUsername.asStateFlow()

    private val _myApplications = MutableStateFlow<List<RoleApplicationView>>(emptyList())
    val myApplications: StateFlow<List<RoleApplicationView>> = _myApplications.asStateFlow()

    /**
     * Gateway sync (Prompt 9): the sync runner's own status line, exposed
     * directly — the Account screen shows it next to the Sync now button.
     * Sync works with or without an account (device-token fallback), so it
     * is NOT tied to the session state.
     */
    val syncStatus: StateFlow<String> = app.gatewaySync.status

    /** Manual sync trigger (one of the four in contracts §6). */
    fun syncNow() {
        viewModelScope.launch { app.gatewaySync.syncNow() }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Auto bind-device inside refreshMe runs at most once per process. */
    private var bindAttempted = false

    init {
        // A token from a previous run means "probably signed in": quietly
        // refresh the session. Any failure (offline, server asleep, expired
        // token) just leaves the session empty — it blocks nothing.
        viewModelScope.launch {
            if (tokenStore.token() != null) refreshMeInternal()
        }
    }

    fun saveServerUrl(url: String) {
        val saved = serverConfig.saveBaseUrl(url)
        if (saved != null) {
            _serverUrl.value = saved
            _errorMessage.value = null
            _statusMessage.value = "Server URL saved: $saved"
        } else {
            _errorMessage.value =
                "That URL doesn't look right — it must start with http:// or https:// and name a host."
        }
    }

    fun signup(username: String, email: String, password: String) {
        runBusy {
            // The deviceId is ALWAYS this phone's originDeviceId (Prompt 4
            // identity) — the user never types it — and no role is sent:
            // the server creates every self-registered account as CITIZEN.
            when (val result = api.register(username.trim(), email.trim(), password, originDeviceId)) {
                is ApiResult.Success -> {
                    _pendingVerificationUsername.value = result.value.username
                    _statusMessage.value =
                        "Account created. A 6-digit verification code was emailed to " +
                            "${email.trim()} — enter it below. " +
                            "(Dev server without email set up? The code is in the server log.)"
                }
                is ApiResult.HttpError -> _errorMessage.value = result.message
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    fun verifyEmail(code: String) {
        val username = _pendingVerificationUsername.value
        if (username == null) {
            _errorMessage.value = "Sign up or try logging in first — then enter the code here."
            return
        }
        runBusy {
            when (val result = api.verifyEmail(username, code.trim())) {
                is ApiResult.Success -> {
                    // Verify returns a login token (server auto-login).
                    tokenStore.save(result.value.token)
                    _pendingVerificationUsername.value = null
                    _statusMessage.value = "Email verified — you are signed in."
                    refreshMeInternal()
                }
                is ApiResult.HttpError -> _errorMessage.value = result.message
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    fun resendVerification() {
        val username = _pendingVerificationUsername.value
        if (username == null) {
            _errorMessage.value = "Nothing to resend yet — sign up or log in first."
            return
        }
        runBusy {
            when (val result = api.resendVerification(username)) {
                is ApiResult.Success -> _statusMessage.value =
                    "A fresh code was sent — the old one no longer works. " +
                        "(Dev server? The code is in the server log.)"
                is ApiResult.HttpError -> _errorMessage.value = result.message
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    fun login(username: String, password: String) {
        runBusy {
            when (val result = api.login(username.trim(), password)) {
                is ApiResult.Success -> {
                    tokenStore.save(result.value.token)
                    _pendingVerificationUsername.value = null
                    _statusMessage.value = "Signed in."
                    refreshMeInternal()
                }
                is ApiResult.HttpError -> {
                    if (result.code == 403 &&
                        result.message.contains("not verified", ignoreCase = true)
                    ) {
                        // Server: password was right, email unproven. Move
                        // the user straight to the code-entry step.
                        _pendingVerificationUsername.value = username.trim()
                        _errorMessage.value = null
                        _statusMessage.value =
                            "This account's email isn't verified yet. Enter the 6-digit " +
                                "code from the email below (or tap Resend code), then Verify."
                    } else {
                        _errorMessage.value = result.message
                    }
                }
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    /** There is no server logout endpoint (the JWT is stateless): the local token is simply discarded. The account and this phone's own records are untouched. */
    fun logout() {
        tokenStore.clear()
        _session.value = null
        _myApplications.value = emptyList()
        bindAttempted = false
        _errorMessage.value = null
        _statusMessage.value = "Signed out on this phone."
    }

    fun refreshMe() {
        runBusy { refreshMeInternal() }
    }

    private suspend fun refreshMeInternal() {
        var me = when (val result = api.me()) {
            is ApiResult.Success -> result.value
            is ApiResult.HttpError -> {
                // An expired/rejected token is dropped so the screen falls
                // back to the login form instead of erroring forever.
                if (result.code == 401) tokenStore.clear()
                _errorMessage.value = result.message
                return
            }
            is ApiResult.Unreachable -> {
                _errorMessage.value = result.detail
                return
            }
        }
        if (me.deviceId != originDeviceId && !bindAttempted) {
            bindAttempted = true
            // The account exists but this phone isn't bound to it yet (the
            // account may have been created via Postman or another phone).
            // Bind once; failing that is a note, never a blocker — the
            // account is only the official record, SOS never needs it.
            when (api.bindDevice(originDeviceId)) {
                is ApiResult.Success -> {
                    _statusMessage.value = "This phone is now bound to ${me.username}'s account."
                    // Re-read /me so the session line shows the bound deviceId.
                    (api.me() as? ApiResult.Success)?.let { me = it.value }
                }
                is ApiResult.HttpError -> _statusMessage.value =
                    "Signed in. Binding this phone failed (it may already belong to " +
                        "another account) — the mesh and SOS do not need the account."
                is ApiResult.Unreachable -> _statusMessage.value =
                    "Signed in. This phone will be bound when the server is reachable."
            }
        }
        applyServerSession(me)
        loadMyApplicationsInternal()
    }

    /**
     * The single funnel for every successful GET /me (login, verify-email,
     * redeem, manual refresh and the startup auto-refresh all end in
     * [refreshMeInternal], which ends here): updates the session state AND
     * mirrors the server-confirmed role into the local Room profile, so the
     * Home screen shows the same role as this screen.
     *
     * Only a positive server answer ever reaches this function — logout,
     * network failures and HTTP errors return earlier and therefore can
     * never change the local role. An unknown role string from the server
     * is ignored the same way (see [roleFromServerName]).
     */
    private suspend fun applyServerSession(me: MeResponse) {
        _session.value = me
        roleFromServerName(me.role)?.let { profileRepository.mirrorServerRole(it) }
    }

    /** [requestedRole] is VOLUNTEER or COORDINATOR (the only roles the UI offers). */
    fun applyRole(requestedRole: String, organization: String, reason: String) {
        runBusy {
            when (val result = api.applyRole(
                requestedRole,
                organization.trim().ifBlank { null },
                reason.trim().ifBlank { null }
            )) {
                is ApiResult.Success -> {
                    _statusMessage.value =
                        "Application sent for $requestedRole. An admin reviews it; on " +
                            "approval you receive a one-time access code by email."
                    loadMyApplicationsInternal()
                }
                is ApiResult.HttpError -> _errorMessage.value = result.message
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    fun loadMyApplications() {
        runBusy { loadMyApplicationsInternal() }
    }

    private suspend fun loadMyApplicationsInternal() {
        when (val result = api.myApplications()) {
            is ApiResult.Success -> _myApplications.value = result.value
            is ApiResult.HttpError -> _errorMessage.value = result.message
            is ApiResult.Unreachable -> _errorMessage.value = result.detail
        }
    }

    fun redeemCode(code: String) {
        runBusy {
            when (val result = api.redeemRoleCode(code.trim())) {
                is ApiResult.Success -> {
                    // Redeeming returns a FRESH token whose role claim is the
                    // new role — it replaces the stored one. The refresh then
                    // re-reads /me with it and mirrors the role into the
                    // local profile (applyServerSession), so Home agrees.
                    tokenStore.save(result.value.token)
                    refreshMeInternal()
                    val activatedRole = _session.value?.role
                    _statusMessage.value = if (activatedRole != null) {
                        "Role activated: $activatedRole — updated on the Home screen too."
                    } else {
                        // The code was accepted but /me could not be re-read
                        // just now; the mirror runs on the next refresh.
                        "Access code accepted — your role was updated."
                    }
                }
                is ApiResult.HttpError -> _errorMessage.value = result.message
                is ApiResult.Unreachable -> _errorMessage.value = result.detail
            }
        }
    }

    /** Runs [block] with the busy flag set and any previous error cleared; taps while busy are ignored. */
    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
