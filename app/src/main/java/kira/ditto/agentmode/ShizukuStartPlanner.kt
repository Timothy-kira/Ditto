package kira.ditto.agentmode

internal object ShizukuStartPlanner {
    fun shouldPromptWirelessPairing(
        binderReady: Boolean,
        silentStartSucceeded: Boolean,
        adbWifiEnabled: Boolean,
        pairingPort: Int,
    ): Boolean {
        if (binderReady || silentStartSucceeded) return false
        return adbWifiEnabled || pairingPort > 0
    }
}
