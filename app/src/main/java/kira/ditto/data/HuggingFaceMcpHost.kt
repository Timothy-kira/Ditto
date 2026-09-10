package kira.ditto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object HuggingFaceMcpHost {
    private const val ApiRoot = "https://huggingface.co"

    fun execute(context: Context, name: String, arguments: JSONObject): String {
        val tool = HuggingFaceMcp.canonicalToolName(name)
        if (tool.isBlank()) return JsonRest.errorJson("unknown_tool", "Unknown Hugging Face tool: $name")
        val store = HostSecretStore(context.applicationContext)
        val token = HuggingFaceAuth.snapshot(store).token
        if (token.isBlank()) {
            runCatching { HuggingFaceAuth.openConsole(context) }
            return JsonRest.missingToken(
                "Hugging Face",
                "Create an access token at huggingface.co/settings/tokens and paste it in Settings.",
            )
        }
        return runCatching { dispatch(tool, arguments, token).put("ok", true).toString() }
            .getOrElse { error -> JsonRest.errorJson("huggingface_api", error.message ?: "Hugging Face request failed") }
    }

    internal fun dispatch(tool: String, arguments: JSONObject, token: String): JSONObject {
        if (tool == HuggingFaceMcp.CatalogTool) {
            return JSONObject().put("catalog", HuggingFaceMcp.catalogText())
        }
        val method = arguments.optString("method").trim()
        require(method.isNotBlank()) { "method is required" }
        if (method.equals("schema", ignoreCase = true)) {
            val target = JsonRest.paramsObject(arguments).optString("method").ifBlank {
                arguments.optString("target").ifBlank { arguments.optString("name") }
            }
            return JSONObject().put("schema", HuggingFaceMcp.schemaText(target))
        }
        require(HuggingFaceMcp.Spec.knownMethod(method)) { "Unknown method $method. Use huggingface_catalog." }
        return call(method, JsonRest.paramsObject(arguments), token)
    }

    internal fun call(method: String, params: JSONObject, token: String): JSONObject {
        val resolved = if (method == "hf_fs") expandHfFs(params) else method to params
        return when (resolved.first) {
            "whoami" -> hf("GET", "/api/whoami-v2", token)
            "search_models" -> compactRepos(listHub("/api/models", resolved.second, token))
            "search_datasets" -> compactRepos(listHub("/api/datasets", resolved.second, token))
            "search_spaces" -> compactRepos(listHub("/api/spaces", resolved.second, token))
            "search_papers" -> compactPapers(
                hf(
                    "GET",
                    "/api/papers/search",
                    token,
                    query = JSONObject()
                        .put("q", JsonRest.opt(resolved.second, "q", "search").also { q ->
                            require(q.isNotBlank()) { "q is required" }
                        })
                        .put("limit", JsonRest.opt(resolved.second, "limit").ifBlank { "10" }),
                ),
            )
            "model_info" -> compactRepo(hf("GET", "/api/models/${JsonRest.need(resolved.second, "repo_id")}", token))
            "dataset_info" -> compactRepo(hf("GET", "/api/datasets/${JsonRest.need(resolved.second, "repo_id")}", token))
            "space_info" -> compactRepo(hf("GET", "/api/spaces/${JsonRest.need(resolved.second, "repo_id")}", token))
            "paper_info" -> hf("GET", "/api/papers/${JsonRest.need(resolved.second, "paper_id")}", token)
            "list_files" -> {
                val repoId = JsonRest.need(params, "repo_id")
                val kind = when (JsonRest.opt(params, "repo_type", "type")) {
                    "dataset", "datasets" -> "datasets"
                    "space", "spaces" -> "spaces"
                    else -> "models"
                }
                val revision = JsonRest.opt(params, "revision").ifBlank { "main" }
                compactTree(hf("GET", "/api/$kind/$repoId/tree/$revision", token))
            }
            else -> error("Unknown method ${resolved.first}")
        }
    }

    private fun expandHfFs(params: JSONObject): Pair<String, JSONObject> {
        val action = JsonRest.opt(params, "action").ifBlank { "search" }
        val type = JsonRest.opt(params, "type", "repo_type").ifBlank { "model" }
        val query = JsonRest.opt(params, "query", "search", "q")
        val repoId = JsonRest.opt(params, "repo_id", "id")
        return when {
            action == "info" || action == "get" -> when (type) {
                "dataset", "datasets" -> "dataset_info" to JSONObject().put("repo_id", repoId)
                "space", "spaces" -> "space_info" to JSONObject().put("repo_id", repoId)
                "paper", "papers" -> "paper_info" to JSONObject().put("paper_id", repoId.ifBlank { query })
                else -> "model_info" to JSONObject().put("repo_id", repoId)
            }
            type == "paper" || type == "papers" -> "search_papers" to JSONObject().put("q", query)
            type == "dataset" || type == "datasets" -> "search_datasets" to JSONObject().put("search", query)
            type == "space" || type == "spaces" -> "search_spaces" to JSONObject().put("search", query)
            else -> "search_models" to JSONObject().put("search", query)
        }
    }

    private fun listHub(path: String, params: JSONObject, token: String): JSONObject {
        val query = JSONObject()
        JsonRest.opt(params, "search", "query", "q").takeIf { it.isNotBlank() }?.let { query.put("search", it) }
        JsonRest.opt(params, "author").takeIf { it.isNotBlank() }?.let { query.put("author", it) }
        JsonRest.opt(params, "filter").takeIf { it.isNotBlank() }?.let { query.put("filter", it) }
        JsonRest.opt(params, "sort").ifBlank { "downloads" }.let { query.put("sort", it) }
        query.put("direction", JsonRest.opt(params, "direction").ifBlank { "-1" })
        query.put("limit", JsonRest.opt(params, "limit").ifBlank { "10" })
        return hf("GET", path, token, query = query)
    }

    private fun compactRepos(json: JSONObject): JSONObject {
        val items = json.optJSONArray("items") ?: JSONArray().put(json)
        val compact = JSONArray()
        val limit = minOf(items.length(), 10)
        for (index in 0 until limit) {
            compact.put(compactRepo(items.optJSONObject(index) ?: continue))
        }
        return JSONObject().put("items", compact)
    }

    private fun compactRepo(item: JSONObject): JSONObject {
        val id = item.optString("id").ifBlank { item.optString("modelId") }
        return JSONObject()
            .put("id", id)
            .put("downloads", item.opt("downloads"))
            .put("likes", item.opt("likes"))
            .put("pipeline_tag", item.optString("pipeline_tag"))
            .put("library_name", item.optString("library_name"))
            .put("lastModified", item.optString("lastModified"))
            .put("url", if (id.isBlank()) "" else "https://huggingface.co/$id")
    }

    private fun compactPapers(json: JSONObject): JSONObject {
        val items = json.optJSONArray("items") ?: json.optJSONArray("papers") ?: JSONArray()
        val compact = JSONArray()
        val limit = minOf(items.length(), 10)
        for (index in 0 until limit) {
            val item = items.optJSONObject(index) ?: continue
            val paper = item.optJSONObject("paper") ?: item
            compact.put(
                JSONObject()
                    .put("id", paper.optString("id"))
                    .put("title", paper.optString("title"))
                    .put("summary", paper.optString("summary").take(280))
                    .put("url", "https://huggingface.co/papers/${paper.optString("id")}"),
            )
        }
        return JSONObject().put("items", compact)
    }

    private fun compactTree(json: JSONObject): JSONObject {
        val items = json.optJSONArray("items") ?: JSONArray()
        val compact = JSONArray()
        val limit = minOf(items.length(), 40)
        for (index in 0 until limit) {
            val item = items.optJSONObject(index) ?: continue
            compact.put(
                JSONObject()
                    .put("path", item.optString("path"))
                    .put("type", item.optString("type"))
                    .put("size", item.opt("size")),
            )
        }
        return JSONObject().put("items", compact)
    }

    private fun hf(
        verb: String,
        path: String,
        token: String,
        query: JSONObject = JSONObject(),
    ): JSONObject = JsonRest.request(
        verb = verb,
        url = ApiRoot + path,
        token = token,
        query = query,
    )
}
