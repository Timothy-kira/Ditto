package kira.ditto.data

import org.json.JSONObject

internal object AgentModeSafety {
    const val ProtectedWindowCode = "PROTECTED_WINDOW"
    const val SensitiveActionCode = "SENSITIVE_ACTION"
    const val StaleSnapshotCode = "STALE_SNAPSHOT"
    const val StalledCode = "STALLED"
    const val MacroMismatchCode = "MACRO_MISMATCH"
    const val CaptureTimeoutCode = "CAPTURE_TIMEOUT"
    const val SceneNotSopCode = "SCENE_NOT_SOP"
    const val StallMutatingLimit = 4
    const val ReplanDegradedTo = "replan"

    val InterruptCodes = setOf(
        StalledCode,
        StaleSnapshotCode,
        ProtectedWindowCode,
        MacroMismatchCode,
        CaptureTimeoutCode,
        SceneNotSopCode,
        AgentModeDisplayGate.DisplayBusyCode,
    )

    fun captureTimeoutJson(
        message: String = "Screenshot timed out. Do not assume the MCP backend is down. Replan this segment with dump_tree or launch; do not spam screenshot.",
    ): String = JSONObject()
        .put("ok", false)
        .put("code", CaptureTimeoutCode)
        .put("errmsg", message)
        .put("consistent", "no")
        .put("degraded_to", ReplanDegradedTo)
        .toString()

    private val ProtectedPackages = listOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.oneplus.account",
    )

    private val PasswordManagerHints = listOf(
        "passwordmanager",
        "password_manager",
        "keepass",
        "bitwarden",
        "1password",
        "lastpass",
        "enpass",
    )

    private val ProtectedWindowHints = listOf(
        "permissioncontroller",
        "packageinstaller",
        "confirmpin",
        "keyguard",
    )

    private val SensitiveLabels = listOf(
        "支付", "转账", "付款", "删除", "卸载", "授权", "安装",
        "pay", "transfer", "delete", "uninstall", "grant", "install",
    )

    fun isProtectedPackage(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (ProtectedPackages.any { pkg == it || pkg.startsWith("$it.") }) return true
        if (PasswordManagerHints.any { pkg.contains(it) }) return true
        return false
    }

    fun shouldBlockLaunch(packageName: String): Boolean = isProtectedPackage(packageName)

    fun isPasswordManagerPackage(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        return PasswordManagerHints.any { pkg.contains(it) }
    }

    fun protectedWindowPackage(nodes: List<AgentModeUiTree.RichNode>, launchedPackage: String): String? {
        nodes.map { it.packageName }.distinct().forEach { pkg ->
            val lower = pkg.lowercase()
            if (isProtectedPackage(pkg)) return pkg
            if (ProtectedWindowHints.any { lower.contains(it) }) return pkg
        }
        val launched = launchedPackage.trim()
        if (isProtectedPackage(launched)) return launched
        return null
    }

    fun focusedPassword(nodes: List<AgentModeUiTree.RichNode>): Boolean =
        nodes.any { it.focused && it.password }

    fun looksSensitiveLabel(label: String): Boolean {
        val value = label.trim().lowercase()
        if (value.isBlank()) return false
        return SensitiveLabels.any { token ->
            value.equals(token, ignoreCase = true) ||
                (token.length >= 2 && value.contains(token, ignoreCase = true))
        }
    }
}
