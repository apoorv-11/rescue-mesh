package com.rescuemesh

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rescuemesh.mesh.NearbyPermissions
import com.rescuemesh.net.LocationFetcher
import com.rescuemesh.ui.AccountViewModel
import com.rescuemesh.ui.AppViewModel
import com.rescuemesh.ui.PrepareViewModel
import com.rescuemesh.ui.RescueMeshRoot
import com.rescuemesh.ui.RescueMeshTheme

class MainActivity : ComponentActivity() {

    // FIX11: the AppViewModel, kept as a field so onResume can refresh
    // the saved location when the user returns from Settings.
    private lateinit var appViewModel: AppViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Result is visible when the user starts the mesh; Nearby reports failures there. */ }

    override fun onResume() {
        super.onResume()
        // FIX11: returning from Settings with location newly granted —
        // Home's one-shot prompt won't re-fire, so clear the blocked
        // flag and fetch when nothing is saved yet. Guarded by the
        // saved fix, so it never runs again once one exists.
        if (::appViewModel.isInitialized && LocationFetcher.hasPermission(this)) {
            appViewModel.setLocationBlocked(false)
            appViewModel.refreshSavedLocationIfMissing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FORENSIC FIX ROUND (15 Nov): the previous build relied on
        // targetSdk-36 enforcement alone for edge-to-edge and on ONE
        // one-shot controller flag (below) for dark status-bar icons —
        // and on the user's phone neither took visible effect. The
        // window setup is now explicit and layered, so no single
        // mechanism has to carry it:
        //
        // 1) enableEdgeToEdge() — the canonical switch: it sets
        //    decorFitsSystemWindows = false itself instead of relying
        //    on targetSdk enforcement, makes both system bars
        //    transparent, and installs an insets listener that
        //    REAPPLIES the given SystemBarStyles on every insets
        //    dispatch (a one-shot flag never reapplies). Both styles
        //    are LIGHT with transparent scrims because the app
        //    background is always light (#FAF7F4 / white): the bars
        //    must draw DARK clock/icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        // 2) The theme (res/values/themes.xml, Theme.RescueMesh)
        //    carries android:windowLightStatusBar /
        //    windowLightNavigationBar = true, applied by the
        //    framework before the first frame.
        // 3) This explicit controller flag stays as the immediate
        //    belt: dark status-bar icons, unconditional, because no
        //    screen in the app has a dark top area. (It only changes
        //    the icons' colour; the Compose roots consume the insets
        //    themselves — see RescueMeshRoot in Screens.kt.)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        setContent {
            RescueMeshTheme {
                appViewModel = viewModel()
                val accountViewModel: AccountViewModel = viewModel()
                val prepareViewModel: PrepareViewModel = viewModel()
                // FIX9 (19 Nov 2026): the location permission prompt for the
                // Home fetch — the SAME system dialog shape Nearby uses
                // (RequestMultiplePermissions), not a new flow. On grant,
                // the saved fix is refreshed right away; on denial Home
                // simply never has a fix, and nothing is blocked by it.
                // FIX11: the denial is now classified — permanently
                // blocked only when Android will no longer show the
                // prompt (no rationale) AND we have asked before, so a
                // first denial still gets the popup on the next pill tap.
                val locationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result.values.any { it }) {
                        appViewModel.setLocationBlocked(false)
                        appViewModel.refreshSavedLocation()
                    } else {
                        val noRationale = !ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (noRationale && appViewModel.wasLocationPermissionAsked()) {
                            appViewModel.setLocationBlocked(true)
                        }
                    }
                }
                RescueMeshRoot(
                    viewModel = appViewModel,
                    accountViewModel = accountViewModel,
                    prepareViewModel = prepareViewModel,
                    onRequestPermissions = {
                        permissionLauncher.launch(NearbyPermissions.requiredRuntimePermissions())
                    },
                    onRequestLocationPermissions = {
                        appViewModel.markLocationPermissionAsked()
                        locationLauncher.launch(LocationFetcher.LOCATION_PERMISSIONS)
                    },
                    // FIX11: the pill's Settings path — the app's own
                    // Settings page, where location can be allowed after
                    // a permanent denial (the system prompt is gone then).
                    onOpenLocationSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                        )
                    }
                )
            }
        }
    }
}
