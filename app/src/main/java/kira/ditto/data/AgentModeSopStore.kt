package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class AgentModeSopProvenance(val storageValue: String) {
    KimiAgent("kimi_agent"),
    UserTeaching("user_teaching"),
    LegacyOperator("legacy_operator");

    companion object {
        fun fromStorage(value: String): AgentModeSopProvenance =
            entries.firstOrNull { it.storageValue == value } ?: LegacyOperator
    }
}

enum class AgentModeSopMaturity(val storageValue: String) {
    Candidate("candidate"),
    Verified("verified"),
    Ready("ready"),
    Degraded("degraded"),
    Validating("validating");

    companion object {
        fun fromStorage(value: String): AgentModeSopMaturity =
            entries.firstOrNull { it.storageValue == value } ?: Candidate
    }
}

data class AgentModeSopStep(
    val action: String,
    val source: String = "operator",
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val target: String = "",
    val text: String = "",
    val key: String = "",
    val packageName: String = "",
    val appName: String = "",
    val screenshotPath: String = "",
    val guiLabel: String = "",
    val guiBounds: String = "",
    val inputSlot: String = "",
    val resultOk: Boolean = true,
    val uiFingerprint: String = "",
    val toFingerprint: String = "",
    val kind: String = "",
    val pageTargetCount: Int = 0,
    val resourceId: String = "",
    val semantic: String = "",
    val locator: String = "",
    val locatorSpec: GuiLocator = GuiLocator.Empty,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", action)
        .put("source", source)
        .put("x", x)
        .put("y", y)
        .put("x1", x1)
        .put("y1", y1)
        .put("x2", x2)
        .put("y2", y2)
        .put("target", target)
        .put("text", text)
        .put("key", key)
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("screenshot_path", screenshotPath)
        .put("gui_label", guiLabel)
        .put("gui_bounds", guiBounds)
        .put("input_slot", inputSlot)
        .put("result_ok", resultOk)
        .put("ui_fingerprint", uiFingerprint)
        .put("to_fingerprint", toFingerprint)
        .put("kind", kind)
        .put("page_target_count", pageTargetCount)
        .put("resource_id", resourceId)
        .put("semantic", semantic)
        .put("locator", locator)
        .put("locator_spec", locatorSpec.toJson())

    fun toExecuteArguments(): JSONObject {
        val tapLike = PhoneUiMcp.normalizeAction(action) in setOf("tap", "click_node")
        if (tapLike && !locatorSpec.isEmpty()) {
            val arguments = JSONObject().put("action", "click_node")
            val query = locatorSpec.resourceId
                .ifBlank { locatorSpec.staticDesc }
                .ifBlank { locatorSpec.hint }
                .ifBlank { locator }
            if (query.isNotBlank()) arguments.put("query", query)
            if (kind.isNotBlank()) arguments.put("kind", kind)
            if (inputSlot.isNotBlank()) arguments.put("input_slot", inputSlot)
            return arguments
        }
        val arguments = JSONObject().put("action", PhoneUiMcp.normalizeAction(action))
        val skipCoordinates = kind == TeachingTraceDistiller.KindInstance ||
            inputSlot == TeachingTraceDistiller.PickSlot ||
            !locatorSpec.isEmpty()
        if (!skipCoordinates) {
            x?.let { arguments.put("x", it) }
            y?.let { arguments.put("y", it) }
            x1?.let { arguments.put("x1", it) }
            y1?.let { arguments.put("y1", it) }
            x2?.let { arguments.put("x2", it) }
            y2?.let { arguments.put("y2", it) }
        }
        if (target.isNotBlank()) arguments.put("target", target)
        if (text.isNotBlank() && inputSlot.isBlank()) arguments.put("text", text)
        if (key.isNotBlank()) arguments.put("key", key)
        if (kind.isNotBlank()) arguments.put("kind", kind)
        if (inputSlot.isNotBlank()) arguments.put("input_slot", inputSlot)
        return arguments
    }

    companion object {
        fun fromJson(item: JSONObject): AgentModeSopStep = AgentModeSopStep(
            action = item.optString("action"),
            source = item.optString("source").ifBlank { "operator" },
            x = item.optionalInt("x"),
            y = item.optionalInt("y"),
            x1 = item.optionalInt("x1"),
            y1 = item.optionalInt("y1"),
            x2 = item.optionalInt("x2"),
            y2 = item.optionalInt("y2"),
            target = item.optString("target"),
            text = item.optString("text"),
            key = item.optString("key"),
            packageName = item.optString("package_name"),
            appName = item.optString("app_name"),
            screenshotPath = item.optString("screenshot_path"),
            guiLabel = item.optString("gui_label"),
            guiBounds = item.optString("gui_bounds"),
            inputSlot = item.optString("input_slot"),
            resultOk = item.optBoolean("result_ok", true),
            uiFingerprint = item.optString("ui_fingerprint"),
            toFingerprint = item.optString("to_fingerprint"),
            kind = item.optString("kind"),
            pageTargetCount = item.optInt("page_target_count"),
            resourceId = item.optString("resource_id"),
            semantic = item.optString("semantic"),
            locator = item.optString("locator"),
            locatorSpec = GuiLocator.fromJson(item.optJSONObject("locator_spec")),
        )

        private fun JSONObject.optionalInt(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return optInt(key)
        }
    }
}

data class AgentModeSop(
    val id: String,
    val goal: String,
    val packageName: String = "",
    val appName: String = "",
    val steps: List<AgentModeSopStep> = emptyList(),
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val confidence: Double = 0.0,
    val promoted: Boolean = false,
    val maturity: AgentModeSopMaturity = AgentModeSopMaturity.Candidate,
    val provenance: AgentModeSopProvenance = AgentModeSopProvenance.LegacyOperator,
    val riskLevel: String = "low",
    val validationState: String = "unvalidated",
    val environmentFingerprint: String = "",
    val lastFailureCode: String = "",
    val failureStreak: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("goal", goal)
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("success_count", successCount)
        .put("failure_count", failureCount)
        .put("confidence", confidence)
        .put("promoted", promoted)
        .put("maturity", maturity.storageValue)
        .put("provenance", provenance.storageValue)
        .put("risk_level", riskLevel)
        .put("validation_state", validationState)
        .put("environment_fingerprint", environmentFingerprint)
        .put("last_failure_code", lastFailureCode)
        .put("failure_streak", failureStreak)
        .put("created_at_millis", createdAtMillis)
        .put("updated_at_millis", updatedAtMillis)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
}

class AgentModeSopStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    fun findById(id: String): AgentModeSop? = lock.withLock {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return@withLock null
        loadAllUnlocked().firstOrNull { it.id == trimmed }
    }

    fun findByGoal(goal: String): AgentModeSop? = lock.withLock {
        val key = normalizeGoal(goal)
        if (key.isBlank()) return@withLock null
        loadAllUnlocked()
            .filter { normalizeGoal(it.goal) == key }
            .maxWithOrNull(compareBy<AgentModeSop>({ it.promoted }, { it.confidence }, { it.successCount }))
    }

    fun findRelevant(goal: String, limit: Int = 3): List<AgentModeSop> = lock.withLock {
        val normalized = normalizeGoal(goal)
        if (normalized.isBlank()) return@withLock emptyList()
        loadAllUnlocked()
            .map { sop -> sop to goalSimilarity(normalized, normalizeGoal(sop.goal)) }
            .filter { (_, score) -> score >= 0.2 }
            .sortedWith(
                compareByDescending<Pair<AgentModeSop, Double>> { it.second }
                    .thenByDescending { it.first.promoted }
                    .thenByDescending { it.first.confidence },
            )
            .take(limit.coerceIn(1, 10))
            .map { it.first }
    }

    fun recordSuccess(goal: String, steps: List<AgentModeSopStep>): AgentModeSop =
        recordVerifiedSuccess(
            goal = goal,
            steps = steps,
            provenance = AgentModeSopProvenance.LegacyOperator,
        )

    fun recordVerifiedSuccess(
        goal: String,
        steps: List<AgentModeSopStep>,
        provenance: AgentModeSopProvenance = AgentModeSopProvenance.KimiAgent,
        promoteImmediately: Boolean = false,
        riskLevel: String = "",
        validationState: String = "",
        environmentFingerprint: String = "",
    ): AgentModeSop = lock.withLock {
        val existing = findByGoal(goal)
        val packageName = steps.map { it.packageName }.lastOrNull { it.isNotBlank() }.orEmpty()
        val appName = steps.map { it.appName }.lastOrNull { it.isNotBlank() }.orEmpty()
        val nextCount = (existing?.successCount ?: 0) + 1
        val failureCount = existing?.failureCount ?: 0
        val userTaught = provenance == AgentModeSopProvenance.UserTeaching ||
            existing?.provenance == AgentModeSopProvenance.UserTeaching
        val methodOnly = GuiFlowSegmenter.segment(steps).let { segments ->
            segments.isNotEmpty() && segments.all { it.methodOnly }
        }
        val risk = riskLevel.ifBlank {
            if (methodOnly) "low" else "medium"
        }
        val validation = validationState.ifBlank {
            when {
                userTaught -> "validated"
                methodOnly -> "validated"
                // A repeat success is the replay this was waiting for.
                //
                // This used to recompute from scratch on every call, so an automatically learned
                // flow returned "pending_replay" forever - and since promotion requires
                // "validated", it could never promote no matter how many times it succeeded. The
                // first success still parks at pending_replay on purpose (one success is not
                // evidence a GUI flow is repeatable); it is the second that clears it, which is the
                // same rule `recordFeedback` below already applied.
                existing != null && existing.validationState == "pending_replay" -> "validated"
                existing != null -> existing.validationState
                else -> "pending_replay"
            }
        }
        val promoted = existing?.promoted == true ||
            userTaught ||
            promoteImmediately ||
            (methodOnly && validation == "validated" && nextCount >= PromoteMethodAfterSuccesses) ||
            (validation == "validated" && nextCount >= PromoteAfterSuccesses)
        val now = System.currentTimeMillis()
        val sop = AgentModeSop(
            id = existing?.id ?: newId(),
            goal = goal.trim(),
            packageName = packageName.ifBlank { existing?.packageName.orEmpty() },
            appName = appName.ifBlank { existing?.appName.orEmpty() },
            steps = steps.ifEmpty { existing?.steps.orEmpty() },
            successCount = nextCount,
            failureCount = failureCount,
            confidence = confidenceFor(nextCount, failureCount, userTaught),
            promoted = promoted,
            maturity = when {
                promoted -> AgentModeSopMaturity.Ready
                validation == "pending_replay" -> AgentModeSopMaturity.Validating
                nextCount >= 2 -> AgentModeSopMaturity.Verified
                else -> AgentModeSopMaturity.Candidate
            },
            provenance = if (userTaught) AgentModeSopProvenance.UserTeaching else provenance,
            riskLevel = risk,
            validationState = validation,
            environmentFingerprint = environmentFingerprint.ifBlank {
                existing?.environmentFingerprint.orEmpty()
            },
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        writeLocked(sop)
        sop
    }

    fun saveExplicit(goal: String, steps: List<AgentModeSopStep>): AgentModeSop =
        recordVerifiedSuccess(
            goal = goal,
            steps = steps.ifEmpty { findByGoal(goal)?.steps.orEmpty() },
            provenance = AgentModeSopProvenance.UserTeaching,
        )

    fun recordFeedback(id: String, succeeded: Boolean): AgentModeSop? = lock.withLock {
        val existing = findById(id) ?: return@withLock null
        val successes = existing.successCount + if (succeeded) 1 else 0
        val failures = existing.failureCount + if (succeeded) 0 else 1
        val userTaught = existing.provenance == AgentModeSopProvenance.UserTeaching
        val validation = if (succeeded && existing.validationState == "pending_replay") {
            "validated"
        } else {
            existing.validationState
        }
        val nextStreak = if (succeeded) 0 else existing.failureStreak + 1
        val promoted = existing.promoted || userTaught ||
            (validation == "validated" && successes >= PromoteAfterSuccesses)
        val degraded = !succeeded && failures >= maxOf(2, successes)
        val updated = existing.copy(
            successCount = successes,
            failureCount = failures,
            confidence = confidenceFor(successes, failures, userTaught),
            promoted = promoted && !degraded,
            failureStreak = nextStreak,
            maturity = when {
                degraded -> AgentModeSopMaturity.Degraded
                promoted -> AgentModeSopMaturity.Ready
                validation == "pending_replay" -> AgentModeSopMaturity.Validating
                successes >= 2 -> AgentModeSopMaturity.Verified
                else -> AgentModeSopMaturity.Candidate
            },
            validationState = validation,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeLocked(updated)
        updated
    }

    fun recordFailureSignal(id: String, code: String): AgentModeSop? = lock.withLock {
        val existing = findById(id) ?: return@withLock null
        val degraded = code in setOf(
            AgentModeSafety.MacroMismatchCode,
            AgentModeSafety.StalledCode,
            AgentModeSafety.SceneNotSopCode,
        )
        val updated = existing.copy(
            lastFailureCode = code,
            failureStreak = existing.failureStreak + 1,
            promoted = if (degraded) false else existing.promoted,
            maturity = if (degraded) AgentModeSopMaturity.Degraded else existing.maturity,
            validationState = if (degraded) "failed" else existing.validationState,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeLocked(updated)
        updated
    }

    /** Outcome of a pre-promotion replay (see PhoneSettlementStage.Validating). */
    fun recordValidationResult(id: String, replayPassed: Boolean): AgentModeSop? = lock.withLock {
        val existing = findById(id) ?: return@withLock null
        if (existing.validationState != "pending_replay") return@withLock existing
        val updated = if (replayPassed) {
            existing.copy(
                validationState = "validated",
                promoted = existing.provenance == AgentModeSopProvenance.UserTeaching ||
                    existing.successCount >= PromoteAfterSuccesses,
                maturity = when {
                    existing.provenance == AgentModeSopProvenance.UserTeaching -> AgentModeSopMaturity.Ready
                    existing.successCount >= PromoteAfterSuccesses -> AgentModeSopMaturity.Ready
                    else -> AgentModeSopMaturity.Verified
                },
                failureStreak = 0,
                updatedAtMillis = System.currentTimeMillis(),
            )
        } else {
            existing.copy(
                validationState = "failed",
                promoted = false,
                maturity = AgentModeSopMaturity.Degraded,
                failureStreak = existing.failureStreak + 1,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        writeLocked(updated)
        updated
    }

    fun loadAll(): List<AgentModeSop> = lock.withLock { loadAllUnlocked() }

    private fun loadAllUnlocked(): List<AgentModeSop> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file -> runCatching { parse(JSONObject(file.readText())) }.getOrNull() }
    }

    private fun writeLocked(sop: AgentModeSop) {
        root.mkdirs()
        val target = File(root, "${sop.id}.json")
        val staging = File(root, ".${sop.id}.${UUID.randomUUID()}.tmp")
        staging.writeText(sop.toJson().toString())
        if (!staging.renameTo(target)) {
            target.writeText(staging.readText())
            staging.delete()
        }
    }

    private fun parse(json: JSONObject): AgentModeSop {
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(index) ?: continue
                add(AgentModeSopStep.fromJson(item))
            }
        }
        val successCount = json.optInt("success_count")
        val promoted = json.optBoolean("promoted") || successCount >= PromoteAfterSuccesses
        return AgentModeSop(
            id = json.optString("id").ifBlank { newId() },
            goal = json.optString("goal"),
            packageName = json.optString("package_name"),
            appName = json.optString("app_name"),
            steps = steps,
            successCount = successCount,
            failureCount = json.optInt("failure_count"),
            confidence = json.optDouble("confidence", confidenceFor(json.optInt("success_count"), json.optInt("failure_count"), false)),
            promoted = promoted,
            maturity = AgentModeSopMaturity.fromStorage(json.optString("maturity")).let { maturity ->
                if (promoted && maturity == AgentModeSopMaturity.Candidate) {
                    AgentModeSopMaturity.Ready
                } else {
                    maturity
                }
            },
            provenance = AgentModeSopProvenance.fromStorage(json.optString("provenance")),
            riskLevel = json.optString("risk_level").ifBlank { "low" },
            validationState = json.optString("validation_state").ifBlank {
                if (json.optBoolean("promoted") || successCount >= PromoteAfterSuccesses) {
                    "validated"
                } else {
                    "unvalidated"
                }
            },
            environmentFingerprint = json.optString("environment_fingerprint"),
            lastFailureCode = json.optString("last_failure_code"),
            failureStreak = json.optInt("failure_streak"),
            createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis()),
        )
    }

    companion object {
        const val PromoteAfterSuccesses = 3
        const val PromoteMethodAfterSuccesses = 1

        private fun confidenceFor(successes: Int, failures: Int, userTaught: Boolean): Double {
            val priorSuccesses = if (userTaught) 2.0 else 0.0
            val score = (successes + priorSuccesses) / (successes + failures + priorSuccesses + 2.0)
            return score.coerceIn(0.0, 0.99)
        }

        private fun goalSimilarity(left: String, right: String): Double {
            if (left == right) return 1.0
            if (left.contains(right) || right.contains(left)) return 0.8
            val leftTokens = goalTokens(left)
            val rightTokens = goalTokens(right)
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
            val intersection = leftTokens.intersect(rightTokens).size.toDouble()
            val union = leftTokens.union(rightTokens).size.toDouble()
            return if (union == 0.0) 0.0 else intersection / union
        }

        private fun goalTokens(value: String): Set<String> = buildSet {
            Regex("[a-z0-9]+|[\\u4e00-\\u9fff]+").findAll(value).forEach { match ->
                val token = match.value
                add(token)
                if (token.any { it.code in 0x4E00..0x9FFF } && token.length > 1) {
                    token.windowed(2).forEach(::add)
                }
            }
        }

        fun normalizeGoal(goal: String): String =
            goal.trim().lowercase().replace(Regex("\\s+"), " ")

        fun newId(): String = "sop-" + UUID.randomUUID().toString().take(8)
    }
}
