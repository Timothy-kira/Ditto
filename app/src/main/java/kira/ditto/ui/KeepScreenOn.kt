package kira.ditto.ui

import android.view.Window
import android.view.WindowManager
import kira.ditto.data.SessionExecutionState

internal fun shouldKeepAgentScreenOn(
    isSending: Boolean,
    pendingResponseSessionId: String?,
    agentModeDisplayActive: Boolean,
    sessionExecutionStates: Map<String, SessionExecutionState>,
): Boolean =
    isSending ||
        pendingResponseSessionId != null ||
        agentModeDisplayActive ||
        sessionExecutionStates.values.any { it.activeTurnStartedAtMillis != null }

internal fun applyKeepScreenOn(window: Window?, keepOn: Boolean) {
    if (window == null) return
    if (keepOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
