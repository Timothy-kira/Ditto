package kira.ditto.data

internal const val UpaMcpHttpPort = 18792
internal const val UpaMcpHttpPathPrefix = "/mcp/"
internal const val DefaultUpaProxyBase = DefaultUpaProxyBaseUrl
internal const val UpaMcpProtocolVersion = "2025-11-25"

internal fun upaMcpHttpUrl(pluginId: String): String =
    "http://127.0.0.1:$UpaMcpHttpPort$UpaMcpHttpPathPrefix${pluginId.trim()}"

internal fun kimiMcpServerKey(pluginId: String): String =
    pluginId.trim().replace('.', '-').ifBlank { "upa-plugin" }
