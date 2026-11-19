package com.rescuemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.rescuemesh.mesh.NearbyPermissions
import com.rescuemesh.mesh.RelayConfig
import com.rescuemesh.mesh.RelayEngine

/**
 * Foreground service that keeps the Prompt 5 [RelayEngine] running over the
 * app's ONE shared mesh transport (RescueMeshApp.meshTransport — this
 * service never constructs a second NearbyTransport).
 *
 * The engine itself is started idempotently; starting the service twice is
 * harmless. The persistent notification is the honest signal that relaying
 * is on: "RescueMesh relay is on".
 *
 * TTL test mode is read from SharedPreferences ([PREFS_NAME] /
 * [KEY_TTL_TEST_MODE]) at engine creation — the mesh debug screen writes
 * that pref, and toggling it while the service runs restarts the service so
 * the new flag takes effect (see AppViewModel).
 */
class RelayService : Service() {

    private var engine: RelayEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive permission gate: never start the engine (and with it
        // Nearby advertising/discovery) without the Nearby runtime
        // permissions — an un-granted start fails with a raw 8038
        // MISSING_PERMISSION error. AppViewModel gates before starting
        // this service and explains to the user; this guard covers every
        // other path (e.g. a sticky restart after permissions were
        // revoked in Settings).
        if (NearbyPermissions.missingPermissions(this).isNotEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        val app = application as RescueMeshApp
        if (engine == null) {
            val ttlTestMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TTL_TEST_MODE, false)
            engine = RelayEngine(
                transport = app.meshTransport,
                messageDao = app.database.messageDao(),
                metricsDao = app.database.metricsDao(),
                hopSigner = app.deviceIdentity.hopSigner(),
                ownDeviceId = app.deviceIdentity.originDeviceId,
                ownPublicKey = app.deviceIdentity.rawPublicKey,
                // Prompt 9: the Room-backed ack store (verified acks gossip
                // in HELLO and mark records SERVER_SYNCED) and the shared
                // internet checker's cached flag as HELLO's gateway flag.
                serverAckStore = app.serverAckStore,
                // Photo-transfer design: the photo phase runs over the
                // app's shared PhotoStore (filesDir/photos/), and a
                // peer's PHOTO_ACK COMPLETE lands in the Room-backed
                // delivery store so cards can show "Photo delivered ✓".
                photoStore = app.photoStore,
                photoDeliveryStore = app.photoDeliveryStore,
                gatewayProvider = { app.internetChecker.online.value },
                config = RelayConfig(ttlTestMode = ttlTestMode),
                // FIX9 Kaam 4: the relay phone's saved last-known location
                // rides on its RELAY hop entries (loc is optional — without
                // a saved fix the entries are exactly as before).
                locationProvider = app.locationStore::savedLocation
            )
        }
        engine?.start()
        // Trigger (contracts §6): relay-service start also attempts a sync.
        app.triggerGatewaySync()
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RescueMesh relay is on")
            .setContentText("Relaying emergency records to nearby phones.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PREFS_NAME = "rescuemesh_relay"
        const val KEY_TTL_TEST_MODE = "ttl_test_mode"
        private const val CHANNEL_ID = "rescuemesh_relay"
        private const val NOTIFICATION_ID = 5

        fun start(context: Context) {
            // minSdk is 26, so Context.startForegroundService (API 26+) is
            // directly available — no androidx.core helper needed.
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, RelayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, RelayService::class.java)
            )
        }
    }
}
