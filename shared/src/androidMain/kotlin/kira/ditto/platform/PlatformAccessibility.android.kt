package kira.ditto.platform

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences {
    val resolver = LocalContext.current.contentResolver
    fun readPreferences() = PlatformAccessibilityPreferences(
        reduceMotion = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f,
        increasedContrast = Settings.Secure.getInt(
            resolver,
            "high_text_contrast_enabled",
            0,
        ) == 1,
    )
    var preferences by remember(resolver) { mutableStateOf(readPreferences()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                preferences = readPreferences()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor("high_text_contrast_enabled"),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return preferences
}
