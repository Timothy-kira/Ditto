package kira.ditto.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal object GithubMcpHost {
    private const val ApiRoot = "https://api.github.com"
    private val GithubHeaders = mapOf(
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    fun execute(context: Context, name: String, arguments: JSONObject): String {
        val tool = GithubMcp.canonicalToolName(name)
        if (tool.isBlank()) return JsonRest.errorJson("unknown_tool", "Unknown GitHub tool: $name")
        val store = HostSecretStore(context.applicationContext)
        val token = GithubAuth.snapshot(store).token
        if (token.isBlank()) {
            runCatching { GithubAuth.openConsole(context) }
            return JsonRest.missingToken(
                "GitHub",
                "Create a Personal Access Token and paste it in Settings.",
            )
        }
        return runCatching { dispatch(tool, arguments, token).put("ok", true).toString() }
            .getOrElse { error -> JsonRest.errorJson("github_api", error.message ?: "GitHub request failed") }
    }

    internal fun dispatch(tool: String, arguments: JSONObject, token: String): JSONObject {
        if (tool == GithubMcp.CatalogTool) {
            return JSONObject().put("catalog", GithubMcp.catalogText())
        }
        val method = arguments.optString("method").trim()
        require(method.isNotBlank()) { "method is required" }
        if (method.equals("schema", ignoreCase = true)) {
            val target = JsonRest.paramsObject(arguments).optString("method").ifBlank {
                arguments.optString("target").ifBlank { arguments.optString("name") }
            }
            return JSONObject().put("schema", GithubMcp.schemaText(target))
        }
        require(GithubMcp.Spec.knownMethod(method)) { "Unknown method $method. Use github_catalog." }
        return call(method, JsonRest.paramsObject(arguments), token)
    }

    internal fun call(method: String, params: JSONObject, token: String): JSONObject {
        val owner = JsonRest.opt(params, "owner")
        val repo = JsonRest.opt(params, "repo")
        val perPage = JSONObject().also { query ->
            JsonRest.opt(params, "per_page", "perPage").takeIf { it.isNotBlank() }?.let {
                query.put("per_page", it)
            }
            JsonRest.opt(params, "page").takeIf { it.isNotBlank() }?.let { query.put("page", it) }
        }
        return when (method) {
            "get_me" -> gh("GET", "/user", token)
            "search_users" -> search("users", JsonRest.need(params, "q"), params, token)
            "search_repositories" -> search("repositories", JsonRest.need(params, "q"), params, token)
            "search_code" -> search("code", JsonRest.need(params, "q"), params, token)
            "search_commits" -> search("commits", JsonRest.need(params, "q"), params, token)
            "get_file_contents" -> {
                val path = JsonRest.opt(params, "path").trimStart('/')
                val suffix = if (path.isBlank()) "" else "/$path"
                compactContents(
                    gh(
                        "GET",
                        "/repos/${needOwner(params)}/${needRepo(params)}/contents$suffix",
                        token,
                        query = JSONObject().also { q ->
                            JsonRest.opt(params, "ref", "sha").takeIf { it.isNotBlank() }?.let { q.put("ref", it) }
                        },
                    ),
                )
            }
            "list_commits" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/commits",
                token,
                query = perPage.also { q ->
                    JsonRest.opt(params, "sha").takeIf { it.isNotBlank() }?.let { q.put("sha", it) }
                    JsonRest.opt(params, "path").takeIf { it.isNotBlank() }?.let { q.put("path", it) }
                },
            )
            "get_commit" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/commits/${JsonRest.need(params, "sha")}",
                token,
            )
            "list_branches" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/branches",
                token,
                query = perPage,
            )
            "create_branch" -> {
                val from = JsonRest.opt(params, "from", "sha").ifBlank { defaultSha(needOwner(params), needRepo(params), token) }
                gh(
                    "POST",
                    "/repos/${needOwner(params)}/${needRepo(params)}/git/refs",
                    token,
                    body = JSONObject()
                        .put("ref", "refs/heads/${JsonRest.need(params, "branch")}")
                        .put("sha", from),
                )
            }
            "create_or_update_file" -> putFile(params, token)
            "delete_file" -> {
                val path = JsonRest.need(params, "path")
                val sha = fileSha(needOwner(params), needRepo(params), path, JsonRest.opt(params, "branch"), token)
                gh(
                    "DELETE",
                    "/repos/${needOwner(params)}/${needRepo(params)}/contents/$path",
                    token,
                    body = JSONObject()
                        .put("message", JsonRest.need(params, "message"))
                        .put("sha", sha)
                        .put("branch", JsonRest.need(params, "branch")),
                )
            }
            "create_repository" -> {
                val org = JsonRest.opt(params, "organization", "org")
                val body = JSONObject()
                    .put("name", JsonRest.need(params, "name"))
                    .put("private", params.optBoolean("private", true))
                JsonRest.opt(params, "description").takeIf { it.isNotBlank() }?.let { body.put("description", it) }
                if (params.has("auto_init") || params.has("autoInit")) {
                    body.put("auto_init", params.optBoolean("auto_init", params.optBoolean("autoInit")))
                }
                if (org.isBlank()) gh("POST", "/user/repos", token, body = body)
                else gh("POST", "/orgs/$org/repos", token, body = body)
            }
            "fork_repository" -> {
                val org = JsonRest.opt(params, "organization")
                val body = if (org.isBlank()) null else JSONObject().put("organization", org)
                gh("POST", "/repos/${needOwner(params)}/${needRepo(params)}/forks", token, body = body)
            }
            "list_issues" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/issues",
                token,
                query = perPage.also { q ->
                    JsonRest.opt(params, "state").takeIf { it.isNotBlank() }?.let { q.put("state", it) }
                    JsonRest.opt(params, "labels").takeIf { it.isNotBlank() }?.let { q.put("labels", it) }
                },
            )
            "search_issues" -> {
                var q = JsonRest.opt(params, "query", "q")
                require(q.isNotBlank()) { "query is required" }
                if (owner.isNotBlank() && repo.isNotBlank()) q = "$q repo:$owner/$repo"
                search("issues", q, params, token)
            }
            "issue_read" -> {
                val number = needAny(params, "issue_number", "issueNumber")
                when (JsonRest.opt(params, "method").ifBlank { "get" }) {
                    "get_comments" -> gh(
                        "GET",
                        "/repos/${needOwner(params)}/${needRepo(params)}/issues/$number/comments",
                        token,
                        query = perPage,
                    )
                    "get_labels" -> gh(
                        "GET",
                        "/repos/${needOwner(params)}/${needRepo(params)}/issues/$number/labels",
                        token,
                    )
                    else -> gh("GET", "/repos/${needOwner(params)}/${needRepo(params)}/issues/$number", token)
                }
            }
            "issue_write" -> {
                when (JsonRest.need(params, "method")) {
                    "create" -> gh(
                        "POST",
                        "/repos/${needOwner(params)}/${needRepo(params)}/issues",
                        token,
                        body = issueBody(params, includeNumber = false),
                    )
                    else -> {
                        val number = needAny(params, "issue_number", "issueNumber")
                        gh(
                            "PATCH",
                            "/repos/${needOwner(params)}/${needRepo(params)}/issues/$number",
                            token,
                            body = issueBody(params, includeNumber = false),
                        )
                    }
                }
            }
            "add_issue_comment" -> gh(
                "POST",
                "/repos/${needOwner(params)}/${needRepo(params)}/issues/${needAny(params, "issue_number", "issueNumber")}/comments",
                token,
                body = JSONObject().put("body", JsonRest.need(params, "body")),
            )
            "list_pull_requests" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/pulls",
                token,
                query = perPage.also { q ->
                    JsonRest.opt(params, "state").takeIf { it.isNotBlank() }?.let { q.put("state", it) }
                    JsonRest.opt(params, "base").takeIf { it.isNotBlank() }?.let { q.put("base", it) }
                    JsonRest.opt(params, "head").takeIf { it.isNotBlank() }?.let { q.put("head", it) }
                },
            )
            "search_pull_requests" -> {
                var q = JsonRest.opt(params, "query", "q").ifBlank { "is:pr" }
                if (!q.contains("is:pr")) q = "$q is:pr"
                if (owner.isNotBlank() && repo.isNotBlank()) q = "$q repo:$owner/$repo"
                search("issues", q, params, token)
            }
            "pull_request_read" -> {
                val number = needAny(params, "pullNumber", "pull_number")
                val path = "/repos/${needOwner(params)}/${needRepo(params)}/pulls/$number"
                when (JsonRest.opt(params, "method").ifBlank { "get" }) {
                    "get_diff" -> JSONObject().put(
                        "diff",
                        JsonRest.request(
                            "GET",
                            ApiRoot + path,
                            token,
                            headers = GithubHeaders,
                            accept = "application/vnd.github.diff",
                        ).optString("raw"),
                    )
                    "get_files" -> gh("GET", "$path/files", token, query = perPage)
                    "get_commits" -> gh("GET", "$path/commits", token, query = perPage)
                    "get_comments" -> gh(
                        "GET",
                        "/repos/${needOwner(params)}/${needRepo(params)}/issues/$number/comments",
                        token,
                        query = perPage,
                    )
                    else -> gh("GET", path, token)
                }
            }
            "create_pull_request" -> gh(
                "POST",
                "/repos/${needOwner(params)}/${needRepo(params)}/pulls",
                token,
                body = JSONObject()
                    .put("title", JsonRest.need(params, "title"))
                    .put("head", JsonRest.need(params, "head"))
                    .put("base", JsonRest.need(params, "base"))
                    .put("body", JsonRest.opt(params, "body"))
                    .put("draft", params.optBoolean("draft", false)),
            )
            "update_pull_request" -> {
                val number = needAny(params, "pullNumber", "pull_number")
                val body = JSONObject()
                JsonRest.opt(params, "title").takeIf { it.isNotBlank() }?.let { body.put("title", it) }
                JsonRest.opt(params, "body").takeIf { it.isNotBlank() }?.let { body.put("body", it) }
                JsonRest.opt(params, "state").takeIf { it.isNotBlank() }?.let { body.put("state", it) }
                gh("PATCH", "/repos/${needOwner(params)}/${needRepo(params)}/pulls/$number", token, body = body)
            }
            "merge_pull_request" -> {
                val number = needAny(params, "pullNumber", "pull_number")
                val body = JSONObject()
                JsonRest.opt(params, "merge_method", "mergeMethod").takeIf { it.isNotBlank() }?.let {
                    body.put("merge_method", it)
                }
                gh("PUT", "/repos/${needOwner(params)}/${needRepo(params)}/pulls/$number/merge", token, body = body)
            }
            "list_releases" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/releases",
                token,
                query = perPage,
            )
            "get_latest_release" -> gh(
                "GET",
                "/repos/${needOwner(params)}/${needRepo(params)}/releases/latest",
                token,
            )
            else -> error("Unknown method $method")
        }
    }

    private fun search(kind: String, q: String, params: JSONObject, token: String): JSONObject {
        val query = JSONObject().put("q", q)
        JsonRest.opt(params, "sort").takeIf { it.isNotBlank() }?.let { query.put("sort", it) }
        JsonRest.opt(params, "order").takeIf { it.isNotBlank() }?.let { query.put("order", it) }
        JsonRest.opt(params, "per_page", "perPage").ifBlank { "10" }.let { query.put("per_page", it) }
        return compactSearch(gh("GET", "/search/$kind", token, query = query))
    }

    private fun putFile(params: JSONObject, token: String): JSONObject {
        val owner = needOwner(params)
        val repo = needRepo(params)
        val path = JsonRest.need(params, "path")
        val message = JsonRest.need(params, "message")
        val branch = JsonRest.opt(params, "branch")
        val rawContent = JsonRest.need(params, "content")
        val encoded = if (looksBase64(rawContent)) rawContent else
            Base64.encodeToString(rawContent.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject()
            .put("message", message)
            .put("content", encoded)
        if (branch.isNotBlank()) body.put("branch", branch)
        runCatching { fileSha(owner, repo, path, branch, token) }.getOrNull()?.let { body.put("sha", it) }
        return gh("PUT", "/repos/$owner/$repo/contents/$path", token, body = body)
    }

    private fun fileSha(owner: String, repo: String, path: String, ref: String, token: String): String {
        val query = JSONObject()
        if (ref.isNotBlank()) query.put("ref", ref)
        return gh("GET", "/repos/$owner/$repo/contents/$path", token, query = query).optString("sha")
    }

    private fun defaultSha(owner: String, repo: String, token: String): String {
        val repoJson = gh("GET", "/repos/$owner/$repo", token)
        val branch = repoJson.optString("default_branch").ifBlank { "main" }
        return gh("GET", "/repos/$owner/$repo/git/ref/heads/$branch", token)
            .optJSONObject("object")?.optString("sha").orEmpty()
            .ifBlank { error("Could not resolve default branch SHA") }
    }

    private fun issueBody(params: JSONObject, includeNumber: Boolean): JSONObject {
        val body = JSONObject()
        JsonRest.opt(params, "title").takeIf { it.isNotBlank() }?.let { body.put("title", it) }
        JsonRest.opt(params, "body").takeIf { it.isNotBlank() }?.let { body.put("body", it) }
        JsonRest.opt(params, "state").takeIf { it.isNotBlank() }?.let { body.put("state", it) }
        val labels = params.optJSONArray("labels")
        if (labels != null) body.put("labels", labels)
        val assignees = params.optJSONArray("assignees")
        if (assignees != null) body.put("assignees", assignees)
        if (includeNumber) {
            JsonRest.opt(params, "issue_number").takeIf { it.isNotBlank() }?.let { body.put("issue_number", it) }
        }
        return body
    }

    private fun compactSearch(json: JSONObject): JSONObject {
        val items = json.optJSONArray("items") ?: return json
        val compact = JSONArray()
        val limit = minOf(items.length(), 10)
        for (index in 0 until limit) {
            compact.put(compactItem(items.optJSONObject(index) ?: continue))
        }
        return JSONObject()
            .put("total_count", json.optInt("total_count"))
            .put("items", compact)
    }

    private fun compactItem(item: JSONObject): JSONObject {
        val out = JSONObject()
        listOf(
            "id", "name", "full_name", "html_url", "url", "description", "language",
            "stargazers_count", "login", "title", "number", "state", "path", "sha",
            "draft", "merged", "body",
        ).forEach { key ->
            if (item.has(key) && item.opt(key) != JSONObject.NULL) out.put(key, item.opt(key))
        }
        item.optJSONObject("owner")?.optString("login")?.takeIf { it.isNotBlank() }?.let {
            out.put("owner", it)
        }
        val body = out.optString("body")
        if (body.length > 400) out.put("body", body.take(399) + "…")
        return out
    }

    private fun compactContents(json: JSONObject): JSONObject {
        json.optJSONArray("items")?.let { list ->
            val compact = JSONArray()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                compact.put(
                    JSONObject()
                        .put("name", item.optString("name"))
                        .put("path", item.optString("path"))
                        .put("type", item.optString("type"))
                        .put("size", item.optInt("size")),
                )
            }
            return JSONObject().put("items", compact)
        }
        val encoding = json.optString("encoding")
        val content = json.optString("content").replace("\n", "")
        val decoded = if (encoding == "base64" && content.isNotBlank()) {
            runCatching {
                String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrNull()
        } else {
            null
        }
        val text = (decoded ?: content)
        return JSONObject()
            .put("name", json.optString("name"))
            .put("path", json.optString("path"))
            .put("sha", json.optString("sha"))
            .put("type", json.optString("type"))
            .put("html_url", json.optString("html_url"))
            .put("content", if (text.length > 6_000) text.take(5_999) + "…" else text)
    }

    private fun gh(
        verb: String,
        path: String,
        token: String,
        query: JSONObject = JSONObject(),
        body: JSONObject? = null,
    ): JSONObject = JsonRest.request(
        verb = verb,
        url = ApiRoot + path,
        token = token,
        query = query,
        body = body,
        headers = GithubHeaders,
        accept = "application/vnd.github+json",
    )

    private fun needOwner(params: JSONObject): String = JsonRest.need(params, "owner")
    private fun needRepo(params: JSONObject): String = JsonRest.need(params, "repo")

    private fun needAny(params: JSONObject, vararg keys: String): String {
        val value = JsonRest.opt(params, *keys)
        require(value.isNotBlank()) { "${keys.first()} is required" }
        return value
    }

    private fun looksBase64(value: String): Boolean {
        if (value.length < 8 || value.length % 4 != 0) return false
        return value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }
}
