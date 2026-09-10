package kira.ditto.runtime

import kira.ditto.data.AgentDisplayMcp
import kira.ditto.data.AgentModeDisplayGate
import kira.ditto.data.AetherLearningSessionHeader
import kira.ditto.data.DeviceCatalogMcp
import kira.ditto.data.PhoneUiMcp
import kira.ditto.data.AgentModeSafety
import kira.ditto.data.PhoneAppFlowMcp
import kira.ditto.data.UpaMcpHttpPort
import kira.ditto.data.UpaMcpHttpPathPrefix
import kira.ditto.data.UpaMcpProtocolVersion
import kira.ditto.data.GmailMcp
import kira.ditto.data.AmapMcp
import kira.ditto.data.GithubMcp
import kira.ditto.data.HuggingFaceMcp
import kira.ditto.data.SpotifyMcp
import kira.ditto.data.WebMcpMcp
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

private const val MaxHttpBodyBytes = 1_048_576
private const val ClientSoTimeoutMillis = 15_000
private const val ExclusiveGuiCallTimeoutMillis = 20_000L

/**
 * Loopback MCP HTTP for Agent Mode (`agent_display` / `phone_app` / `device_catalog`)
 * plus `webmcp` and listed MCP servers such as `gmail`.
 * UPA UI plugins are not MCP servers and are not served here.
 */
class UpaMcpHttpGateway {
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool(
        ThreadFactory { runnable ->
            Thread(runnable, "agent-mode-mcp-http").apply { isDaemon = true }
        },
    )
    @Volatile
    private var agentDisplayExecutor: ((JSONObject, String) -> String)? = null
    @Volatile
    private var phoneAppFlowListTools: (() -> JSONObject)? = null
    @Volatile
    private var phoneAppFlowCallTool: ((String, JSONObject, String) -> String)? = null
    @Volatile
    private var deviceCatalogExecutor: ((JSONObject) -> String)? = null
    @Volatile
    private var webMcpListTools: (() -> JSONObject)? = null
    @Volatile
    private var webMcpCallTool: ((String, JSONObject, String) -> String)? = null
    @Volatile
    private var gmailMcpCallTool: ((String, JSONObject) -> String)? = null
    @Volatile
    private var spotifyMcpCallTool: ((String, JSONObject) -> String)? = null
    @Volatile
    private var amapMcpCallTool: ((String, JSONObject) -> String)? = null
    @Volatile
    private var githubMcpCallTool: ((String, JSONObject) -> String)? = null
    @Volatile
    private var huggingFaceMcpCallTool: ((String, JSONObject) -> String)? = null
    @Volatile
    var onToolActivity: (() -> Unit)? = null
    private val guiGate = AgentModeDisplayGate()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun attachAgentDisplayExecutor(executor: (JSONObject, String) -> String) {
        agentDisplayExecutor = executor
    }

    fun attachPhoneAppFlow(
        listTools: () -> JSONObject,
        callTool: (String, JSONObject, String) -> String,
    ) {
        phoneAppFlowListTools = listTools
        phoneAppFlowCallTool = callTool
    }

    fun attachDeviceCatalogExecutor(executor: (JSONObject) -> String) {
        deviceCatalogExecutor = executor
    }

    fun attachWebMcp(
        listTools: () -> JSONObject,
        callTool: (String, JSONObject, String) -> String,
    ) {
        webMcpListTools = listTools
        webMcpCallTool = callTool
    }

    fun attachGmailMcp(callTool: (String, JSONObject) -> String) {
        gmailMcpCallTool = callTool
    }

    fun attachSpotifyMcp(callTool: (String, JSONObject) -> String) {
        spotifyMcpCallTool = callTool
    }

    fun attachAmapMcp(callTool: (String, JSONObject) -> String) {
        amapMcpCallTool = callTool
    }

    fun attachGithubMcp(callTool: (String, JSONObject) -> String) {
        githubMcpCallTool = callTool
    }

    fun attachHuggingFaceMcp(callTool: (String, JSONObject) -> String) {
        huggingFaceMcpCallTool = callTool
    }

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket(UpaMcpHttpPort, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            acceptThread = Thread(
                {
                    while (!socket.isClosed) {
                        val client = runCatching { socket.accept() }.getOrNull() ?: continue
                        client.soTimeout = ClientSoTimeoutMillis
                        workers.execute { handleClient(client) }
                    }
                },
                "upa-mcp-http-accept",
            ).apply {
                isDaemon = true
                start()
            }
            verifyLoopbackHealth(UpaMcpHttpPort)
        } catch (_: BindException) {
            started.set(false)
        } catch (error: Throwable) {
            started.set(false)
            throw error
        }
    }

    fun stop() {
        started.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val request = readHttpRequest(BufferedInputStream(client.getInputStream())) ?: run {
                writeHttp(client, 400, jsonRpcError(null, -32700, "invalid request").toString())
                return
            }
            val path = request.path.substringBefore('?')
            if (request.method == "GET" && path == "/health") {
                writeHttp(client, 200, JSONObject().put("ok", true).toString())
                return
            }
            if (request.method == "OPTIONS" && path.startsWith(UpaMcpHttpPathPrefix)) {
                writeHttp(client, 204, "", extraHeaders = listOf("Allow: POST, OPTIONS"))
                return
            }
            if (!path.startsWith(UpaMcpHttpPathPrefix)) {
                writeHttp(client, 404, jsonRpcError(null, -32601, "not found").toString())
                return
            }
            val pluginId = URLDecoder.decode(
                path.removePrefix(UpaMcpHttpPathPrefix).trim('/'),
                Charsets.UTF_8.name(),
            )
            if (pluginId.isBlank()) {
                writeHttp(client, 404, jsonRpcError(null, -32601, "missing plugin id").toString())
                return
            }
            val isAgentModeEndpoint =
                pluginId == AgentDisplayMcp.PluginId ||
                    pluginId == PhoneUiMcp.PluginId ||
                    pluginId == PhoneAppFlowMcp.PluginId ||
                    pluginId == DeviceCatalogMcp.PluginId ||
                    pluginId == WebMcpMcp.PluginId ||
                    pluginId == GmailMcp.PluginId ||
                    pluginId == SpotifyMcp.PluginId ||
                    pluginId == AmapMcp.PluginId ||
                    pluginId == GithubMcp.PluginId ||
                    pluginId == HuggingFaceMcp.PluginId
            if (!isAgentModeEndpoint) {
                writeHttp(client, 404, jsonRpcError(null, -32601, "not found").toString())
                return
            }
            if (request.method != "POST") {
                writeHttp(client, 405, jsonRpcError(null, -32601, "POST required").toString())
                return
            }
            val trimmed = request.body.trim()
            val learningSessionId = request.headers[AetherLearningSessionHeader.lowercase()].orEmpty()
            if (trimmed.startsWith("[")) {
                val batch = JSONArray(trimmed.ifBlank { "[]" })
                val responses = JSONArray()
                var protocol = UpaMcpProtocolVersion
                for (index in 0 until batch.length()) {
                    val message = batch.optJSONObject(index) ?: continue
                    if (pluginId == WebMcpMcp.PluginId) {
                        protocol = WebMcpMcp.resolveProtocol(request.headers, message)
                    }
                    dispatch(pluginId, message, learningSessionId, request.headers)?.let(responses::put)
                }
                writeHttp(client, 200, responses.toString(), accept = request.accept, protocol = protocol)
                return
            }
            val message = runCatching { JSONObject(trimmed.ifBlank { "{}" }) }.getOrElse {
                writeHttp(client, 400, jsonRpcError(null, -32700, "parse error").toString())
                return
            }
            val response = dispatch(pluginId, message, learningSessionId, request.headers)
            val protocol = if (pluginId == WebMcpMcp.PluginId) {
                WebMcpMcp.resolveProtocol(request.headers, message)
            } else {
                UpaMcpProtocolVersion
            }
            if (response == null) {
                writeHttp(client, 202, "", protocol = protocol)
                return
            }
            writeHttp(client, 200, response.toString(), accept = request.accept, protocol = protocol)
        }
    }

    private fun dispatch(
        pluginId: String,
        message: JSONObject,
        learningSessionId: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? {
        if (pluginId == AgentDisplayMcp.PluginId) {
            return dispatchAgentDisplay(message, learningSessionId)
        }
        if (pluginId == PhoneUiMcp.PluginId) {
            return dispatchPhoneUi(message, learningSessionId)
        }
        if (pluginId == PhoneAppFlowMcp.PluginId) {
            return dispatchPhoneAppFlow(message, learningSessionId)
        }
        if (pluginId == DeviceCatalogMcp.PluginId) {
            return dispatchDeviceCatalog(message)
        }
        if (pluginId == WebMcpMcp.PluginId) {
            return dispatchWebMcp(message, learningSessionId, headers)
        }
        if (pluginId == GmailMcp.PluginId) {
            return dispatchGmail(message)
        }
        if (pluginId == SpotifyMcp.PluginId) {
            return dispatchSpotify(message)
        }
        if (pluginId == AmapMcp.PluginId) {
            return dispatchAmap(message)
        }
        if (pluginId == GithubMcp.PluginId) {
            return dispatchGithub(message)
        }
        if (pluginId == HuggingFaceMcp.PluginId) {
            return dispatchHuggingFace(message)
        }
        val requestId = if (message.has("id") && !message.isNull("id")) message.get("id") else null
        return jsonRpcError(requestId, -32601, "Method not found")
    }

    private fun dispatchAgentDisplay(message: JSONObject, learningSessionId: String): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> AgentDisplayMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> AgentDisplayMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name").ifBlank { AgentDisplayMcp.ToolName }
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = agentDisplayExecutor
                        ?: error("Agent Mode display is not attached.")
                    if (!name.equals(AgentDisplayMcp.ToolName, ignoreCase = true) &&
                        !name.endsWith(AgentDisplayMcp.ToolName, ignoreCase = true)
                    ) {
                        error("Unknown tool: $name")
                    }
                    AgentDisplayMcp.wrapCallResult(
                        exclusiveGuiCall(arguments, treatContentionAsBusy = true) {
                            executor(arguments, learningSessionId)
                        },
                    )
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchPhoneUi(message: JSONObject, learningSessionId: String): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> PhoneUiMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> PhoneUiMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = agentDisplayExecutor
                        ?: error("Phone UI tools are not attached.")
                    if (!PhoneUiMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    val mapped = PhoneUiMcp.toExecuteArguments(name, arguments)
                    PhoneUiMcp.wrapCallResult(
                        exclusiveGuiCall(mapped, treatContentionAsBusy = true) {
                            executor(mapped, learningSessionId)
                        },
                    )
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchWebMcp(
        message: JSONObject,
        learningSessionId: String,
        headers: Map<String, String>,
    ): JSONObject? {
        val protocol = WebMcpMcp.resolveProtocol(headers, message)
        val method = WebMcpMcp.resolveMethod(message.optString("method"), headers)
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = when {
            hasId -> message.get("id")
            protocol == WebMcpMcp.Protocol2026 -> JSONObject.NULL
            else -> null
        }
        if (!hasId && protocol != WebMcpMcp.Protocol2026) return null
        val result = runCatching {
            when (method) {
                "initialize" -> WebMcpMcp.initializeResult(protocol)
                "server/discover", "discover" -> WebMcpMcp.discoverResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> webMcpListTools?.invoke() ?: WebMcpMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                        .ifBlank { headers["mcp-name"].orEmpty() }
                        .ifBlank { params.optString("tool") }
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = webMcpCallTool
                        ?: error("WebMCP host is not attached.")
                    if (!WebMcpMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    WebMcpMcp.wrapCallResult(executor(name, arguments, learningSessionId))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchGmail(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> GmailMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> GmailMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = gmailMcpCallTool
                        ?: error("Gmail MCP is not attached.")
                    if (!GmailMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    GmailMcp.wrapCallResult(executor(name, arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchSpotify(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> SpotifyMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> SpotifyMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = spotifyMcpCallTool
                        ?: error("Spotify MCP is not attached.")
                    if (!SpotifyMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    SpotifyMcp.wrapCallResult(executor(name, arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchAmap(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> AmapMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> AmapMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = amapMcpCallTool
                        ?: error("Amap MCP is not attached.")
                    if (!AmapMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    AmapMcp.wrapCallResult(executor(name, arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchGithub(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> GithubMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> GithubMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = githubMcpCallTool
                        ?: error("GitHub MCP is not attached.")
                    if (!GithubMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    GithubMcp.wrapCallResult(executor(name, arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchHuggingFace(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> HuggingFaceMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> HuggingFaceMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = huggingFaceMcpCallTool
                        ?: error("Hugging Face MCP is not attached.")
                    if (!HuggingFaceMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    HuggingFaceMcp.wrapCallResult(executor(name, arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchPhoneAppFlow(message: JSONObject, learningSessionId: String): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> PhoneAppFlowMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> phoneAppFlowListTools?.invoke()
                    ?: PhoneAppFlowMcp.listToolsResult(emptyList())
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = phoneAppFlowCallTool
                        ?: error("Phone app flows are not attached.")
                    val raw = if (name.equals(PhoneAppFlowMcp.RecallToolName, ignoreCase = true)) {
                        executor(name, arguments, learningSessionId)
                    } else {
                        exclusiveGuiCall(arguments, treatContentionAsBusy = true) {
                            executor(name, arguments, learningSessionId)
                        }
                    }
                    PhoneAppFlowMcp.wrapCallResult(raw)
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private fun dispatchDeviceCatalog(message: JSONObject): JSONObject? {
        val method = message.optString("method")
        val hasId = message.has("id") && !message.isNull("id")
        val requestId = if (hasId) message.get("id") else null
        if (!hasId) return null
        val result = runCatching {
            when (method) {
                "initialize" -> DeviceCatalogMcp.initializeResult()
                "ping", "notifications/initialized" -> JSONObject()
                "tools/list" -> DeviceCatalogMcp.listToolsResult()
                "tools/call" -> {
                    onToolActivity?.invoke()
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name").ifBlank { DeviceCatalogMcp.ToolName }
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    val executor = deviceCatalogExecutor
                        ?: error("Device catalog is not attached.")
                    if (!DeviceCatalogMcp.matchesToolName(name)) {
                        error("Unknown tool: $name")
                    }
                    DeviceCatalogMcp.wrapCallResult(executor(arguments))
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return jsonRpcError(requestId, -32601, "Method not found: $method")
            }
        }.getOrElse { error ->
            return jsonRpcError(requestId, -32000, error.message ?: "internal error")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
        val accept: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArray(8 * 1024)
        var headerEnd = -1
        var total = 0
        while (total < headerBytes.size) {
            val byte = input.read()
            if (byte < 0) break
            headerBytes[total] = byte.toByte()
            total += 1
            if (
                total >= 4 &&
                headerBytes[total - 4] == '\r'.code.toByte() &&
                headerBytes[total - 3] == '\n'.code.toByte() &&
                headerBytes[total - 2] == '\r'.code.toByte() &&
                headerBytes[total - 1] == '\n'.code.toByte()
            ) {
                headerEnd = total
                break
            }
        }
        if (headerEnd < 0) return null
        val header = String(headerBytes, 0, headerEnd, Charsets.US_ASCII)
        val lines = header.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
            val headers = lines.drop(1).filter { it.contains(':') }.associate { line ->
                line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (contentLength > MaxHttpBodyBytes) return null
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            return HttpRequest(
                method = requestLine[0].uppercase(),
                path = requestLine[1],
                body = String(bodyBytes, 0, read, Charsets.UTF_8),
                accept = headers["accept"].orEmpty(),
                headers = headers,
            )
    }

    private fun writeHttp(
        socket: Socket,
        status: Int,
        body: String,
        extraHeaders: List<String> = emptyList(),
        accept: String = "",
        protocol: String = UpaMcpProtocolVersion,
    ) {
        val sse = status == 200 &&
            body.isNotBlank() &&
            accept.contains("text/event-stream", ignoreCase = true) &&
            !accept.contains("application/json", ignoreCase = true)
        val payload = if (sse) {
            "event: message\ndata: $body\n\n".toByteArray(Charsets.UTF_8)
        } else {
            body.toByteArray(Charsets.UTF_8)
        }
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val contentType = if (sse) "text/event-stream" else "application/json; charset=utf-8"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("MCP-Protocol-Version: $protocol\r\n")
            append("Mcp-Session-Id: ${sessionId()}\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        BufferedOutputStream(socket.getOutputStream()).use { output ->
            output.write(header)
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun exclusiveGuiCall(
        arguments: JSONObject,
        treatContentionAsBusy: Boolean = true,
        block: () -> String,
    ): String = guiGate.runExclusive(
        treatContentionAsBusy = treatContentionAsBusy,
    ) {
        val result = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                result.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                done.countDown()
            }
        }, "agent-mode-gui-call")
        worker.isDaemon = true
        worker.start()
        if (!done.await(ExclusiveGuiCallTimeoutMillis, TimeUnit.MILLISECONDS)) {
            worker.interrupt()
            return@runExclusive AgentModeSafety.captureTimeoutJson()
        }
        error.get()?.let { throw it }
        result.get() ?: AgentModeSafety.captureTimeoutJson()
    }

    private fun sessionId(): String = gatewaySessionId

    private fun verifyLoopbackHealth(port: Int) {
        runCatching {
            val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 800
                connection.readTimeout = 800
                connection.requestMethod = "GET"
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private val gatewaySessionId: String = UUID.randomUUID().toString()

        fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject =
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id ?: JSONObject.NULL)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", message),
                )
    }
}
