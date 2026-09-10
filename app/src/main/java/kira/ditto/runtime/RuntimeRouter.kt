package kira.ditto.runtime

import kira.ditto.data.AppSettings
import kira.ditto.data.LocalRuntimeId
import org.json.JSONObject

class RuntimeRouter(
    private val alpineRuntime: LocalRuntime,
) {
    fun runtimeFor(
        settings: AppSettings,
        environment: String?,
    ): LocalRuntime? {
        val requested = environment?.trim().orEmpty().lowercase()
        if (requested == "termux") return alpineRuntime
        if (requested.isNotEmpty() && requested != "default" && requested != "alpine") {
            return null
        }
        return alpineRuntime.takeIf {
            settings.enabledRuntimeIds.isEmpty() ||
                LocalRuntimeId.Alpine in settings.enabledRuntimeIds ||
                settings.alpineSetupCompleted ||
                settings.defaultRuntimeId == LocalRuntimeId.Alpine ||
                settings.defaultRuntimeId == LocalRuntimeId.Termux ||
                settings.defaultRuntimeId == null
        } ?: alpineRuntime
    }

    fun runtimeById(runtimeId: LocalRuntimeId): LocalRuntime = alpineRuntime

    fun runtimeForRunId(runId: String): Pair<LocalRuntime, String>? {
        val separatorIndex = runId.indexOf(':')
        if (separatorIndex <= 0) return null
        LocalRuntimeId.fromStorage(runId.substring(0, separatorIndex)) ?: return null
        return alpineRuntime to runId.substring(separatorIndex + 1)
    }

    fun runtimeWorkspaceDirectory(
        settings: AppSettings,
        termuxWorkspaceDirectory: String,
        environment: String? = null,
    ): String {
        runtimeFor(settings, environment)
        return alpineRuntime.workspaceRoot
    }

    fun setupRequiredError(environment: String? = null): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", "No local runtime is configured for this tool call.")
            put("hint", "Initialize Alpine in Settings before using local tools.")
            if (!environment.isNullOrBlank()) put("environment", environment)
        }.toString()
}

internal fun JSONObject.runtimeEnvironment(): String? {
    val value = optString("runtime").ifBlank { optString("environment") }.trim()
        .ifBlank { optString("runtimeEnvironment").trim() }
    return value.takeIf { it.isNotBlank() }
}
