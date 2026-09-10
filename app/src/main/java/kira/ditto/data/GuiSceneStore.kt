package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Local index of GUI scenes distilled from takeover teaching. EverMe is the
 * searchable memory; this store is the offline fallback and the source of
 * the structured fact the desk lead saves.
 */
data class GuiSceneCard(
    val id: String,
    val appName: String,
    val packageName: String,
    val scene: String,
    val tags: List<String>,
    val guiLabels: List<String>,
    val method: List<String>,
    val pickSlots: Int = 0,
    val repairNarrative: String = "",
    val body: String,
    val sopId: String = "",
    val failureSignals: List<String> = emptyList(),
    val sopMaturity: String = "",
    val sopConfidence: Double = 0.0,
    val version: Int = 1,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("app_name", appName)
        .put("package_name", packageName)
        .put("scene", scene)
        .put("tags", JSONArray(tags))
        .put("gui_labels", JSONArray(guiLabels))
        .put("method", JSONArray(method))
        .put("pick_slots", pickSlots)
        .put("repair_narrative", repairNarrative)
        .put("body", body)
        .put("sop_id", sopId)
        .put("failure_signals", JSONArray(failureSignals))
        .put("sop_maturity", sopMaturity)
        .put("sop_confidence", sopConfidence)
        .put("version", version)
        .put("updated_at_millis", updatedAtMillis)

    fun toEverMeFact(): String = buildString {
        appendLine(tags.joinToString(" ") { tag -> if (tag.startsWith("#")) tag else "#$tag" })
        append("App: ").append(appName.ifBlank { packageName }.ifBlank { "unknown" })
        if (packageName.isNotBlank() && appName.isNotBlank()) {
            append(" (").append(packageName).append(")")
        }
        appendLine()
        append("Scene: ").append(scene).appendLine()
        if (guiLabels.isNotEmpty()) {
            append("GUI labels: ").append(guiLabels.joinToString(" | ")).appendLine()
        }
        if (method.isNotEmpty()) {
            append("Method: ").append(method.joinToString(" → ")).appendLine()
        }
        if (pickSlots > 0) {
            append("Pick slots: ").append(pickSlots)
            appendLine(" (look up this user's item on the current page; never reuse taught coordinates)")
        }
        if (repairNarrative.isNotBlank()) {
            append("Repair: ").append(repairNarrative).appendLine()
        }
        if (body.isNotBlank()) {
            appendLine(body.trim())
        }
        if (sopId.isNotBlank()) {
            append("sop_id=").append(sopId)
        }
    }.trim()

    fun toLeadHint(): String {
        val app = appName.ifBlank { packageName }.ifBlank { "app" }
        val labels = guiLabels.take(6).joinToString("|").ifBlank { "(no chrome labels)" }
        return buildString {
            append("- $app / $scene  labels=$labels")
            if (sopId.isNotBlank()) {
                append(" sop_id=").append(sopId)
            }
            if (sopConfidence > 0.0) {
                append(" confidence=").append("%.2f".format(sopConfidence))
            }
            if (sopMaturity.isNotBlank()) {
                append(" maturity=").append(sopMaturity)
            }
            if (failureSignals.isNotEmpty()) {
                append(" last_failure=").append(failureSignals.last())
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): GuiSceneCard = GuiSceneCard(
            id = json.optString("id"),
            appName = json.optString("app_name"),
            packageName = json.optString("package_name"),
            scene = json.optString("scene"),
            tags = json.optJSONArray("tags").toStringList(),
            guiLabels = json.optJSONArray("gui_labels").toStringList(),
            method = json.optJSONArray("method").toStringList(),
            pickSlots = json.optInt("pick_slots"),
            repairNarrative = json.optString("repair_narrative"),
            body = json.optString("body"),
            sopId = json.optString("sop_id"),
            failureSignals = json.optJSONArray("failure_signals").toStringList(),
            sopMaturity = json.optString("sop_maturity"),
            sopConfidence = json.optDouble("sop_confidence"),
            version = json.optInt("version", 1),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis()),
        )

        fun sceneKey(packageName: String, scene: String): String {
            val pkg = packageName.trim().lowercase(Locale.ROOT)
            val goal = AgentModeSopStore.normalizeGoal(scene)
            return "$pkg|$goal"
        }
    }
}

class GuiSceneStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    fun upsert(card: GuiSceneCard): GuiSceneCard = lock.withLock {
        root.mkdirs()
        val key = GuiSceneCard.sceneKey(card.packageName, card.scene)
        val existing = if (key.removePrefix("|").isBlank()) {
            null
        } else {
            loadAllUnlocked().firstOrNull { current ->
                GuiSceneCard.sceneKey(current.packageName, current.scene) == key
            }
        }
        val saved = if (existing != null) {
            card.copy(
                id = existing.id,
                version = existing.version + 1,
                updatedAtMillis = System.currentTimeMillis(),
            )
        } else {
            card.copy(
                id = card.id.ifBlank { "scene-" + UUID.randomUUID().toString().take(8) },
                version = card.version.coerceAtLeast(1),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        File(root, "${saved.id}.json").writeText(saved.toJson().toString())
        saved
    }

    fun recall(task: String, packageName: String = "", limit: Int = 4): List<GuiSceneCard> = lock.withLock {
        val query = AgentModeSopStore.normalizeGoal(task)
        if (query.isBlank()) return@withLock emptyList()
        val requested = packageName.trim()
        loadAllUnlocked()
            .filter { card ->
                requested.isBlank() ||
                    card.packageName.equals(requested, ignoreCase = true)
            }
            .map { card -> card to recallScore(query, card) }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 12))
            .map { it.first }
    }

    fun loadAll(): List<GuiSceneCard> = lock.withLock { loadAllUnlocked() }

    fun findById(id: String): GuiSceneCard? = lock.withLock {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return@withLock null
        loadAllUnlocked().firstOrNull { it.id == trimmed }
    }

    private fun loadAllUnlocked(): List<GuiSceneCard> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { GuiSceneCard.fromJson(JSONObject(file.readText())) }.getOrNull() }
    }

    private fun recallScore(query: String, card: GuiSceneCard): Double {
        val haystack = AgentModeSopStore.normalizeGoal(
            listOf(
                card.appName,
                card.packageName,
                card.scene,
                card.guiLabels.joinToString(" "),
                card.tags.joinToString(" "),
            ).joinToString(" "),
        )
        if (haystack.contains(query) || query.contains(AgentModeSopStore.normalizeGoal(card.scene))) {
            return 1.0 + card.version * 0.01
        }
        val queryTokens = Regex("[a-z0-9]+|[\\u4e00-\\u9fff]+").findAll(query).map { it.value }.toSet()
        val haystackTokens = Regex("[a-z0-9]+|[\\u4e00-\\u9fff]+").findAll(haystack).map { it.value }.toSet()
        if (queryTokens.isEmpty() || haystackTokens.isEmpty()) return 0.0
        val overlap = queryTokens.intersect(haystackTokens).size.toDouble() / queryTokens.size
        val appBonus = if (card.appName.isNotBlank() && query.contains(card.appName.lowercase(Locale.ROOT))) {
            0.4
        } else {
            0.0
        }
        return overlap + appBonus
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

internal object GuiSceneComposer {
    fun fromTeaching(
        goal: String,
        appName: String,
        packageName: String,
        distilled: TeachingTraceDistiller.Result,
        sopId: String,
    ): GuiSceneCard {
        val app = appName.ifBlank { packageName }.ifBlank { "app" }
        val scene = GuiSceneAbstractor.sceneGoal(app, goal)
        val labels = distilled.replay
            .map { step ->
                step.locatorSpec.staticDesc
                    .ifBlank { step.locatorSpec.hint }
                    .ifBlank { step.guiLabel }
            }
            .filter { it.isNotBlank() && !GuiLocatorCodec.isVolatileContent(it) }
            .distinct()
        val method = distilled.replay.mapNotNull { step ->
            val recipe = step.locatorSpec.recipe()
            step.semantic.ifBlank { step.guiLabel.takeIf { it.isNotBlank() } }.orEmpty()
                .let { text -> if (recipe.isNotBlank() && recipe !in text) "$text（$recipe）" else text }
                .takeIf { it.isNotBlank() }
        }
        val tags = buildList {
            add("#gui")
            add("#app:${sanitizeTag(app)}")
            add("#scene:${sanitizeTag(scene)}")
            labels.take(6).forEach { label -> add("#${sanitizeTag(label)}") }
        }.distinct()
        val pickSlots = distilled.replay.count {
            it.kind == TeachingTraceDistiller.KindInstance ||
                it.inputSlot == TeachingTraceDistiller.PickSlot
        }
        val repair = distilled.repair
            .map { it.guiLabel.ifBlank { it.action } }
            .filter { it.isNotBlank() }
            .joinToString(" → ")
        val body = distilled.everMeSummary.ifBlank {
            buildString {
                if (method.isNotEmpty()) {
                    append("方法：").append(method.joinToString(" → ")).append("。")
                }
                if (pickSlots > 0) {
                    append("有 $pickSlots 步是选当前用户的目标，不要复用坐标。")
                }
            }
        }
        return GuiSceneCard(
            id = "",
            appName = app,
            packageName = packageName,
            scene = scene,
            tags = tags,
            guiLabels = labels,
            method = method,
            pickSlots = pickSlots,
            repairNarrative = repair,
            body = body,
            sopId = sopId,
        )
    }

    private fun sanitizeTag(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), "")
            .replace("#", "")
            .take(24)
            .ifBlank { "gui" }
}
