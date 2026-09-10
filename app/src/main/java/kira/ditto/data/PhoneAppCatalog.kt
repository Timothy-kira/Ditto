package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PhoneAppPrimitiveKind(val storageValue: String, val toolSuffix: String) {
    Search("search", "search"),
    OpenItem("open_item", "open_item"),
    ExtractContent("extract_content", "extract_content"),
    Like("like", "like"),
    Comment("comment", "comment"),
    Follow("follow", "follow"),
    Collect("collect", "collect"),
    Compose("compose", "compose"),
    OpenApp("open_app", "open"),
    Flow("flow", "flow");

    companion object {
        fun fromStorage(value: String): PhoneAppPrimitiveKind =
            entries.firstOrNull { it.storageValue == value || it.toolSuffix == value }
                ?: Search
    }
}

data class PhoneAppPrimitive(
    val kind: PhoneAppPrimitiveKind,
    val toolName: String,
    val title: String,
    val description: String,
    val sopId: String,
    val goal: String,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val confidence: Double = 0.0,
    val promoted: Boolean = false,
    val provenance: String = AgentModeSopProvenance.KimiAgent.storageValue,
    val acceptsQuery: Boolean = false,
    val guiLabels: List<String> = emptyList(),
) {
    val isReady: Boolean get() = promoted || successCount >= ReadyAfterSuccesses

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.storageValue)
        .put("tool_name", toolName)
        .put("title", title)
        .put("description", description)
        .put("sop_id", sopId)
        .put("goal", goal)
        .put("success_count", successCount)
        .put("failure_count", failureCount)
        .put("confidence", confidence)
        .put("promoted", promoted)
        .put("provenance", provenance)
        .put("accepts_query", acceptsQuery)
        .put("gui_labels", JSONArray(guiLabels))

    companion object {
        const val ReadyAfterSuccesses = AgentModeSopStore.PromoteAfterSuccesses

        fun fromJson(json: JSONObject): PhoneAppPrimitive = PhoneAppPrimitive(
            kind = PhoneAppPrimitiveKind.fromStorage(json.optString("kind")),
            toolName = json.optString("tool_name"),
            title = json.optString("title"),
            description = json.optString("description"),
            sopId = json.optString("sop_id"),
            goal = json.optString("goal"),
            successCount = json.optInt("success_count", 1),
            failureCount = json.optInt("failure_count"),
            confidence = json.optDouble("confidence"),
            promoted = json.optBoolean("promoted") ||
                json.optInt("success_count", 1) >= ReadyAfterSuccesses,
            provenance = json.optString("provenance")
                .ifBlank { AgentModeSopProvenance.LegacyOperator.storageValue },
            acceptsQuery = json.optBoolean("accepts_query"),
            guiLabels = json.optJSONArray("gui_labels").toStringList(),
        )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}

data class PhoneAppCatalog(
    val packageName: String,
    val appName: String,
    val skillId: String = "",
    val primitives: List<PhoneAppPrimitive> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun readyPrimitives(): List<PhoneAppPrimitive> = primitives.filter { it.isReady && it.toolName.isNotBlank() }

    fun toJson(): JSONObject = JSONObject()
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("skill_id", skillId)
        .put("updated_at_millis", updatedAtMillis)
        .put("primitives", JSONArray().apply { primitives.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): PhoneAppCatalog {
            val items = json.optJSONArray("primitives") ?: JSONArray()
            return PhoneAppCatalog(
                packageName = json.optString("package_name"),
                appName = json.optString("app_name"),
                skillId = json.optString("skill_id"),
                updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis()),
                primitives = buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        add(PhoneAppPrimitive.fromJson(item))
                    }
                },
            )
        }
    }
}

object PhoneAppPrimitiveClassifier {
    fun classify(
        goal: String,
        steps: List<AgentModeSopStep>,
        packageName: String,
        appName: String,
        sopId: String,
        successCount: Int = 1,
        failureCount: Int = 0,
        confidence: Double = 0.0,
        promoted: Boolean = false,
        provenance: AgentModeSopProvenance = AgentModeSopProvenance.KimiAgent,
    ): List<PhoneAppPrimitive> {
        val segments = if (steps.isEmpty()) {
            emptyList()
        } else {
            GuiFlowSegmenter.segment(steps).ifEmpty {
                listOf(
                    GuiFlowSegment(
                        sceneKey = GuiFlowSegmenter.sceneKey(steps),
                        steps = steps,
                        methodOnly = steps.none {
                            it.kind == TeachingTraceDistiller.KindInstance ||
                                it.inputSlot == TeachingTraceDistiller.PickSlot
                        },
                        slots = steps.map { it.inputSlot }.filter { it.isNotBlank() }.distinct(),
                    ),
                )
            }
        }
        if (segments.isEmpty()) {
            val kind = kindsFrom(goal, emptyList()).firstOrNull() ?: PhoneAppPrimitiveKind.OpenApp
            return listOf(primitiveFor(kind, kind.toolSuffix, goal, steps, packageName, appName, sopId, successCount, failureCount, confidence, promoted, provenance))
        }
        return segments.map { segment ->
            val kind = kindFromScene(segment.sceneKey, goal)
            val segmentSopId = if (segments.size == 1) sopId else "$sopId-${segment.sceneKey}"
            primitiveFor(
                kind = kind,
                sceneKey = segment.sceneKey,
                goal = goal,
                steps = segment.steps,
                packageName = packageName,
                appName = appName,
                sopId = segmentSopId,
                successCount = successCount,
                failureCount = failureCount,
                confidence = confidence,
                promoted = promoted || segment.methodOnly,
                provenance = provenance,
            )
        }
    }

    private fun primitiveFor(
        kind: PhoneAppPrimitiveKind,
        sceneKey: String,
        goal: String,
        steps: List<AgentModeSopStep>,
        packageName: String,
        appName: String,
        sopId: String,
        successCount: Int,
        failureCount: Int,
        confidence: Double,
        promoted: Boolean,
        provenance: AgentModeSopProvenance,
    ): PhoneAppPrimitive {
        val slug = PhoneAppCatalogStore.appSlug(packageName, appName)
        val display = appName.ifBlank { slug }
        val acceptsQuery = kind == PhoneAppPrimitiveKind.Search ||
            kind == PhoneAppPrimitiveKind.Comment ||
            kind == PhoneAppPrimitiveKind.Compose ||
            steps.any { it.inputSlot == TeachingTraceDistiller.QuerySlot }
        return PhoneAppPrimitive(
            kind = kind,
            toolName = "${slug}_${sceneKey.ifBlank { kind.toolSuffix }}",
            title = titleFor(display, kind),
            description = descriptionFor(display, kind),
            sopId = sopId,
            goal = goal,
            successCount = successCount,
            failureCount = failureCount,
            confidence = confidence,
            promoted = promoted,
            provenance = provenance.storageValue,
            acceptsQuery = acceptsQuery,
            guiLabels = steps.map(AgentModeSopStep::guiLabel)
                .filter(String::isNotBlank)
                .distinct()
                .take(12),
        )
    }

    fun kindFromScene(sceneKey: String, goal: String): PhoneAppPrimitiveKind {
        return when (sceneKey) {
            "search" -> PhoneAppPrimitiveKind.Search
            "like" -> PhoneAppPrimitiveKind.Like
            "open_item" -> PhoneAppPrimitiveKind.OpenItem
            "comment" -> PhoneAppPrimitiveKind.Comment
            "follow" -> PhoneAppPrimitiveKind.Follow
            "collect" -> PhoneAppPrimitiveKind.Collect
            "compose" -> PhoneAppPrimitiveKind.Compose
            "extract_content" -> PhoneAppPrimitiveKind.ExtractContent
            "open", "open_app" -> PhoneAppPrimitiveKind.OpenApp
            "confirm", "flow" -> kindsFrom(goal, emptyList()).firstOrNull() ?: PhoneAppPrimitiveKind.Flow
            else -> kindsFrom(goal, emptyList()).firstOrNull() ?: PhoneAppPrimitiveKind.Flow
        }
    }

    fun kindsFrom(goal: String, steps: List<AgentModeSopStep>): List<PhoneAppPrimitiveKind> {
        val text = goal.lowercase(Locale.ROOT)
        val kinds = linkedSetOf<PhoneAppPrimitiveKind>()
        if (matches(text, "搜索", "search", "查找", "搜一下")) kinds += PhoneAppPrimitiveKind.Search
        if (matches(text, "帖子", "笔记", "视频", "open post", "点开", "打开帖", "点击帖")) {
            kinds += PhoneAppPrimitiveKind.OpenItem
        }
        if (matches(text, "抓取内容", "提取内容", "读取内容", "读取帖子", "提取帖子", "extract content", "read content", "scrape")) {
            kinds += PhoneAppPrimitiveKind.ExtractContent
        }
        if (matches(text, "点赞", "like")) kinds += PhoneAppPrimitiveKind.Like
        if (matches(text, "评论", "comment")) kinds += PhoneAppPrimitiveKind.Comment
        if (matches(text, "关注", "follow")) kinds += PhoneAppPrimitiveKind.Follow
        if (matches(text, "收藏", "collect", "star")) kinds += PhoneAppPrimitiveKind.Collect
        if (matches(text, "发布", "发帖", "发笔记", "compose", "post")) kinds += PhoneAppPrimitiveKind.Compose
        val typed = steps.any { PhoneUiMcp.normalizeAction(it.action) == "text" && it.text.isNotBlank() }
        if (typed && PhoneAppPrimitiveKind.Search !in kinds) kinds += PhoneAppPrimitiveKind.Search
        if (kinds.isEmpty()) kinds += PhoneAppPrimitiveKind.OpenApp
        return kinds.toList()
    }

    private fun matches(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it.lowercase(Locale.ROOT)) }

    private fun titleFor(app: String, kind: PhoneAppPrimitiveKind): String = when (kind) {
        PhoneAppPrimitiveKind.Search -> "搜索$app"
        PhoneAppPrimitiveKind.OpenItem -> "打开${app}帖子"
        PhoneAppPrimitiveKind.ExtractContent -> "读取${app}内容"
        PhoneAppPrimitiveKind.Like -> "${app}点赞"
        PhoneAppPrimitiveKind.Comment -> "${app}评论"
        PhoneAppPrimitiveKind.Follow -> "${app}关注"
        PhoneAppPrimitiveKind.Collect -> "${app}收藏"
        PhoneAppPrimitiveKind.Compose -> "发布到$app"
        PhoneAppPrimitiveKind.OpenApp -> "打开$app"
        PhoneAppPrimitiveKind.Flow -> "操作$app"
    }

    private fun descriptionFor(app: String, kind: PhoneAppPrimitiveKind): String = when (kind) {
        PhoneAppPrimitiveKind.Search -> "Search inside $app. Pass text=query. Replays the distilled SOP."
        PhoneAppPrimitiveKind.OpenItem -> "Open a post/note currently visible in $app. Pass item= the visible label."
        PhoneAppPrimitiveKind.ExtractContent -> "Open and return the final screenshot and accessibility snapshot so Kimi can extract visible content from $app."
        PhoneAppPrimitiveKind.Like -> "Like the current item in $app."
        PhoneAppPrimitiveKind.Comment -> "Comment in $app. Pass text=comment body."
        PhoneAppPrimitiveKind.Follow -> "Follow the current account in $app."
        PhoneAppPrimitiveKind.Collect -> "Collect/save the current item in $app."
        PhoneAppPrimitiveKind.Compose -> "Compose a new post in $app. Pass text=body."
        PhoneAppPrimitiveKind.OpenApp -> "Open $app on the Agent Mode virtual display."
        PhoneAppPrimitiveKind.Flow -> "Replay a distilled method segment in $app."
    }
}

class PhoneAppCatalogStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    fun load(packageName: String): PhoneAppCatalog? = lock.withLock {
        val file = fileFor(packageName) ?: return@withLock null
        if (!file.isFile) return@withLock null
        runCatching { PhoneAppCatalog.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun loadAll(): List<PhoneAppCatalog> = lock.withLock {
        if (!root.isDirectory) return@withLock emptyList()
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file -> runCatching { PhoneAppCatalog.fromJson(JSONObject(file.readText())) }.getOrNull() }
    }

    fun merge(
        packageName: String,
        appName: String,
        incoming: List<PhoneAppPrimitive>,
        skillId: String = "",
    ): PhoneAppCatalog = lock.withLock {
        val existing = fileFor(packageName)
            ?.takeIf { it.isFile }
            ?.let { runCatching { PhoneAppCatalog.fromJson(JSONObject(it.readText())) }.getOrNull() }
        val byTool = existing?.primitives.orEmpty().associateBy { it.toolName }.toMutableMap()
        incoming.forEach { primitive ->
            val previous = byTool[primitive.toolName]
            byTool[primitive.toolName] = if (previous == null) {
                primitive
            } else {
                previous.copy(
                    sopId = primitive.sopId.ifBlank { previous.sopId },
                    goal = primitive.goal.ifBlank { previous.goal },
                    title = primitive.title.ifBlank { previous.title },
                    description = primitive.description.ifBlank { previous.description },
                    successCount = maxOf(previous.successCount + 1, primitive.successCount),
                    failureCount = maxOf(previous.failureCount, primitive.failureCount),
                    confidence = primitive.confidence.takeIf { it > 0.0 } ?: previous.confidence,
                    promoted = previous.promoted || primitive.promoted,
                    provenance = if (
                        primitive.provenance == AgentModeSopProvenance.UserTeaching.storageValue
                    ) {
                        primitive.provenance
                    } else {
                        previous.provenance
                    },
                    acceptsQuery = previous.acceptsQuery || primitive.acceptsQuery,
                    guiLabels = (previous.guiLabels + primitive.guiLabels).distinct().take(12),
                )
            }
        }
        val catalog = PhoneAppCatalog(
            packageName = packageName,
            appName = appName.ifBlank { existing?.appName.orEmpty() },
            skillId = skillId.ifBlank { existing?.skillId.orEmpty() },
            primitives = byTool.values.sortedBy { it.toolName },
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeLocked(catalog)
        catalog
    }

    fun save(catalog: PhoneAppCatalog) = lock.withLock { writeLocked(catalog) }

    fun findReadyTool(toolName: String): Pair<PhoneAppCatalog, PhoneAppPrimitive>? {
        val trimmed = toolName.trim().lowercase(Locale.ROOT)
        if (trimmed.isBlank()) return null
        return loadAll().firstNotNullOfOrNull { catalog ->
            val primitive = catalog.readyPrimitives().firstOrNull { it.toolName.equals(trimmed, ignoreCase = true) }
            if (primitive != null) catalog to primitive else null
        }
    }

    fun findReadySop(sopId: String): Pair<PhoneAppCatalog, PhoneAppPrimitive>? {
        val trimmed = sopId.trim()
        if (trimmed.isBlank()) return null
        return loadAll().firstNotNullOfOrNull { catalog ->
            val primitive = catalog.readyPrimitives().firstOrNull { primitive ->
                primitive.sopId == trimmed ||
                    // Multi-segment promotion rewrites sop_id to "$sopId-$sceneKey"; a stale
                    // catalog entry may still hold the base id (or vice versa).
                    (trimmed.contains('-') && primitive.sopId == trimmed.substringBeforeLast('-')) ||
                    primitive.sopId.startsWith("$trimmed-")
            }
            if (primitive != null) catalog to primitive else null
        }
    }

    fun recallReady(
        task: String,
        packageName: String = "",
        limit: Int = 6,
    ): List<Pair<PhoneAppCatalog, PhoneAppPrimitive>> {
        val normalizedTask = AgentModeSopStore.normalizeGoal(task)
        if (normalizedTask.isBlank()) return emptyList()
        val requestedPackage = packageName.trim()
        return loadAll()
            .flatMap { catalog -> catalog.readyPrimitives().map { primitive -> catalog to primitive } }
            .filter { (catalog, _) ->
                requestedPackage.isBlank() || catalog.packageName.equals(requestedPackage, ignoreCase = true)
            }
            .map { pair -> pair to recallScore(normalizedTask, pair.first, pair.second) }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 20))
            .map { it.first }
    }

    fun operatorHint(packageName: String, task: String): String {
        val catalog = load(packageName) ?: matchByTask(task) ?: return ""
        val ready = catalog.readyPrimitives()
        if (ready.isEmpty()) return ""
        return buildString {
            append("Distilled ").append(catalog.appName.ifBlank { catalog.packageName })
            append(" MCP tools: ")
            append(ready.joinToString(", ") { primitive ->
                if (primitive.acceptsQuery) "${primitive.toolName}(text=...)" else primitive.toolName
            })
            append(". Prefer these over raw taps when they match the task.")
        }
    }

    private fun matchByTask(task: String): PhoneAppCatalog? {
        val key = AgentModeSopStore.normalizeGoal(task)
        if (key.isBlank()) return null
        return loadAll().firstOrNull { catalog ->
            catalog.primitives.any { AgentModeSopStore.normalizeGoal(it.goal) == key }
        }
    }

    private fun recallScore(
        query: String,
        catalog: PhoneAppCatalog,
        primitive: PhoneAppPrimitive,
    ): Double {
        val haystack = AgentModeSopStore.normalizeGoal(
            listOf(
                catalog.appName,
                catalog.packageName,
                primitive.goal,
                primitive.title,
                primitive.kind.storageValue,
                primitive.toolName,
            ).joinToString(" "),
        )
        val normalizedGoal = AgentModeSopStore.normalizeGoal(primitive.goal)
        if (haystack.contains(query) || (normalizedGoal.isNotBlank() && query.contains(normalizedGoal))) {
            return 1.0 + primitive.confidence
        }
        val queryTokens = recallTokens(query)
        val haystackTokens = recallTokens(haystack)
        if (queryTokens.isEmpty() || haystackTokens.isEmpty()) return 0.0
        val overlap = queryTokens.intersect(haystackTokens).size.toDouble() / queryTokens.size
        val appBonus = if (
            catalog.appName.isNotBlank() && query.contains(catalog.appName.lowercase(Locale.ROOT))
        ) 0.5 else 0.0
        return overlap + appBonus + primitive.confidence * 0.1
    }

    private fun recallTokens(value: String): Set<String> = buildSet {
        Regex("[a-z0-9]+|[\\u4e00-\\u9fff]+").findAll(value).forEach { match ->
            val token = match.value
            add(token)
            if (token.any { it.code in 0x4E00..0x9FFF } && token.length > 1) {
                token.windowed(2).forEach(::add)
            }
        }
    }

    private fun writeLocked(catalog: PhoneAppCatalog) {
        root.mkdirs()
        val file = fileFor(catalog.packageName) ?: return
        file.writeText(catalog.toJson().toString())
    }

    private fun fileFor(packageName: String): File? {
        val slug = packageName.trim().ifBlank { return null }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return File(root, "$slug.json")
    }

    companion object {
        private val KnownSlugs = mapOf(
            "com.xingin.xhs" to "xhs",
            "com.tencent.mm" to "wechat",
            "com.ss.android.ugc.aweme" to "douyin",
            "com.smile.gifmaker" to "kuaishou",
            "com.taobao.taobao" to "taobao",
            "com.eg.android.AlipayGphone" to "alipay",
            "com.sina.weibo" to "weibo",
            "com.ss.android.article.news" to "toutiao",
        )

        fun appSlug(packageName: String, appName: String): String {
            KnownSlugs[packageName.trim()]?.let { return it }
            val fromApp = appName.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "")
            if (fromApp.length >= 2 && fromApp.matches(Regex("[a-z0-9]+"))) return fromApp.take(12)
            val fromPackage = packageName.substringAfterLast('.').lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "")
            return fromPackage.ifBlank { "app" }.take(12)
        }

        fun skillIdFor(packageName: String): String =
            "agent-mode-" + packageName.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "app" }
    }
}
