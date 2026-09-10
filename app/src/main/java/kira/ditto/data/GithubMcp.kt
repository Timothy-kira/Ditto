package kira.ditto.data

/**
 * Built-in GitHub MCP. Official github-mcp-server registers one tool per API.
 * Aether ships github_catalog + github_call; method names stay official.
 * First version is the default toolsets: context, issues, pull_requests, repos, users.
 */
internal object GithubMcp {
    const val PluginId = "aether-github"
    const val ServerName = "github"
    const val CatalogTool = "github_catalog"
    const val CallTool = "github_call"

    val Spec = CompactShippedMcp(
        pluginId = PluginId,
        serverName = ServerName,
        actionLabel = "GitHub",
        catalogTool = CatalogTool,
        callTool = CallTool,
        matchHint = "github",
        catalogHeader = "default toolsets. call with method + params. method=schema for one spec. PAT as the user.",
        methods = listOf(
            CompactMcpMethod("get_me", "当前用户", ""),
            CompactMcpMethod("search_users", "搜用户", "q,per_page?"),
            CompactMcpMethod("search_repositories", "搜仓库", "q,sort?,order?,per_page?"),
            CompactMcpMethod("search_code", "搜代码", "q,per_page?"),
            CompactMcpMethod("search_commits", "搜提交", "q,per_page?"),
            CompactMcpMethod("get_file_contents", "读文件/目录", "owner,repo,path?,ref?"),
            CompactMcpMethod("list_commits", "提交列表", "owner,repo,sha?,path?,per_page?"),
            CompactMcpMethod("get_commit", "提交详情", "owner,repo,sha"),
            CompactMcpMethod("list_branches", "分支列表", "owner,repo,per_page?"),
            CompactMcpMethod("create_branch", "建分支", "owner,repo,branch,from?"),
            CompactMcpMethod("create_or_update_file", "写文件", "owner,repo,path,content,message,branch?"),
            CompactMcpMethod("delete_file", "删文件", "owner,repo,path,message,branch"),
            CompactMcpMethod("create_repository", "建仓库", "name,description?,private?,auto_init?"),
            CompactMcpMethod("fork_repository", "Fork", "owner,repo,organization?"),
            CompactMcpMethod("list_issues", "Issue 列表", "owner,repo,state?,labels?,per_page?"),
            CompactMcpMethod("search_issues", "搜 Issue", "query,owner?,repo?,per_page?"),
            CompactMcpMethod("issue_read", "读 Issue", "owner,repo,issue_number,method"),
            CompactMcpMethod("issue_write", "写 Issue", "owner,repo,method,title?,body?,issue_number?,labels?,assignees?,state?"),
            CompactMcpMethod("add_issue_comment", "Issue 评论", "owner,repo,issue_number,body"),
            CompactMcpMethod("list_pull_requests", "PR 列表", "owner,repo,state?,per_page?"),
            CompactMcpMethod("search_pull_requests", "搜 PR", "query,owner?,repo?,per_page?"),
            CompactMcpMethod("pull_request_read", "读 PR", "owner,repo,pullNumber,method"),
            CompactMcpMethod("create_pull_request", "开 PR", "owner,repo,title,head,base,body?,draft?"),
            CompactMcpMethod("update_pull_request", "改 PR", "owner,repo,pullNumber,title?,body?,state?"),
            CompactMcpMethod("merge_pull_request", "合并 PR", "owner,repo,pullNumber,merge_method?"),
            CompactMcpMethod("list_releases", "Release 列表", "owner,repo,per_page?"),
            CompactMcpMethod("get_latest_release", "最新 Release", "owner,repo"),
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
