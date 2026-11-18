package com.rescuemesh.net

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ONE shared internet check (contracts §6), used by HELLO's gateway
 * flag (via RelayService's gateway supplier) and by GatewaySync: the
 * server base URL is configured AND `GET {baseUrl}/health` answers 2xx
 * within the API client's timeouts. No third-party pings.
 *
 * [online] is a cached flag: the relay engine's gateway supplier is
 * synchronous and reads it directly, so it reflects the LAST [refresh]
 * (startup, network callbacks and each sync run refresh it).
 *
 * The two seams ([baseUrlProvider], [healthProbe]) keep the class
 * JVM-testable and free of Android types; production wires them to
 * [ServerConfig] and [ApiClient.health] in RescueMeshApp.
 */
class InternetChecker(
    private val baseUrlProvider: () -> String?,
    private val healthProbe: suspend () -> Boolean
) {
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Re-probes now and updates [online]; returns the fresh value. */
    suspend fun refresh(): Boolean {
        val ok = baseUrlProvider() != null &&
            runCatching { healthProbe() }.getOrDefault(false)
        _online.value = ok
        return ok
    }

    /** Marks the phone offline without probing (network-lost callback). */
    fun markOffline() {
        _online.value = false
    }
}

/**
 * Read/write access to the cached server sync key (contracts §6): the
 * (keyId, publicKey) pair from GET /sync/pubkey. An interface so
 * GatewaySync and the ack store are JVM-testable with an in-memory fake;
 * [SyncKeyStore] is the SharedPreferences implementation.
 */
interface ServerKeyCache {
    /** The cached (keyId, publicKeyBase64Url) pair, or null when never fetched. */
    fun cached(): Pair<String, String>?

    fun save(keyId: String, publicKeyBase64Url: String)
}

/**
 * The server sync-key cache in a plain app-private SharedPreferences file
 * (configuration, not app data — same reasoning as [ServerConfig]). If a
 * later /sync/pubkey fetch returns a different keyId, the cache is
 * replaced and older acks become unverifiable (their signatures simply
 * stop verifying under the new key).
 */
class SyncKeyStore(context: Context) : ServerKeyCache {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    override fun cached(): Pair<String, String>? {
        val keyId = prefs.getString(KEY_ID, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return keyId to publicKey
    }

    override fun save(keyId: String, publicKeyBase64Url: String) {
        prefs.edit()
            .putString(KEY_ID, keyId)
            .putString(KEY_PUBLIC_KEY, publicKeyBase64Url)
            .apply()
    }

    companion object {
        const val PREF_FILE_NAME = "rescuemesh_sync_keys"
        private const val KEY_ID = "keyId"
        private const val KEY_PUBLIC_KEY = "publicKey"
    }
}
