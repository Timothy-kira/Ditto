package kira.ditto.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PhoneSettlementStage(val storageValue: String) {
    Queued("queued"),
    Catalogued("catalogued"),
    Validating("validating"),
    SkillWritten("skill_written"),
    Done("done"),
    Failed("failed");

    companion object {
        fun fromStorage(value: String): PhoneSettlementStage =
            entries.firstOrNull { it.storageValue == value } ?: Queued
    }
}

data class PhoneSettlementJob(
    val id: String,
    val stage: PhoneSettlementStage = PhoneSettlementStage.Queued,
    val goal: String,
    val summary: String = "",
    val packageName: String = "",
    val appName: String = "",
    val sopId: String = "",
    val sopPromoted: Boolean = false,
    val sopSuccessCount: Int = 0,
    val sopFailureCount: Int = 0,
    val sopConfidence: Double = 0.0,
    val sopProvenance: String = AgentModeSopProvenance.KimiAgent.storageValue,
    val steps: List<AgentModeSopStep> = emptyList(),
    val skillId: String = "",
    val error: String = "",
    val running: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("stage", stage.storageValue)
        .put("goal", goal)
        .put("summary", summary)
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("sop_id", sopId)
        .put("sop_promoted", sopPromoted)
        .put("sop_success_count", sopSuccessCount)
        .put("sop_failure_count", sopFailureCount)
        .put("sop_confidence", sopConfidence)
        .put("sop_provenance", sopProvenance)
        .put("skill_id", skillId)
        .put("error", error)
        .put("running", running)
        .put("created_at_millis", createdAtMillis)
        .put("updated_at_millis", updatedAtMillis)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): PhoneSettlementJob {
            val stepsJson = json.optJSONArray("steps") ?: JSONArray()
            return PhoneSettlementJob(
                id = json.optString("id").ifBlank { "settle-" + UUID.randomUUID().toString().take(8) },
                stage = PhoneSettlementStage.fromStorage(json.optString("stage")),
                goal = json.optString("goal"),
                summary = json.optString("summary"),
                packageName = json.optString("package_name"),
                appName = json.optString("app_name"),
                sopId = json.optString("sop_id"),
                sopPromoted = json.optBoolean("sop_promoted"),
                sopSuccessCount = json.optInt("sop_success_count"),
                sopFailureCount = json.optInt("sop_failure_count"),
                sopConfidence = json.optDouble("sop_confidence"),
                sopProvenance = json.optString("sop_provenance")
                    .ifBlank { AgentModeSopProvenance.LegacyOperator.storageValue },
                skillId = json.optString("skill_id"),
                error = json.optString("error"),
                running = json.optBoolean("running"),
                createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
                updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis()),
                steps = buildList {
                    for (index in 0 until stepsJson.length()) {
                        val item = stepsJson.optJSONObject(index) ?: continue
                        add(AgentModeSopStep.fromJson(item))
                    }
                },
            )
        }
    }
}

data class PhoneSettlementUiState(
    val isActive: Boolean = false,
    val headline: String = "",
    val detail: String = "",
    val appName: String = "",
)

class PhoneSettlementStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    fun upsert(
        job: PhoneSettlementJob,
        updateTimestamp: Boolean = true,
    ) = lock.withLock {
        root.mkdirs()
        File(root, "${job.id}.json").writeText(
            (if (updateTimestamp) job.copy(updatedAtMillis = System.currentTimeMillis()) else job)
                .toJson()
                .toString(),
        )
    }

    fun load(id: String): PhoneSettlementJob? = lock.withLock {
        val file = File(root, "$id.json")
        if (!file.isFile) return@withLock null
        runCatching { PhoneSettlementJob.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun loadAll(): List<PhoneSettlementJob> = lock.withLock {
        if (!root.isDirectory) return@withLock emptyList()
        root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file -> runCatching { PhoneSettlementJob.fromJson(JSONObject(file.readText())) }.getOrNull() }
            .sortedBy { it.createdAtMillis }
    }

    fun reclaimStaleRunning(staleAfterMillis: Long = 45_000L) = lock.withLock {
        val now = System.currentTimeMillis()
        loadAllUnlocked().forEach { job ->
            if (job.running && now - job.updatedAtMillis >= staleAfterMillis &&
                job.stage != PhoneSettlementStage.Done &&
                job.stage != PhoneSettlementStage.Failed
            ) {
                writeUnlocked(job.copy(running = false, updatedAtMillis = now))
            }
        }
    }

    fun nextResumable(): PhoneSettlementJob? = lock.withLock {
        loadAllUnlocked().firstOrNull { job ->
            !job.running &&
                job.stage != PhoneSettlementStage.Done &&
                job.stage != PhoneSettlementStage.Failed
        }
    }

    fun activeJobs(): List<PhoneSettlementJob> = loadAll().filter { job ->
        job.stage != PhoneSettlementStage.Done && job.stage != PhoneSettlementStage.Failed
    }

    private fun loadAllUnlocked(): List<PhoneSettlementJob> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file -> runCatching { PhoneSettlementJob.fromJson(JSONObject(file.readText())) }.getOrNull() }
            .sortedBy { it.createdAtMillis }
    }

    private fun writeUnlocked(job: PhoneSettlementJob) {
        root.mkdirs()
        File(root, "${job.id}.json").writeText(job.toJson().toString())
    }
}

class PhoneSettlementRuntime(
    private val store: PhoneSettlementStore,
    private val catalogStore: PhoneAppCatalogStore,
    private val skillManager: AgentSkillManager,
    private val scope: CoroutineScope,
    private val sopStore: AgentModeSopStore,
    private val replayExecutor: PhoneAppFlowExecutor? = null,
    private val replaySettings: (() -> AppSettings)? = null,
    private val replayWorkspaceDirectory: (() -> String)? = null,
) {
    private val paused = AtomicBoolean(false)
    private val pumpMutex = Mutex()
    private val _uiState = MutableStateFlow(PhoneSettlementUiState())
    val uiState: StateFlow<PhoneSettlementUiState> = _uiState.asStateFlow()

    fun enqueue(
        goal: String,
        summary: String,
        steps: List<AgentModeSopStep>,
        sop: AgentModeSop?,
        packageName: String,
        appName: String,
    ) {
        if (steps.isEmpty() && sop == null) return
        val job = PhoneSettlementJob(
            id = "settle-" + UUID.randomUUID().toString().take(8),
            goal = goal,
            summary = summary,
            packageName = packageName,
            appName = appName,
            sopId = sop?.id.orEmpty(),
            sopPromoted = sop?.promoted == true,
            sopSuccessCount = sop?.successCount ?: 0,
            sopFailureCount = sop?.failureCount ?: 0,
            sopConfidence = sop?.confidence ?: 0.0,
            sopProvenance = sop?.provenance?.storageValue
                ?: AgentModeSopProvenance.KimiAgent.storageValue,
            steps = steps,
        )
        store.upsert(job)
        publishUi()
        scope.launch { pump() }
    }

    fun pause() {
        paused.set(true)
        publishUi()
    }

    fun resumePending(reclaimAllRunning: Boolean = false) {
        paused.set(false)
        store.reclaimStaleRunning(staleAfterMillis = if (reclaimAllRunning) 0L else 45_000L)
        publishUi()
        scope.launch { pump() }
    }

    private suspend fun pump() {
        if (!pumpMutex.tryLock()) return
        try {
            while (!paused.get()) {
                val job = store.nextResumable() ?: break
                runCatching { advance(job) }
                    .onFailure { error ->
                        store.upsert(
                            job.copy(
                                stage = PhoneSettlementStage.Failed,
                                running = false,
                                error = error.message.orEmpty().take(240),
                            ),
                        )
                    }
                publishUi()
            }
        } finally {
            pumpMutex.unlock()
            publishUi()
        }
    }

    private suspend fun advance(job: PhoneSettlementJob) = withContext(Dispatchers.IO) {
        var current = job.copy(running = true, error = "")
        store.upsert(current)
        publishUi()
        when (current.stage) {
            PhoneSettlementStage.Queued -> {
                current = catalogue(current)
                store.upsert(current.copy(running = false))
            }
            PhoneSettlementStage.Catalogued -> {
                current = validateReplay(current)
                store.upsert(current.copy(running = false))
            }
            PhoneSettlementStage.Validating -> {
                current = writeSkill(current)
                store.upsert(current.copy(running = false))
            }
            PhoneSettlementStage.SkillWritten -> {
                store.upsert(
                    current.copy(
                        stage = PhoneSettlementStage.Done,
                        running = false,
                    ),
                )
            }
            PhoneSettlementStage.Done, PhoneSettlementStage.Failed -> {
                store.upsert(current.copy(running = false))
            }
        }
    }

    fun validateReplay(job: PhoneSettlementJob): PhoneSettlementJob {
        if (job.sopProvenance == AgentModeSopProvenance.UserTeaching.storageValue ||
            job.sopPromoted
        ) {
            return job.copy(stage = PhoneSettlementStage.Validating)
        }
        val sop = sopStore.findById(job.sopId) ?: return job.copy(
            stage = PhoneSettlementStage.Validating,
            running = false,
        )
        if (sop.validationState != "pending_replay") {
            return job.copy(stage = PhoneSettlementStage.Validating)
        }
        val ok = runCatching {
            val output = replaySopBlocking(sop, job)
            val parsed = runCatching { JSONObject(output) }.getOrNull()
            parsed?.optBoolean("ok", false) == true
        }.getOrDefault(false)
        val updated = sopStore.recordValidationResult(sop.id, ok)
        return job.copy(
            stage = PhoneSettlementStage.Validating,
            sopPromoted = updated?.promoted == true,
            running = false,
            error = if (ok) "" else "Replay validation did not pass.",
        )
    }

    private fun replaySopBlocking(sop: AgentModeSop, job: PhoneSettlementJob): String {
        val executor = replayExecutor ?: return "{\"ok\":false,\"errmsg\":\"no replay executor\"}"
        val settings = replaySettings?.invoke() ?: return "{\"ok\":false,\"errmsg\":\"no replay settings\"}"
        return kotlinx.coroutines.runBlocking {
            executor.invoke(
                toolName = PhoneAppFlowMcp.RunToolName,
                arguments = JSONObject().put("sop_id", sop.id),
                settings = settings,
                workspaceDirectory = replayWorkspaceDirectory?.invoke().orEmpty(),
            )
        }
    }

    private fun catalogue(job: PhoneSettlementJob): PhoneSettlementJob {
        val packageName = job.packageName.ifBlank {
            job.steps.map { it.packageName }.lastOrNull { it.isNotBlank() }.orEmpty()
        }
        val appName = job.appName.ifBlank {
            job.steps.map { it.appName }.lastOrNull { it.isNotBlank() }.orEmpty()
        }
        if (packageName.isBlank() && appName.isBlank()) {
            return job.copy(stage = PhoneSettlementStage.Done, running = false)
        }
        val resolvedPackage = packageName.ifBlank { appName }
        val primitives = PhoneAppPrimitiveClassifier.classify(
            goal = job.goal,
            steps = job.steps,
            packageName = resolvedPackage,
            appName = appName.ifBlank { resolvedPackage },
            sopId = job.sopId,
            successCount = job.sopSuccessCount.coerceAtLeast(1),
            failureCount = job.sopFailureCount,
            confidence = job.sopConfidence,
            promoted = job.sopPromoted,
            provenance = AgentModeSopProvenance.fromStorage(job.sopProvenance),
        )
        val skillId = PhoneAppCatalogStore.skillIdFor(resolvedPackage)
        catalogStore.merge(
            packageName = resolvedPackage,
            appName = appName.ifBlank { resolvedPackage },
            incoming = primitives,
            skillId = skillId,
        )
        return job.copy(
            stage = PhoneSettlementStage.Catalogued,
            packageName = resolvedPackage,
            appName = appName.ifBlank { resolvedPackage },
            skillId = skillId,
            running = false,
        )
    }

    private suspend fun writeSkill(job: PhoneSettlementJob): PhoneSettlementJob {
        val catalog = catalogStore.load(job.packageName)
            ?: return job.copy(stage = PhoneSettlementStage.SkillWritten)
        val markdown = PhoneAppSkillMarkdown.render(catalog)
        val result = skillManager.upsertAgentModeSkill(
            skillId = catalog.skillId.ifBlank { PhoneAppCatalogStore.skillIdFor(catalog.packageName) },
            name = catalog.appName.ifBlank { catalog.packageName },
            description = markdown.description,
            bodyMarkdown = markdown.body,
        )
        val skill = result.getOrThrow()
        catalogStore.save(catalog.copy(skillId = skill.id))
        return job.copy(
            stage = PhoneSettlementStage.SkillWritten,
            skillId = skill.id,
            running = false,
        )
    }

    private fun publishUi() {
        val active = store.activeJobs()
        val current = active.lastOrNull()
        _uiState.value = if (current == null) {
            PhoneSettlementUiState()
        } else {
            val app = current.appName.ifBlank { current.packageName }.ifBlank { "app" }
            PhoneSettlementUiState(
                isActive = true,
                appName = app,
                headline = if (paused.get()) {
                    "Paused distilling $app — will resume when Ditto is back"
                } else {
                    "Distilling $app into reusable tools"
                },
                detail = when (current.stage) {
                    PhoneSettlementStage.Queued -> "Saving the click trace"
                    PhoneSettlementStage.Catalogued -> "Validating the distilled flow by replay"
                    PhoneSettlementStage.Validating -> "Writing the $app skill and MCP tools"
                    PhoneSettlementStage.SkillWritten -> "Publishing distilled MCP tools"
                    PhoneSettlementStage.Done, PhoneSettlementStage.Failed -> current.error
                },
            )
        }
    }
}

internal object PhoneAppSkillMarkdown {
    data class Rendered(val description: String, val body: String)

    fun render(catalog: PhoneAppCatalog): Rendered {
        val app = catalog.appName.ifBlank { catalog.packageName }
        val ready = catalog.readyPrimitives()
        val emerging = catalog.primitives.filterNot { it in ready }
        val description = "Operate $app on this phone using distilled Agent Mode flows (search, open post, and similar)."
            .replace("Aether", "Ditto")
        val body = buildString {
            appendLine("Use these distilled tools instead of tapping from scratch when the user asks about $app.")
            appendLine()
            appendLine("## Native Kimi retrieval contract")
            appendLine("1. Call `recall_gui_flows(task=...)` to retrieve only verified matching SOPs.")
            appendLine("2. Prefer `run_gui_pipeline` when several Ready macros cover the task; otherwise `run_gui_flow(sop_id=..., text=...)`.")
            appendLine("3. Macros locate controls on the tree. On MACRO_MISMATCH/SCENE_NOT_SOP/CAPTURE_TIMEOUT/DISPLAY_BUSY, replan only that segment (`degraded_to=replan`). Do not say the MCP backend is down.")
            appendLine()
            if (ready.isNotEmpty()) {
                appendLine("## Ready SOP ids (call via recall / run_gui_flow, not as extra tools/list names)")
                ready.forEach { primitive ->
                    append("- `").append(primitive.toolName).append("`: ").append(primitive.description)
                    if (primitive.acceptsQuery) append(" Argument `text` is pasted into the flow.")
                    append(" sop_id=").append(primitive.sopId)
                    append(" confidence=").append("%.2f".format(primitive.confidence))
                    append(" source=").append(primitive.provenance)
                    if (primitive.guiLabels.isNotEmpty()) {
                        append(" gui_labels=").append(primitive.guiLabels.joinToString("|") { it.take(32) })
                    }
                    appendLine()
                }
                appendLine()
            }
            if (emerging.isNotEmpty()) {
                appendLine("## Still warming up")
                emerging.forEach { primitive ->
                    append("- `").append(primitive.toolName).append("` (")
                    append(primitive.successCount).append("/").append(PhoneAppPrimitive.ReadyAfterSuccesses)
                    append("): ").append(primitive.title)
                    appendLine()
                }
                appendLine()
            }
            appendLine("These tools are available only to Kimi Code's native `phone` subagent through the `phone_app` MCP server.")
            appendLine("phone_app tools/list exposes recall_gui_flows, run_gui_flow, and run_gui_pipeline. Named macros stay callable by sop_id.")
            appendLine("On mismatch or capture timeout, replan the current segment. Never tell the user the MCP backend is unavailable.")
        }
        return Rendered(description = description, body = body)
    }
}
