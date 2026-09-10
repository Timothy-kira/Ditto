package kira.ditto.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityDarkerSystemColorsStatusDidChangeNotification
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

@Composable
actual fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences {
    fun readPreferences() = PlatformAccessibilityPreferences(
        reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
        increasedContrast = UIAccessibilityDarkerSystemColorsEnabled(),
    )
    var preferences by remember { mutableStateOf(readPreferences()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val reduceMotionObserver = center.addObserverForName(
            UIAccessibilityReduceMotionStatusDidChangeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { preferences = readPreferences() }
        val contrastObserver = center.addObserverForName(
            UIAccessibilityDarkerSystemColorsStatusDidChangeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { preferences = readPreferences() }
        onDispose {
            center.removeObserver(reduceMotionObserver)
            center.removeObserver(contrastObserver)
        }
    }
    return preferences
}
