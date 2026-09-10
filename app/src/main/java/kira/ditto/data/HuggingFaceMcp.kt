package kira.ditto.data

/**
 * Built-in Hugging Face MCP. Official server is mostly hf_fs plus Hub search.
 * Aether ships huggingface_catalog + huggingface_call to keep tools/list tiny.
 */
internal object HuggingFaceMcp {
    const val PluginId = "aether-huggingface"
    const val ServerName = "huggingface"
    const val CatalogTool = "huggingface_catalog"
    const val CallTool = "huggingface_call"

    val Spec = CompactShippedMcp(
        pluginId = PluginId,
        serverName = ServerName,
        actionLabel = "Hugging Face",
        catalogTool = CatalogTool,
        callTool = CallTool,
        matchHint = "huggingface",
        catalogHeader = "Hub search/info. call with method + params. method=schema for one spec. hf_ token as the user.",
        methods = listOf(
            CompactMcpMethod("whoami", "当前用户", ""),
            CompactMcpMethod("hf_fs", "浏览 Hub", "action,type?,query?,repo_id?,repo_type?"),
            CompactMcpMethod("search_models", "搜模型", "search,author?,filter?,sort?,limit?"),
            CompactMcpMethod("search_datasets", "搜数据集", "search,author?,filter?,sort?,limit?"),
            CompactMcpMethod("search_spaces", "搜 Space", "search,author?,filter?,sort?,limit?"),
            CompactMcpMethod("search_papers", "搜论文", "q,limit?"),
            CompactMcpMethod("model_info", "模型详情", "repo_id"),
            CompactMcpMethod("dataset_info", "数据集详情", "repo_id"),
            CompactMcpMethod("space_info", "Space 详情", "repo_id"),
            CompactMcpMethod("paper_info", "论文详情", "paper_id"),
            CompactMcpMethod("list_files", "仓库文件树", "repo_id,repo_type?,revision?"),
        ),
    )

    val ToolNames: List<String> get() = Spec.toolNames
    fun catalogText(): String = Spec.catalogText()
    fun schemaText(method: String): String = Spec.schemaText(method)
    fun httpUrl(): String = Spec.httpUrl()
    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = Spec.mcpServerConfig(sessionId)
    fun isShippedServerId(serverId: String): Boolean = Spec.isShippedServerId(serverId)
    fun toAcpServer(sessionId: String = ""): org.json.JSONObject? = Spec.toAcpServer(sessionId)
    fun initializeResult() = Spec.initializeResult()
    fun listToolsResult() = Spec.listToolsResult()
    fun matchesToolName(name: String): Boolean = Spec.matchesToolName(name)
    fun canonicalToolName(name: String): String = Spec.canonicalToolName(name)
    fun wrapCallResult(rawOutput: String) = Spec.wrapCallResult(rawOutput)
    fun compactJson(raw: String): String = Spec.compactJson(raw)
}
