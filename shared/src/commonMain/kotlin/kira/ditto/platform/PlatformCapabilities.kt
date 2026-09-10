package kira.ditto.platform

/** Platform feature switches are resolved once at composition/runtime creation. */
data class PlatformCapabilities(
    val termux: Boolean,
    val runtimeSelection: Boolean,
    val agentMode: Boolean,
    val scheduledTasks: Boolean,
    val persistentBackground: Boolean,
    val localNotifications: Boolean = false,
    val nativeMods: Boolean,
    val alpine: Boolean = true,
    val alpineChrome: Boolean = true,
    val stdioMcp: Boolean = true,
    val scriptExtensions: Boolean = true,
    val layeredScreenTransitions: Boolean = true,
    val supportsTabletLayout: Boolean = false,
) {
    companion object {
        val Android = PlatformCapabilities(
            termux = true,
            runtimeSelection = true,
            agentMode = true,
            scheduledTasks = true,
            persistentBackground = true,
            localNotifications = true,
            nativeMods = true,
        )

        val Ios = PlatformCapabilities(
            termux = false,
            runtimeSelection = false,
            agentMode = false,
            scheduledTasks = false,
            persistentBackground = true,
            localNotifications = false,
            nativeMods = false,
            alpineChrome = true,
            layeredScreenTransitions = true,
            supportsTabletLayout = true,
        )
    }
}

expect val currentPlatformCapabilities: PlatformCapabilities
