package kira.ditto

import androidx.compose.ui.window.ComposeUIViewController
import kira.ditto.platform.currentPlatformCapabilities
import kira.ditto.runtime.IosAlpineRuntime
import kira.ditto.runtime.NativeRuntimeHost
import kira.ditto.ui.IosComposeApp
import kira.ditto.data.createIosAetherSettingsStore
import kira.ditto.data.createIosAetherChatHistoryDatabase
import kira.ditto.platform.IosPlatformServices
import kira.ditto.platform.IosNativeSettingsHost

fun MainViewController(runtimeHost: NativeRuntimeHost): platform.UIKit.UIViewController {
    val runtime = IosAlpineRuntime(runtimeHost)
    val settingsStore = createIosAetherSettingsStore()
    val chatHistoryDatabase = createIosAetherChatHistoryDatabase()
    val platformServices = IosPlatformServices(runtimeHost)
    return ComposeUIViewController {
        IosComposeApp(
            runtime = runtime,
            capabilities = currentPlatformCapabilities,
            settingsStore = settingsStore,
            chatHistoryDatabase = chatHistoryDatabase,
            platformServices = platformServices,
            nativeSettingsHost = IosNativeSettingsHost,
        )
    }
}
