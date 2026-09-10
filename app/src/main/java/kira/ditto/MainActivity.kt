package kira.ditto

import android.content.Intent
import android.os.Bundle
import android.view.animation.PathInterpolator
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kira.ditto.agentmode.AgentModeImeGuard
import kira.ditto.browser.BrowserEngineSurface
import kira.ditto.data.DeviceAccessPermissions
import kira.ditto.data.HostHealthSampler
import kira.ditto.data.HuaweiHealthAuthBridge
import kira.ditto.data.HuaweiHealthAuthHost
import kira.ditto.ui.AetherApp
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), HuaweiHealthAuthHost {
    private val keepSplashOnScreen = AtomicBoolean(true)
    private val deviceAccessPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    private var pendingHealthAuth: ((ActivityResult) -> Unit)? = null
    private val healthAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pendingHealthAuth?.invoke(result)
        pendingHealthAuth = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen.get() }
        splashScreen.setOnExitAnimationListener { splashView ->
            splashView.view.animate()
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(PathInterpolator(0.22f, 0.84f, 0.18f, 1f))
                .withEndAction { splashView.remove() }
                .start()
        }
        enableEdgeToEdge()
        BrowserEngineSurface.bindHost(this)
        AgentModeImeGuard.attach(this)
        HuaweiHealthAuthBridge.attach(this, this)
        setContent {
            AetherApp(
                onNotificationPermissionRequested = ::maybeRequestNotificationPermission,
                onFirstFrameReady = { keepSplashOnScreen.set(false) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        BrowserEngineSurface.bindHost(this)
        AgentModeImeGuard.attach(this)
        HuaweiHealthAuthBridge.attach(this, this)
        maybeRequestLocationPermission()
        HostHealthSampler.sampleNow(this)
        (application as AetherApplication).runtime.nativeModManager.notifyUiStable()
    }

    override fun onDestroy() {
        BrowserEngineSurface.unbindHost(this)
        HuaweiHealthAuthBridge.detach(this)
        AgentModeImeGuard.detach(this)
        super.onDestroy()
    }

    override fun requestAuthorization(intent: Intent, onResult: (ActivityResult) -> Unit) {
        pendingHealthAuth = onResult
        healthAuthLauncher.launch(intent)
    }

    private fun maybeRequestNotificationPermission() {
        val missing = DeviceAccessPermissions.missing(this)
        if (missing.isEmpty()) return
        deviceAccessPermissionLauncher.launch(missing)
    }

    private fun maybeRequestLocationPermission() {
        val missing = DeviceAccessPermissions.missingLocation(this)
        if (missing.isEmpty()) return
        deviceAccessPermissionLauncher.launch(missing)
    }
}
