package kira.ditto.data

import android.content.Context
import kira.ditto.AetherApplication
import kira.ditto.runtime.AlpineRuntime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class KaggleCliRuntime(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val secrets = HostSecretStore(appContext)
    private val book = KaggleAccountBook(secrets)

    fun handle(manifest: kira.ditto.upa.UpaManifest, name: String, arguments: JSONObject): JSONObject {
        return when (name) {
            "kaggle.accounts" -> accountsPayload(arguments)
            "kaggle.quota" -> quotaPayload(arguments)
            "kaggle" -> runCli(arguments.optString("command").ifBlank { arguments.optString("args") })
            else -> error("Unknown kaggle tool: $name")
        }
    }

    fun accountsPublic(): JSONObject = accountsPayload(JSONObject())

    fun addAccount(username: String, token: String): JSONObject {
        book.upsert(username, token, makeActive = true)
        return accountsPayload(JSONObject()).put("saved", true)
    }

    fun listAccounts() = book.list()

    fun activeAccountId() = book.activeId()

    fun setActiveAccount(id: String) = book.setActive(id)

    fun toggleSelectedAccount(id: String) = book.toggleSelected(id)

    fun removeAccount(id: String) = book.remove(id)

    private fun accountsPayload(arguments: JSONObject): JSONObject {
        val action = arguments.optString("action").ifBlank { "list" }
        when (action) {
            "add", "save" -> {
                book.upsert(arguments.optString("username"), arguments.optString("token"))
            }
            "remove", "delete" -> book.remove(arguments.optString("id").ifBlank { arguments.optString("username") })
            "activate", "switch" -> book.setActive(arguments.optString("id"))
            "select" -> {
                val ids = arguments.optJSONArray("ids") ?: JSONArray()
                book.setSelected((0 until ids.length()).map { ids.optString(it) })
            }
        }
        val accounts = book.list()
        val active = book.activeId()
        if (accounts.isEmpty()) {
            return JSONObject()
                .put("needLogin", true)
                .put("title", "Kaggle")
                .put("hint", "先在插件页添加账号（用户名 + Token）")
        }
        val payload = JSONObject()
            .put("accounts", true)
            .put("title", "Kaggle 账号")
            .put("activeId", active)
            .put("count", "${accounts.size} 个账号")
            .put(
                "list",
                JSONArray().apply { accounts.forEach { put(it.toPublicJson().put("active", it.id == active)) } },
            )
        accounts.getOrNull(0)?.let {
            payload.put("account0_name", it.username)
            payload.put("account0_meta", if (it.id == active) "当前" else if (it.selected) "已选" else "")
        }
        accounts.getOrNull(1)?.let {
            payload.put("account1_name", it.username)
            payload.put("account1_meta", if (it.id == active) "当前" else if (it.selected) "已选" else "")
        }
        accounts.getOrNull(2)?.let {
            payload.put("account2_name", it.username)
            payload.put("account2_meta", if (it.id == active) "当前" else if (it.selected) "已选" else "")
        }
        return payload
    }

    private fun quotaPayload(arguments: JSONObject): JSONObject {
        val selected = book.list().filter { it.selected }.ifEmpty { book.list() }
        if (selected.isEmpty()) {
            return JSONObject()
                .put("needLogin", true)
                .put("title", "Kaggle")
                .put("hint", "先在插件页添加 Kaggle 账号（用户名 + Token）")
        }
        val rows = JSONArray()
        selected.forEach { account ->
            val token = book.tokenFor(account.id) ?: return@forEach
            val raw = runKaggle(account.username, token, "quota")
            rows.put(
                JSONObject()
                    .put("username", account.username)
                    .put("quota", HostSecretStore.redact(raw.take(4000))),
            )
        }
        val payload = JSONObject()
            .put("quota", true)
            .put("title", "GPU / TPU 额度")
            .put("count", "${rows.length()} 个账号")
            .put("rows", rows)
        rows.optJSONObject(0)?.let {
            payload.put("quota0_name", it.optString("username"))
            payload.put("quota0_meta", it.optString("quota").lineSequence().firstOrNull().orEmpty())
        }
        rows.optJSONObject(1)?.let {
            payload.put("quota1_name", it.optString("username"))
            payload.put("quota1_meta", it.optString("quota").lineSequence().firstOrNull().orEmpty())
        }
        return payload
    }

    private fun runCli(command: String): JSONObject {
        val argv = command.trim().removePrefix("kaggle").trim()
        require(argv.isNotBlank()) { "command required, e.g. competitions list" }
        denyUnsafe(argv)
        val active = book.activeId().ifBlank { book.list().firstOrNull()?.id.orEmpty() }
        val username = book.list().firstOrNull { it.id == active }?.username
            ?: error("先在插件页添加 Kaggle 账号")
        val token = book.tokenFor(active) ?: error("先在插件页添加 Kaggle 账号")
        val output = runKaggle(username, token, argv)
        return JSONObject()
            .put("ok", true)
            .put("command", "kaggle $argv")
            .put("account", username)
            .put("output", HostSecretStore.redact(output.take(8000)))
            .put("title", "kaggle $argv")
    }

    private fun denyUnsafe(argv: String) {
        val lower = argv.lowercase()
        if (lower.startsWith("auth print-access-token") || lower.contains("print-access-token")) {
            error("拒绝打印 access token")
        }
        if (Regex("""\b(competitions|c|datasets|d)\s+download\b""").containsMatchIn(lower)) {
            val allowsSample = lower.contains("sample_submission") || lower.contains("kernel")
            if (!allowsSample) {
                error("禁止本机下载比赛/数据集。请在 Kaggle Notebook 里读数据；只允许拉取 sample_submission 或 kernel 脚本。")
            }
        }
        if (lower.contains("kaggle.json") || lower.contains("access_token")) {
            error("拒绝读取凭据文件")
        }
    }

    private fun runKaggle(username: String, token: String, argv: String): String {
        val alpine = alpineRuntime() ?: error("请先初始化 Alpine，并在其中安装 kaggle CLI")
        return runBlocking {
            val dir = alpine.resolveGuestPath("/root/.kaggle")
            dir.mkdirs()
            val creds = FileWithMode(dir, "kaggle.json")
            creds.writeText("""{"username":${JSONObject.quote(username)},"key":${JSONObject.quote(token)}}""")
            creds.setReadable(false, false)
            creds.setReadable(true, true)
            creds.setWritable(false, false)
            creds.setWritable(true, true)
            val quoted = argv.replace("'", "'\"'\"'")
            val raw = alpine.executeCommand(
                command = "PYTHONUTF8=1 kaggle $quoted",
                workingDirectory = alpine.homeDirectory,
                awaitTimeoutMillis = 90_000L,
            )
            parseAlpineOutput(raw)
        }
    }

    private fun parseAlpineOutput(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json != null) {
            val stdout = json.optString("stdout")
            val stderr = json.optString("stderr")
            val errmsg = json.optString("errmsg")
            val ok = json.optBoolean("ok", json.optInt("exitCode", 0) == 0)
            val text = listOf(stdout, stderr, errmsg).filter { it.isNotBlank() }.joinToString("\n")
            val redacted = HostSecretStore.redact(text.ifBlank { raw })
            if (!ok) error(redacted.ifBlank { "kaggle failed" })
            return redacted
        }
        return HostSecretStore.redact(raw)
    }

    private fun alpineRuntime(): AlpineRuntime? =
        (appContext as? AetherApplication)?.runtime?.alpineRuntime

    private fun FileWithMode(dir: java.io.File, name: String) = java.io.File(dir, name)
}
