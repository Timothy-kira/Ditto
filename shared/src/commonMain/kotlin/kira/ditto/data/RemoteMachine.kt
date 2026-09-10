package kira.ditto.data

import kotlinx.serialization.Serializable

const val RemoteAgentRuntime = "remote"
const val RemoteAgentPathPrefix = "remote://"

@Serializable
data class RemoteMachine(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
    val cwd: String = "",
    val lastSeenAtMillis: Long = 0L,
)

data class ParsedRemoteMachineInput(
    val baseUrl: String,
    val token: String,
    val suggestedName: String,
)

data class RemoteWorkspace(
    val id: String,
    val name: String,
    val root: String,
    val lastOpenedAt: String = "",
)

data class RemoteKimiModel(
    val id: String,
    val provider: String,
    val displayName: String,
    val maxContextSize: Long = 0L,
    val capabilities: List<String> = emptyList(),
    val supportEfforts: List<String> = emptyList(),
    val defaultEffort: String = "",
)

/** kimi web typical default when config has no permission fields. */
const val RemoteDefaultPermissionMode = "auto"

data class RemoteKimiModelCatalog(
    val models: List<RemoteKimiModel> = emptyList(),
    val defaultModelId: String = "",
    val defaultPermissionMode: String = RemoteDefaultPermissionMode,
)

data class PendingRemoteSetup(
    val machineId: String = "",
    val baseUrl: String,
    val token: String,
    val suggestedName: String,
    val currentCwd: String = "",
    val workspaces: List<RemoteWorkspace> = emptyList(),
    val models: List<RemoteKimiModel> = emptyList(),
    val defaultModelId: String = "",
    val defaultPermissionMode: String = RemoteDefaultPermissionMode,
)

/** kimi web REST: permission_mode is manual|auto|yolo; plan is a separate flag. */
data class RemotePermissionWire(
    val permissionMode: String,
    val planMode: Boolean,
    val yolo: Boolean,
)

fun normalizeRemoteUiPermissionMode(value: String?): String =
    value?.trim()?.lowercase()?.takeIf { it in SupportedKimiPermissionModes }
        ?: RemoteDefaultPermissionMode

fun uiPermissionModeFromRemote(
    permissionMode: String?,
    planMode: Boolean = false,
    yolo: Boolean = false,
): String {
    if (planMode) return "plan"
    return when (permissionMode?.trim()?.lowercase().orEmpty()) {
        "manual", "default", "ask" -> "default"
        "auto" -> "auto"
        "yolo" -> "yolo"
        else -> if (yolo) "yolo" else RemoteDefaultPermissionMode
    }
}

fun remotePermissionWireFromUi(uiMode: String): RemotePermissionWire {
    val mode = normalizeRemoteUiPermissionMode(uiMode)
    return when (mode) {
        "plan" -> RemotePermissionWire(
            permissionMode = "auto",
            planMode = true,
            yolo = false,
        )
        "default" -> RemotePermissionWire(
            permissionMode = "manual",
            planMode = false,
            yolo = false,
        )
        "yolo" -> RemotePermissionWire(
            permissionMode = "yolo",
            planMode = false,
            yolo = true,
        )
        else -> RemotePermissionWire(
            permissionMode = "auto",
            planMode = false,
            yolo = false,
        )
    }
}

fun remoteModelProviderConfigId(machineId: String): String = "remote:$machineId"

fun List<RemoteKimiModel>.toProviderModelOptions(machineId: String): List<ProviderModelOption> {
    val configId = remoteModelProviderConfigId(machineId)
    return map { model ->
        val display = model.displayName.ifBlank { model.id }
        ProviderModelOption(
            key = buildModelOptionKey(configId, model.id),
            providerConfigId = configId,
            providerId = "kimi-code",
            providerName = "Kimi Code",
            piProviderId = model.provider.ifBlank { "kimi-code" },
            authMethod = ProviderAuthMethod.Ambient,
            apiKey = "",
            oauthCredentialJson = "",
            providerEnvironmentVariables = emptyList(),
            baseUrl = "",
            modelId = model.id,
            userAgent = "",
            customHeaders = emptyList(),
            fullLabel = display,
            chatLabel = display,
        )
    }
}

fun remoteAgentPath(machineId: String, cwd: String = ""): String {
    val encodedCwd = cwd.trim().replace("|", "%7C")
    return if (encodedCwd.isBlank()) {
        "$RemoteAgentPathPrefix$machineId"
    } else {
        "$RemoteAgentPathPrefix$machineId|$encodedCwd"
    }
}

fun parseRemoteMachineIdFromAgentPath(jsonlPath: String): String =
    jsonlPath.removePrefix(RemoteAgentPathPrefix).substringBefore('|').trim()

fun parseRemoteCwdFromAgentPath(jsonlPath: String): String =
    jsonlPath.removePrefix(RemoteAgentPathPrefix)
        .substringAfter('|', "")
        .replace("%7C", "|")
        .trim()

fun isRemoteAgentPath(jsonlPath: String): Boolean =
    jsonlPath.startsWith(RemoteAgentPathPrefix, ignoreCase = true)

const val OfficialRemoteControlHost = "code-rc.kimi.com"

private val OfficialRemoteControlUrlRegex = Regex(
    """https://code-rc\.kimi\.com/devices/[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""",
    RegexOption.IGNORE_CASE,
)

fun extractOfficialRemoteControlUrl(text: String): String? {
    val match = OfficialRemoteControlUrlRegex.find(text)?.value ?: return null
    return match.trimEnd('.', ',', ')', ']', '"', '\'')
}

fun isOfficialRemoteControlUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return false
    val withoutFragment = trimmed.substringBefore('#')
    val host = withoutFragment
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .lowercase()
    if (host != OfficialRemoteControlHost) return false
    return "/devices/" in withoutFragment.lowercase()
}

fun isLegacyLanKimiWebUrl(raw: String): Boolean {
    if (isOfficialRemoteControlUrl(raw)) return false
    val parsed = parseRemoteMachineInput(raw) ?: return false
    return parsed.token.isNotBlank() ||
        ":58627" in parsed.baseUrl ||
        parsed.baseUrl.contains("/api/v1", ignoreCase = true)
}

fun parseRemoteMachineInput(raw: String): ParsedRemoteMachineInput? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val withoutFragment = trimmed.substringBefore('#')
    val fragment = trimmed.substringAfter('#', "").trim()
    val fragmentToken = fragment
        .split('&')
        .map { it.trim() }
        .firstOrNull { it.startsWith("token=", ignoreCase = true) }
        ?.substringAfter('=')
        .orEmpty()
        .trim()
    val query = withoutFragment.substringAfter('?', "")
    val pathAndHost = withoutFragment.substringBefore('?')
    val queryToken = query
        .split('&')
        .map { it.trim() }
        .firstOrNull { it.startsWith("token=", ignoreCase = true) }
        ?.substringAfter('=')
        .orEmpty()
        .trim()
    val token = fragmentToken.ifBlank { queryToken }
    val normalized = normalizeRemoteBaseUrl(pathAndHost) ?: return null
    val host = normalized
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .ifBlank { "Kimi" }
    return ParsedRemoteMachineInput(
        baseUrl = normalized,
        token = token,
        suggestedName = host,
    )
}

fun normalizeRemoteBaseUrl(raw: String): String? {
    var value = raw.trim().trimEnd('/')
    if (value.isBlank()) return null
    if (value.startsWith("ws://", ignoreCase = true)) {
        value = "http://" + value.removePrefix("ws://")
    } else if (value.startsWith("wss://", ignoreCase = true)) {
        value = "https://" + value.removePrefix("wss://")
    } else if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        value = "http://$value"
    }
    val withoutPath = if ("/api/" in value) {
        value.substringBefore("/api/")
    } else {
        value
    }
    return withoutPath.trimEnd('/').ifBlank { null }
}

fun remoteWebSocketUrl(baseUrl: String): String {
    val http = baseUrl.trim().trimEnd('/')
    val ws = when {
        http.startsWith("https://", ignoreCase = true) -> "wss://" + http.removePrefix("https://")
        http.startsWith("http://", ignoreCase = true) -> "ws://" + http.removePrefix("http://")
        else -> "ws://$http"
    }
    return "$ws/api/v1/ws"
}
