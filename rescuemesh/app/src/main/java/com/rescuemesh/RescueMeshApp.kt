package com.rescuemesh

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.rescuemesh.crypto.DeviceIdentity
import com.rescuemesh.data.local.RescueMeshDatabase
import com.rescuemesh.data.repository.MessageRepository
import com.rescuemesh.data.repository.MetricsRepository
import com.rescuemesh.data.repository.PeerRepository
import com.rescuemesh.data.repository.ProfileRepository
import com.rescuemesh.data.repository.RoomPhotoDeliveryStore
import com.rescuemesh.data.repository.RoomServerAckStore
import com.rescuemesh.domain.PhotoStore
import com.rescuemesh.mesh.MeshTransport
import com.rescuemesh.mesh.NearbyTransport
import com.rescuemesh.mesh.PhotoDeliveryStore
import com.rescuemesh.mesh.ServerAckStore
import com.rescuemesh.net.ApiClient
import com.rescuemesh.net.ApiClientSyncApi
import com.rescuemesh.net.ApiResult
import com.rescuemesh.net.GatewaySync
import com.rescuemesh.net.InternetChecker
import com.rescuemesh.net.PhotoSync
import com.rescuemesh.net.ServerConfig
import com.rescuemesh.net.SyncKeyStore
import com.rescuemesh.net.SyncWork
import com.rescuemesh.net.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring — no DI framework yet.
 * The database is file-backed, so all records survive an app restart.
 */
class RescueMeshApp : Application() {

    lateinit var database: RescueMeshDatabase
        private set

    /** This phone's Ed25519 identity (Prompt 4); created/loaded at startup. */
    lateinit var deviceIdentity: DeviceIdentity
        private set

    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var messageRepository: MessageRepository
        private set
    lateinit var peerRepository: PeerRepository
        private set
    lateinit var metricsRepository: MetricsRepository
        private set

    lateinit var meshTransport: MeshTransport
        private set

    /**
     * Full-photo store (photo-transfer design): files under
     * filesDir/photos/. Creation writes complete photos here, the relay
     * engine reads/writes them during the photo phase, and the record
     * lists read them back for display.
     */
    lateinit var photoStore: PhotoStore
        private set

    /**
     * Optional account layer (server testing) — config, encrypted token
     * store and API client. Nothing in the offline/mesh path uses these;
     * they exist only for the Account screen.
     */
    lateinit var serverConfig: ServerConfig
        private set
    lateinit var tokenStore: TokenStore
        private set
    lateinit var apiClient: ApiClient
        private set

    /**
     * Gateway sync (Prompt 9): the Room-backed server-ack store (fulfilling
     * the relay engine's Prompt 5 seam), the shared internet check, the
     * cached server sync key, and the sync runner itself. All inert until a
     * server URL is configured.
     */
    lateinit var syncKeyStore: SyncKeyStore
        private set
    lateinit var serverAckStore: ServerAckStore
        private set

    /**
     * Photo delivery receipts (photo-transfer design): the Room-backed
     * store the relay engine writes a peer's PHOTO_ACK COMPLETE into, so
     * record cards can show "Photo delivered ✓".
     */
    lateinit var photoDeliveryStore: PhotoDeliveryStore
        private set
    lateinit var internetChecker: InternetChecker
        private set
    lateinit var gatewaySync: GatewaySync
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = RescueMeshDatabase.create(this)
        deviceIdentity = DeviceIdentity.loadOrCreate(this)
        profileRepository = ProfileRepository(database.profileDao()) { deviceIdentity.originDeviceId }
        photoStore = PhotoStore(filesDir)
        messageRepository = MessageRepository(
            database.messageDao(),
            deviceIdentity.envelopeSigner(),
            deviceIdentity.hopSigner(),
            photoStore = photoStore
        )
        peerRepository = PeerRepository(database.peerDao())
        metricsRepository = MetricsRepository(database.metricsDao())
        meshTransport = NearbyTransport(this)
        serverConfig = ServerConfig(this)
        tokenStore = TokenStore(this)
        apiClient = ApiClient(serverConfig, tokenStore)
        // Phase 1 -> Prompt 4 upgrade: replace a stored random-UUID deviceId
        // with the key-derived originDeviceId (test data only, see repository).
        appScope.launch { profileRepository.migrateDeviceIdIfNeeded() }

        // Prompt 9 wiring. The internet checker's probe is the ApiClient's
        // /health call; GatewaySync reads the same checker's cached flag
        // for HELLO via RelayService's gateway supplier.
        syncKeyStore = SyncKeyStore(this)
        serverAckStore = RoomServerAckStore(database.serverAckDao(), syncKeyStore)
        photoDeliveryStore = RoomPhotoDeliveryStore(database.photoDeliveryDao())
        internetChecker = InternetChecker(
            baseUrlProvider = { serverConfig.baseUrl() },
            healthProbe = { apiClient.health() is ApiResult.Success }
        )
        val syncApi = ApiClientSyncApi(apiClient)
        // FIX5 photo phase: runs inside GatewaySync AFTER the record
        // batches (record first, photo behind — see PhotoSync).
        val photoSync = PhotoSync(
            api = syncApi,
            messageDao = database.messageDao(),
            uploadDao = database.photoUploadDao(),
            photoBytes = { messageId -> photoStore.readComplete(messageId) }
        )
        gatewaySync = GatewaySync(
            api = syncApi,
            baseUrlProvider = { serverConfig.baseUrl() },
            tokenProvider = { tokenStore.token() },
            messageDao = database.messageDao(),
            metricsDao = database.metricsDao(),
            ackStore = serverAckStore,
            keyCache = syncKeyStore,
            hopSigner = deviceIdentity.hopSigner(),
            ownDeviceId = deviceIdentity.originDeviceId,
            ownPublicKey = deviceIdentity.rawPublicKey,
            internetChecker = internetChecker,
            networkTypeProvider = { currentNetworkType() },
            photoSync = photoSync
        )
        registerGatewayNetworkCallback()
        // FIX5 periodic sync (app closed): WorkManager re-runs the
        // gateway sync every 15 minutes whenever any network is
        // available — queued records sync without opening the app.
        // Scheduling failure must never break startup.
        runCatching { SyncWork.schedule(this) }
        // Trigger: app start (a no-op until a server URL is configured).
        triggerGatewaySync()
    }

    /** Fires a gateway-sync attempt on the app scope; concurrent runs collapse inside GatewaySync. */
    fun triggerGatewaySync() {
        appScope.launch { runCatching { gatewaySync.syncNow() } }
    }

    /**
     * Trigger (contracts §6): a network becoming available refreshes the
     * shared internet check and attempts a sync; losing the network marks
     * the cached flag offline. Registration failure (older/odd devices)
     * must never break startup — sync still runs on its other triggers.
     */
    private fun registerGatewayNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    appScope.launch {
                        internetChecker.refresh()
                        runCatching { gatewaySync.syncNow() }
                    }
                }

                override fun onLost(network: Network) {
                    internetChecker.markOffline()
                }
            })
        }
    }

    /** "WIFI" / "CELLULAR" of the active network for GATEWAY hop entries; null when neither/unknown. */
    private fun currentNetworkType(): String? {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> null
        }
    }
}
