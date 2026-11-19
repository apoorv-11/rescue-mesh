package com.rescuemesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rescuemesh.mesh.NearbyPermissions
import com.rescuemesh.ui.AccountViewModel
import com.rescuemesh.ui.AppViewModel
import com.rescuemesh.ui.PrepareViewModel
import com.rescuemesh.ui.RescueMeshRoot
import com.rescuemesh.ui.RescueMeshTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Result is visible when the user starts the mesh; Nearby reports failures there. */ }

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
                val appViewModel: AppViewModel = viewModel()
                val accountViewModel: AccountViewModel = viewModel()
                val prepareViewModel: PrepareViewModel = viewModel()
                RescueMeshRoot(
                    viewModel = appViewModel,
                    accountViewModel = accountViewModel,
                    prepareViewModel = prepareViewModel,
                    onRequestPermissions = {
                        permissionLauncher.launch(NearbyPermissions.requiredRuntimePermissions())
                    }
                )
            }
        }
    }
}
