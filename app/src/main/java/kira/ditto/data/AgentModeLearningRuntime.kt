package kira.ditto.data

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class GuiTrajectoryVerdict(val storageValue: String) {
    Active("active"),
    Success("success"),
    Failure("failure"),
    Unverified("unverified"),
    Cancelled("cancelled"),
}

data class GuiTrajectoryEvent(
    val sequence: Int,
    val createdAtMillis: Long,
    val action: String,
    val source: String,
    val argumentsJson: String = "{}",
    val ok: Boolean = true,
    val errorCode: String = "",
    val failureCode: String = "",
    val repairStrategy: String = "",
    val packageName: String = "",
    val appName: String = "",
    val guiLabel: String = "",
    val guiBounds: String = "",
    val uiFingerprint: String = "",
    val sopId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sequence", sequence)
        .put("created_at_millis", createdAtMillis)
        .put("action", action)
        .put("source", source)
        .put("arguments", runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() })
        .put("ok", ok)
        .put("error_code", errorCode)
        .put("failure_code", failureCode)
        .put("repair_strategy", repairStrategy)
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("gui_label", guiLabel)
        .put("gui_bounds", guiBounds)
        .put("ui_fingerprint", uiFingerprint)
        .put("sop_id", sopId)
}

data class GuiTrajectoryAttempt(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val goal: String,
    val verdict: GuiTrajectoryVerdict = GuiTrajectoryVerdict.Active,
    val report: String = "",
    val packageName: String = "",
    val appName: String = "",
    val userTaught: Boolean = false,
    val candidateSopIds: List<String> = emptyList(),
    val usedSopIds: List<String> = emptyList(),
    val events: List<GuiTrajectoryEvent> = emptyList(),
    val steps: List<AgentModeSopStep> = emptyList(),
    val startedAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("id", id)
        .put("session_id", sessionId)
        .put("turn_id", turnId)
        .put("goal", goal)
        .put("verdict", verdict.storageValue)
        .put("report", report)
        .put("package_name", packageName)
        .put("app_name", appName)
        .put("user_taught", userTaught)
        .put("candidate_sop_ids", JSONArray(candidateSopIds))
        .put("used_sop_ids", JSONArray(usedSopIds))
        .put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("started_at_millis", startedAtMillis)
        .put("completed_at_millis", completedAtMillis)
}

/**
 * Crash-safe local evidence store. The action trace and its task verdict are
 * deliberately separate concepts, mirroring Raven's trajectory/verdict split.
 */
class GuiTrajectoryStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    fun rootPath(): File = root

    fun upsert(attempt: GuiTrajectoryAttempt) = lock.withLock {
        root.mkdirs()
        val target = File(root, "${attempt.id}.json")
        val staging = File(root, ".${attempt.id}.${UUID.randomUUID()}.tmp")
        staging.writeText(attempt.toJson().toString())
        if (!staging.renameTo(target)) {
            target.writeText(staging.readText())
            staging.delete()
        }
    }

    fun appendVerdict(
        attemptId: String,
        verdict: GuiTrajectoryVerdict,
        source: String,
        summary: String,
    ) = lock.withLock {
        root.mkdirs()
        val item = JSONObject()
            .put("attempt_id", attemptId)
            .put("verdict", verdict.storageValue)
            .put("source", source)
            .put("summary", summary.take(1_000))
            .put("created_at_millis", System.currentTimeMillis())
        File(root, "verdicts.jsonl").appendText(item.toString() + "\n")
    }

    fun load(id: String): GuiTrajectoryAttempt? = lock.withLock {
        val file = File(root, "$id.json")
        if (!file.isFile) return@withLock null
        runCatching { parseAttempt(JSONObject(file.readText())) }.getOrNull()
    }

    private fun parseAttempt(json: JSONObject): GuiTrajectoryAttempt {
        val eventItems = json.optJSONArray("events") ?: JSONArray()
        val stepItems = json.optJSONArray("steps") ?: JSONArray()
        return GuiTrajectoryAttempt(
            id = json.optString("id"),
            sessionId = json.optString("session_id"),
            turnId = json.optString("turn_id"),
            goal = json.optString("goal"),
            verdict = GuiTrajectoryVerdict.entries.firstOrNull {
                it.storageValue == json.optString("verdict")
            } ?: GuiTrajectoryVerdict.Unverified,
            report = json.optString("report"),
            packageName = json.optString("package_name"),
            appName = json.optString("app_name"),
            userTaught = json.optBoolean("user_taught"),
            candidateSopIds = json.optJSONArray("candidate_sop_ids").toStringList(),
            usedSopIds = json.optJSONArray("used_sop_ids").toStringList(),
            events = buildList {
                for (index in 0 until eventItems.length()) {
                    val item = eventItems.optJSONObject(index) ?: continue
                    add(
                        GuiTrajectoryEvent(
                            sequence = item.optInt("sequence"),
                            createdAtMillis = item.optLong("created_at_millis"),
                            action = item.optString("action"),
                            source = item.optString("source"),
                            argumentsJson = item.optJSONObject("arguments")?.toString() ?: "{}",
                            ok = item.optBoolean("ok", true),
                            errorCode = item.optString("error_code"),
                            failureCode = item.optString("failure_code"),
                            repairStrategy = item.optString("repair_strategy"),
                            packageName = item.optString("package_name"),
                            appName = item.optString("app_name"),
                            guiLabel = item.optString("gui_label"),
                            guiBounds = item.optString("gui_bounds"),
                            uiFingerprint = item.optString("ui_fingerprint"),
                            sopId = item.optString("sop_id"),
                        ),
                    )
                }
            },
            steps = buildList {
                for (index in 0 until stepItems.length()) {
                    stepItems.optJSONObject(index)?.let { add(AgentModeSopStep.fromJson(it)) }
                }
            },
            startedAtMillis = json.optLong("started_at_millis"),
            completedAtMillis = json.optionalLong("completed_at_millis"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }
}

data class GuiLearningCompletion(
    val attempt: GuiTrajectoryAttempt,
    val sop: AgentModeSop? = null,
)

data class PhoneGuiStepUi(
    val id: String,
    val sequence: Int,
    val action: String,
    val label: String,
    val thought: String,
    val ok: Boolean = true,
)

internal fun PhoneGuiStepUi.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("sequence", sequence)
    .put("action", action)
    .put("label", label)
    .put("thought", thought)
    .put("ok", ok)

internal fun encodePhoneGuiSteps(steps: List<PhoneGuiStepUi>): String {
    if (steps.isEmpty()) return ""
    return JSONArray().apply { steps.forEach { put(it.toJson()) } }.toString()
}

internal fun parsePhoneGuiSteps(json: String): List<PhoneGuiStepUi> {
    if (json.isBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                PhoneGuiStepUi(
                    id = item.optString("id").ifBlank { "${item.optInt("sequence")}" },
                    sequence = item.optInt("sequence"),
                    action = item.optString("action"),
                    label = item.optString("label"),
                    thought = item.optString("thought"),
                    ok = item.optBoolean("ok", true),
                ),
            )
        }
    }
}

internal data class CompletedPhoneReport(
    val app: String,
    val marker: String,
    val summary: String,
)

internal fun formatPhoneGuiStepLabel(
    action: String,
    target: String,
    appName: String = "",
): String {
    val name = target.ifBlank { appName }
    return when (action) {
        "launch" -> "打开${name.ifBlank { "应用" }}"
        "tap", "click_node" -> if (name.isNotBlank()) "点击$name" else "点击"
        "long_press" -> if (name.isNotBlank()) "长按$name" else "长按"
        "double_tap" -> "双击"
        "swipe", "fling" -> "滑动"
        "swipe_left" -> "左滑"
        "swipe_right" -> "右滑"
        "swipe_up" -> "上滑"
        "swipe_down" -> "下滑"
        "pinch" -> "捏合"
        "search" -> if (name.isNotBlank()) "搜索$name" else "搜索"
        "text" -> "输入"
        "clear_text" -> "清空"
        "back" -> "返回"
        "home" -> "主屏幕"
        "key" -> "按键"
        "wait_for_label" -> if (name.isNotBlank()) "等待$name" else "等待"
        "dump_tree" -> when (name.lowercase()) {
            "top" -> "读取上半屏"
            "middle" -> "读取中部"
            "bottom" -> "读取下半屏"
            "left" -> "读取左半屏"
            "right" -> "读取右半屏"
            else -> "读取界面"
        }
        "list_targets" -> "查找控件"
        "screenshot", "capture" -> "截屏"
        "listen_start" -> "开始听课"
        "listen_status" -> "听课进度"
        "listen_stop" -> "结束听课"
        "list_apps" -> "列出应用"
        "scroll_until" -> if (name.isNotBlank()) "滚动到$name" else "滚动查找"
        else -> if (action.startsWith("flow:")) "流程" else name.ifBlank { action }
    }
}

internal fun phoneGuiStepFromEvent(event: GuiTrajectoryEvent, goal: String): PhoneGuiStepUi {
    val argumentJson = runCatching { JSONObject(event.argumentsJson) }.getOrNull()
    val argumentTarget = argumentJson?.optString("target").orEmpty()
    val region = argumentJson?.optString("region").orEmpty()
    val searchQuery = argumentJson?.optString("text").orEmpty()
        .ifBlank { argumentJson?.optString("query").orEmpty() }
    val target = when (event.action) {
        "dump_tree", "list_targets" -> region.ifBlank { argumentTarget }
        "search" -> searchQuery.ifBlank { argumentTarget }
        else -> event.guiLabel.ifBlank { argumentTarget }.ifBlank { event.appName }
    }
    val label = formatPhoneGuiStepLabel(event.action, target, event.appName)
    val thought = buildString {
        append(label)
        if (event.appName.isNotBlank() && event.action != "launch") {
            append(" · ").append(event.appName)
        }
        if (!event.ok) {
            append(" · 失败")
            if (event.errorCode.isNotBlank()) append(" ").append(event.errorCode)
        }
        if (goal.isNotBlank()) {
            append('\n').append("任务：").append(goal.take(160))
        }
        if (event.guiLabel.isNotBlank()) {
            append('\n').append("gui_label=").append(event.guiLabel)
        }
    }
    return PhoneGuiStepUi(
        id = "${event.sequence}-${event.createdAtMillis}",
        sequence = event.sequence,
        action = event.action,
        label = label,
        thought = thought,
        ok = event.ok,
    )
}

internal fun hasEnoughGuiStepsForSkill(steps: List<AgentModeSopStep>): Boolean {
    val reusable = steps.filter { step ->
        step.resultOk && step.action in AgentModeLearningRuntime.ReusableActions
    }
    return reusable.size >= 2
}

/**
 * Learning boundary for native Kimi Code Agent Mode. It observes the MCP calls
 * made by Kimi's `phone` subagent; it never runs a second model loop.
 */
class AgentModeLearningRuntime(
    private val trajectoryStore: GuiTrajectoryStore,
    private val sopStore: AgentModeSopStore,
    private val settlementRuntime: PhoneSettlementRuntime?,
    private val sceneStore: GuiSceneStore? = null,
    private val guiStepStoreDir: File? = null,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val isTeachingActive: () -> Boolean = { true },
    private val signalStore: GuiSignalStore? = GuiSignalStore(File(trajectoryStore.rootPath(), "signals")),
) : AgentModeUserGestureListener {
    private val lock = ReentrantLock()
    private val attempts = linkedMapOf<String, MutableAttempt>()
    private val _reviewingEverMe = MutableStateFlow(false)
    val reviewingEverMe: StateFlow<Boolean> = _reviewingEverMe.asStateFlow()
    private val _liveGuiSteps = MutableStateFlow<List<PhoneGuiStepUi>>(emptyList())
    val liveGuiSteps: StateFlow<List<PhoneGuiStepUi>> = _liveGuiSteps.asStateFlow()
    private val _liveGuiStepsByToolCall = MutableStateFlow<Map<String, List<PhoneGuiStepUi>>>(emptyMap())
    val liveGuiStepsByToolCall: StateFlow<Map<String, List<PhoneGuiStepUi>>> =
        _liveGuiStepsByToolCall.asStateFlow()

    init {
        loadPersistedGuiSteps()
    }

    fun guiStepsForToolCall(toolCallId: String): List<PhoneGuiStepUi> {
        val live = _liveGuiStepsByToolCall.value[toolCallId]
        if (!live.isNullOrEmpty()) return live
        return parsePhoneGuiSteps(readPersistedGuiSteps(toolCallId))
    }

    fun guiStepsJsonForToolCall(toolCallId: String): String {
        val live = encodePhoneGuiSteps(guiStepsForToolCall(toolCallId))
        if (live.isNotBlank()) return live
        return readPersistedGuiSteps(toolCallId)
    }
    @Volatile
    private var pendingEverMeFact: String = ""
    @Volatile
    var lastCommittedScene: GuiSceneCard? = null
        private set
    @Volatile
    private var pendingWatchSchedule: GuiWatchScheduleAction? = null

    fun clearEverMeReview() {
        _reviewingEverMe.value = false
    }

    fun everMeCommitPrompt(): String = pendingEverMeFact

    fun consumeEverMeCommitPrompt(): String = lock.withLock {
        val fact = pendingEverMeFact
        pendingEverMeFact = ""
        fact
    }

    internal fun consumeWatchSchedule(): GuiWatchScheduleAction? = lock.withLock {
        val action = pendingWatchSchedule
        pendingWatchSchedule = null
        action
    }

    fun recallScenes(task: String, packageName: String = ""): List<GuiSceneCard> =
        sceneStore?.recall(task, packageName).orEmpty()

    fun beginTurn(sessionId: String, turnId: String, goal: String) = lock.withLock {
        if (sessionId.isBlank() || goal.isBlank()) return@withLock
        attempts.remove(sessionId)?.let { abandoned ->
            persistFinal(
                abandoned,
                verdict = GuiTrajectoryVerdict.Unverified,
                report = "A newer Agent Mode turn replaced this active trajectory.",
                verdictSource = "turn_replaced",
            )
            persistTurnLearning(
                attempt = abandoned,
                verdict = GuiTrajectoryVerdict.Unverified,
                report = "A newer Agent Mode turn replaced this active trajectory.",
                sop = null,
                enqueueSettlement = false,
            )
        }
        _liveGuiSteps.value = emptyList()
        val attempt = MutableAttempt(
            id = "gui-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            turnId = turnId,
            goal = goal.trim(),
            candidateSopIds = sopStore.findRelevant(goal).map(AgentModeSop::id),
        )
        attempts[sessionId] = attempt
        trajectoryStore.upsert(attempt.snapshot())
        diagnosticLogger.event(
            category = "agent_mode_learning",
            event = "trajectory_started",
            sessionId = sessionId,
            turnId = turnId,
            details = mapOf("candidate_sop_count" to attempt.candidateSopIds.size),
        )
    }

    fun ensureTurn(sessionId: String, goal: String) = lock.withLock {
        if (sessionId.isBlank() || goal.isBlank()) return@withLock
        if (attempts.containsKey(sessionId)) return@withLock
        val attempt = MutableAttempt(
            id = "gui-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            sessionId = sessionId,
            turnId = "teach-${System.currentTimeMillis()}",
            goal = goal.trim(),
            candidateSopIds = sopStore.findRelevant(goal).map(AgentModeSop::id),
        )
        attempts[sessionId] = attempt
        trajectoryStore.upsert(attempt.snapshot())
    }

    /** Returns the original tool JSON decorated with a compact EverMe-friendly GUI tag. */
    fun recordAgentDisplayCall(
        sessionId: String,
        arguments: JSONObject,
        rawOutput: String,
    ): String = lock.withLock {
        val actions = arguments.optJSONArray("actions")
        if (actions != null && actions.length() > 0) {
            var tagged = rawOutput
            for (index in 0 until actions.length()) {
                tagged = recordOneAgentDisplayCall(sessionId, actions.getJSONObject(index), tagged)
            }
            return@withLock tagged
        }
        recordOneAgentDisplayCall(sessionId, arguments, rawOutput)
    }

    private fun recordOneAgentDisplayCall(
        sessionId: String,
        arguments: JSONObject,
        rawOutput: String,
    ): String {
        val attempt = activeAttempt(sessionId) ?: return rawOutput
        val result = runCatching { JSONObject(rawOutput) }.getOrNull() ?: return rawOutput
        val action = PhoneUiMcp.normalizeAction(arguments.optString("action"))
        val ok = result.optBoolean("ok", true)
        val previousTree = attempt.lastUiTree
        val uiTree = result.optString("ui_tree").ifBlank {
            result.optString("click_targets_text")
        }
        val target = resolveGuiTarget(action, arguments, previousTree.ifBlank { uiTree })
        val packageName = result.optString("package_name")
            .ifBlank { if (action == "launch") arguments.optString("target").takeIf(::looksLikePackage).orEmpty() else "" }
            .ifBlank { attempt.packageName }
        val appName = result.optString("app_name")
            .ifBlank { if (action == "launch") arguments.optString("target") else "" }
            .ifBlank { attempt.appName }
        attempt.packageName = packageName
        attempt.appName = appName
        val fingerprint = fingerprint(previousTree.ifBlank { uiTree })
        val pageTargetCount = AgentModeUiTree.clickTargetsFromCompact(previousTree.ifBlank { uiTree }).size
        val inputSlot = if (action == "text") "text" else ""
        val event = GuiTrajectoryEvent(
            sequence = attempt.nextSequence(),
            createdAtMillis = System.currentTimeMillis(),
            action = action,
            source = AgentModeSopProvenance.KimiAgent.storageValue,
            argumentsJson = sanitizedArguments(arguments).toString(),
            ok = ok,
            errorCode = result.optString("code"),
            failureCode = GuiFailureSignals.failureCode(result),
            repairStrategy = result.optJSONObject("failure")?.optString("repair_strategy").orEmpty(),
            packageName = packageName,
            appName = appName,
            guiLabel = target?.label.orEmpty(),
            guiBounds = target?.bounds.orEmpty(),
            uiFingerprint = fingerprint,
        )
        attempt.events += event
        if (!ok) {
            signalStore?.appendReceipt(
                attemptId = attempt.id,
                where = "agent_display/$action",
                result = result,
                sopId = event.sopId,
            )
        }
        if (action in ReusableActions && ok) {
            val lastIndex = attempt.steps.lastIndex
            if (lastIndex >= 0 && uiTree.isNotBlank() && attempt.steps[lastIndex].toFingerprint.isBlank()) {
                attempt.steps[lastIndex] = attempt.steps[lastIndex].copy(
                    toFingerprint = fingerprint(uiTree),
                )
            }
            attempt.steps += stepFrom(
                action = action,
                source = AgentModeSopProvenance.KimiAgent.storageValue,
                arguments = arguments,
                result = result,
                packageName = packageName,
                appName = appName,
                target = target,
                inputSlot = inputSlot,
                uiFingerprint = fingerprint,
                pageTargetCount = pageTargetCount,
            )
        }
        if (uiTree.isNotBlank()) attempt.lastUiTree = uiTree
        trajectoryStore.upsert(attempt.snapshot())
        publishLiveGuiSteps(attempt)
        result.put("gui_learning", learningTag(attempt, event, inputSlot))
        if (event.sequence == 1 && attempt.candidateSopIds.isNotEmpty()) {
            result.put(
                "candidate_sops",
                JSONArray().apply {
                    attempt.candidateSopIds.take(3).forEach { sopId ->
                        val sop = sopStore.findById(sopId) ?: return@forEach
                        put(
                            JSONObject()
                                .put("sop_id", sop.id)
                                .put("goal", sop.goal)
                                .put("maturity", sop.maturity.storageValue)
                                .put("confidence", sop.confidence)
                                .put("gui_labels", JSONArray(sop.steps.map { it.guiLabel }.filter { it.isNotBlank() }.distinct().take(6))),
                        )
                    }
                },
            )
        }
        return result.toString()
    }

    fun recordPhoneAppFlowCall(
        sessionId: String,
        toolName: String,
        arguments: JSONObject,
        rawOutput: String,
    ): String = lock.withLock {
        val attempt = activeAttempt(sessionId) ?: return@withLock rawOutput
        val result = runCatching { JSONObject(rawOutput) }.getOrNull() ?: return@withLock rawOutput
        val sopId = result.optString("sop_id")
        val ok = result.optBoolean("ok", true)
        if (sopId.isNotBlank()) {
            attempt.usedSopIds += sopId
            if (ok) {
                sopStore.findById(sopId)?.steps.orEmpty().forEach { step ->
                    attempt.steps += step.copy(source = "sop_replay")
                }
            }
        }
        attempt.packageName = result.optString("package_name")
            .ifBlank { result.optString("package") }
            .ifBlank { attempt.packageName }
        attempt.appName = result.optString("app_name")
            .ifBlank { result.optString("app") }
            .ifBlank { attempt.appName }
        result.optString("ui_tree").takeIf { it.isNotBlank() }?.let { attempt.lastUiTree = it }
        val event = GuiTrajectoryEvent(
            sequence = attempt.nextSequence(),
            createdAtMillis = System.currentTimeMillis(),
            action = "flow:$toolName",
            source = "distilled_mcp",
            argumentsJson = sanitizedArguments(arguments).toString(),
            ok = ok,
            errorCode = result.optString("code"),
            failureCode = GuiFailureSignals.failureCode(result),
            repairStrategy = result.optJSONObject("failure")?.optString("repair_strategy").orEmpty(),
            packageName = attempt.packageName,
            appName = attempt.appName,
            uiFingerprint = fingerprint(attempt.lastUiTree),
            sopId = sopId,
        )
        attempt.events += event
        if (!ok) {
            signalStore?.appendReceipt(
                attemptId = attempt.id,
                where = "phone_app/${event.action}",
                result = result,
                sopId = sopId,
            )
        }
        trajectoryStore.upsert(attempt.snapshot())
        publishLiveGuiSteps(attempt)
        result.put("gui_learning", learningTag(attempt, event, ""))
        result.toString()
    }

    fun observeNativeAgentEvent(sessionId: String, event: AgentToolEvent): String? = lock.withLock {
        val attempt = activeAttempt(sessionId) ?: return@withLock null
        val arguments = runCatching { JSONObject(event.argumentsJson) }.getOrNull()
        val isPhoneAgent = isPhoneSubagentEvent(event.name, arguments)
        if (isPhoneAgent) {
            bindPhoneToolCall(attempt, event.id)
        }
        if (event.id !in attempt.phoneAgentCallIds || event.outputJson == null || event.isRunning == true) {
            return@withLock null
        }
        val output = event.outputJson.orEmpty()
        attempt.phoneVerdict = when {
            output.contains(SuccessMarker, ignoreCase = true) -> {
                pendingWatchSchedule = GuiWatchScheduleAction(sessionId, intervalMillis = null)
                GuiTrajectoryVerdict.Success
            }
            output.contains(WatchingMarker, ignoreCase = true) -> {
                attempt.watching = true
                pendingWatchSchedule = GuiWatchScheduleAction(
                    sessionId = sessionId,
                    intervalMillis = parseWatchingIntervalMillis(output),
                )
                GuiTrajectoryVerdict.Unverified
            }
            output.contains(NeedsTeachingMarker, ignoreCase = true) -> {
                pendingWatchSchedule = GuiWatchScheduleAction(sessionId, intervalMillis = null)
                GuiTrajectoryVerdict.Unverified
            }
            output.contains(FailureMarker, ignoreCase = true) -> {
                pendingWatchSchedule = GuiWatchScheduleAction(sessionId, intervalMillis = null)
                GuiTrajectoryVerdict.Failure
            }
            else -> GuiTrajectoryVerdict.Unverified
        }
        attempt.phoneReport = extractReport(output)
        mergeCompletedReports(attempt, event, output)
        if (attempt.phoneVerdict == GuiTrajectoryVerdict.Success) {
            stageEverMeFromAttempt(attempt)
        }
        trajectoryStore.upsert(attempt.snapshot(report = attempt.phoneReport))
        publishLiveGuiSteps(attempt)
        val fact = attempt.sceneCard?.toEverMeFact().orEmpty().ifBlank { pendingEverMeFact }
        val decorated = decoratePhoneAgentOutput(
            output = output,
            reports = attempt.completedReports.toList(),
            fact = if (fact.isNotBlank() && attempt.phoneVerdict == GuiTrajectoryVerdict.Success) {
                fact
            } else {
                ""
            },
        )
        decorated.takeIf { it != output }
    }

    fun completeTurn(
        sessionId: String,
        outerTurnSucceeded: Boolean,
        summary: String,
    ): GuiLearningCompletion? = lock.withLock {
        val attempt = attempts.remove(sessionId) ?: return@withLock null
        val hasReusableEvidence = attempt.steps.any { it.action in ReusableActions } || attempt.usedSopIds.isNotEmpty()
        val verdict = when {
            !outerTurnSucceeded -> GuiTrajectoryVerdict.Failure
            attempt.phoneVerdict == GuiTrajectoryVerdict.Failure -> GuiTrajectoryVerdict.Failure
            attempt.phoneVerdict == GuiTrajectoryVerdict.Success && hasReusableEvidence ->
                GuiTrajectoryVerdict.Success
            // A user touching the live virtual phone is an explicit teaching takeover.
            // The successful outer turn is the verdict when the phone subagent did not
            // emit its marker after being corrected by the user.
            attempt.userTaught && hasReusableEvidence -> GuiTrajectoryVerdict.Success
            else -> GuiTrajectoryVerdict.Unverified
        }
        val report = attempt.phoneReport.ifBlank { summary }.take(2_000)
        val finalAttempt = persistFinal(
            attempt = attempt,
            verdict = verdict,
            report = report,
            verdictSource = if (attempt.phoneVerdict == GuiTrajectoryVerdict.Active) "outer_turn" else "phone_agent",
        )
        if (verdict == GuiTrajectoryVerdict.Success || verdict == GuiTrajectoryVerdict.Failure) {
            attempt.usedSopIds.distinct().forEach { sopId ->
                sopStore.recordFeedback(sopId, succeeded = verdict == GuiTrajectoryVerdict.Success)
                val failureCode = attempt.events
                    .filter { it.sopId == sopId }
                    .map { it.failureCode }
                    .lastOrNull { it.isNotBlank() }
                    .orEmpty()
                if (failureCode.isNotBlank()) {
                    sopStore.recordFailureSignal(sopId, failureCode)
                }
            }
        }
        val containsFreshActions = attempt.steps.any { it.source != "sop_replay" }
        val sop = when {
            attempt.promotedSop != null -> attempt.promotedSop
            verdict == GuiTrajectoryVerdict.Success && attempt.userTaught ->
                promoteTeaching(attempt, enqueueSettlement = true, report = report)
            verdict == GuiTrajectoryVerdict.Success && containsFreshActions -> {
                promoteAutomaticSuccess(attempt, report)
            }
            else -> null
        }
        diagnosticLogger.event(
            category = "agent_mode_learning",
            event = "trajectory_completed",
            level = if (verdict == GuiTrajectoryVerdict.Failure) "warn" else "info",
            sessionId = sessionId,
            turnId = attempt.turnId,
            details = mapOf(
                "verdict" to verdict.storageValue,
                "event_count" to attempt.events.size,
                "step_count" to attempt.steps.size,
                "user_taught" to attempt.userTaught,
                "sop_id" to sop?.id.orEmpty(),
            ),
        )
        persistTurnLearning(
            attempt = attempt,
            verdict = verdict,
            report = report,
            sop = sop,
            enqueueSettlement = sop == null,
        )
        GuiLearningCompletion(attempt = finalAttempt, sop = sop)
    }

    fun commitTeaching(sessionId: String): GuiLearningCompletion? = lock.withLock {
        val attempt = activeAttempt(sessionId) ?: mostRecentAttempt() ?: return@withLock null
        if (!attempt.userTaught && attempt.steps.none { it.source == AgentModeSopProvenance.UserTeaching.storageValue }) {
            return@withLock null
        }
        val summary = distilledSummary(attempt)
        val sop = promoteTeaching(attempt, enqueueSettlement = true, report = summary)
        publishEverMe(attempt, sop, summary)
        _reviewingEverMe.value = true
        GuiLearningCompletion(attempt = attempt.snapshot(), sop = sop)
    }

    fun cancelTurn(sessionId: String, summary: String = "Turn cancelled.") = lock.withLock {
        attempts.remove(sessionId)?.let { attempt ->
            persistFinal(
                attempt = attempt,
                verdict = GuiTrajectoryVerdict.Cancelled,
                report = summary,
                verdictSource = "cancelled",
            )
            persistTurnLearning(
                attempt = attempt,
                verdict = GuiTrajectoryVerdict.Cancelled,
                report = summary,
                sop = null,
                enqueueSettlement = false,
            )
        }
    }

    override fun onUserTap(normalizedX: Int, normalizedY: Int) = lock.withLock {
        if (!isTeachingActive()) return@withLock
        val attempt = mostRecentAttempt() ?: return@withLock
        val arguments = JSONObject().put("action", "tap").put("x", normalizedX).put("y", normalizedY)
        recordUserTeachingStep(attempt, "tap", arguments)
    }

    override fun onUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    ) = lock.withLock {
        if (!isTeachingActive()) return@withLock
        val attempt = mostRecentAttempt() ?: return@withLock
        val arguments = JSONObject()
            .put("action", "swipe")
            .put("x1", normalizedX1)
            .put("y1", normalizedY1)
            .put("x2", normalizedX2)
            .put("y2", normalizedY2)
            .put("duration_ms", durationMs)
        recordUserTeachingStep(attempt, "swipe", arguments)
    }

    override fun onTeachingPageSettled(compactTree: String) = lock.withLock {
        if (compactTree.isBlank()) return@withLock
        val attempt = mostRecentAttempt() ?: return@withLock
        attempt.lastUiTree = compactTree
        val to = fingerprint(compactTree)
        val lastIndex = attempt.steps.indexOfLast {
            it.source == AgentModeSopProvenance.UserTeaching.storageValue
        }
        if (lastIndex >= 0) {
            attempt.steps[lastIndex] = attempt.steps[lastIndex].copy(toFingerprint = to)
        }
        trajectoryStore.upsert(attempt.snapshot())
    }

    private fun recordUserTeachingStep(
        attempt: MutableAttempt,
        action: String,
        arguments: JSONObject,
    ) {
        val fromTree = attempt.lastUiTree
        val target = resolveGuiTarget(action, arguments, fromTree)
        val fingerprint = fingerprint(fromTree)
        val pageTargetCount = AgentModeUiTree.clickTargetsFromCompact(fromTree).size
        attempt.userTaught = true
        val event = GuiTrajectoryEvent(
            sequence = attempt.nextSequence(),
            createdAtMillis = System.currentTimeMillis(),
            action = action,
            source = AgentModeSopProvenance.UserTeaching.storageValue,
            argumentsJson = sanitizedArguments(arguments).toString(),
            packageName = attempt.packageName,
            appName = attempt.appName,
            guiLabel = target?.label.orEmpty(),
            guiBounds = target?.bounds.orEmpty(),
            uiFingerprint = fingerprint,
        )
        attempt.events += event
        attempt.steps += stepFrom(
            action = action,
            source = AgentModeSopProvenance.UserTeaching.storageValue,
            arguments = arguments,
            result = JSONObject().put("ok", true),
            packageName = attempt.packageName,
            appName = attempt.appName,
            target = target,
            inputSlot = "",
            uiFingerprint = fingerprint,
            pageTargetCount = pageTargetCount,
        )
        trajectoryStore.upsert(attempt.snapshot())
        publishLiveGuiSteps(attempt)
    }

    private fun promoteTeaching(
        attempt: MutableAttempt,
        enqueueSettlement: Boolean,
        report: String,
    ): AgentModeSop? {
        attempt.promotedSop?.let { return it }
        val distilled = TeachingTraceDistiller.distill(attempt.steps)
        attempt.everMeSummary = distilled.everMeSummary
        val launches = attempt.steps.filter { step ->
            step.action == "launch" &&
                step.resultOk &&
                step.source != AgentModeSopProvenance.UserTeaching.storageValue
        }
        val replay = launches + distilled.replay
        if (replay.isEmpty()) {
            attempt.teachingCommitted = true
            persistScene(attempt, distilled, sop = null)
            return null
        }
        val sop = promoteReplaySegments(
            attempt = attempt,
            distilled = distilled,
            replay = replay,
            provenance = AgentModeSopProvenance.UserTeaching,
            enqueueSettlement = enqueueSettlement,
            report = report,
            promoteMethodImmediately = false,
        )
        attempt.teachingCommitted = true
        return sop
    }

    private fun promoteAutomaticSuccess(
        attempt: MutableAttempt,
        report: String,
    ): AgentModeSop? {
        attempt.promotedSop?.let { return it }
        val distilled = TeachingTraceDistiller.distill(attempt.steps)
        attempt.everMeSummary = distilled.everMeSummary
        val launches = attempt.steps.filter { step ->
            step.action == "launch" &&
                step.resultOk &&
                step.source != AgentModeSopProvenance.UserTeaching.storageValue
        }
        val replay = (launches + distilled.replay).distinctBy { step ->
            listOf(step.action, step.locator, step.resourceId, step.guiLabel, step.inputSlot)
        }
        if (replay.isNotEmpty()) {
            return promoteReplaySegments(
                attempt = attempt,
                distilled = distilled,
                replay = replay,
                provenance = AgentModeSopProvenance.KimiAgent,
                enqueueSettlement = true,
                report = report,
                promoteMethodImmediately = true,
            )
        }
        val raw = attempt.steps.filter { it.resultOk && it.action in ReusableActions }
        if (raw.isEmpty()) return null
        return promoteReplaySegments(
            attempt = attempt,
            distilled = distilled,
            replay = raw,
            provenance = AgentModeSopProvenance.KimiAgent,
            enqueueSettlement = true,
            report = report,
            promoteMethodImmediately = false,
        )
    }

    private fun promoteReplaySegments(
        attempt: MutableAttempt,
        distilled: TeachingTraceDistiller.Result,
        replay: List<AgentModeSopStep>,
        provenance: AgentModeSopProvenance,
        enqueueSettlement: Boolean,
        report: String,
        promoteMethodImmediately: Boolean,
    ): AgentModeSop? {
        val baseGoal = GuiSceneAbstractor.sceneGoal(
            appName = attempt.appName.ifBlank { attempt.packageName },
            goal = attempt.goal,
        )
        val segments = GuiFlowSegmenter.segment(replay).ifEmpty {
            listOf(
                GuiFlowSegment(
                    sceneKey = GuiFlowSegmenter.sceneKey(replay),
                    steps = replay,
                    methodOnly = replay.none {
                        it.kind == TeachingTraceDistiller.KindInstance ||
                            it.inputSlot == TeachingTraceDistiller.PickSlot
                    },
                    slots = replay.map { it.inputSlot }.filter { it.isNotBlank() }.distinct(),
                ),
            )
        }
        var last: AgentModeSop? = null
        segments.forEach { segment ->
            val sop = sopStore.recordVerifiedSuccess(
                goal = "$baseGoal · ${segment.sceneKey}",
                steps = segment.steps,
                provenance = provenance,
                promoteImmediately = provenance == AgentModeSopProvenance.UserTeaching ||
                    (promoteMethodImmediately && segment.isLaunchOnly()),
                riskLevel = if (segment.methodOnly) "low" else "medium",
                validationState = when {
                    // A human watched this happen.
                    provenance == AgentModeSopProvenance.UserTeaching -> "validated"
                    // Launching an app by package is deterministic: there are no coordinates and no
                    // list position, so a replay cannot disconfirm anything a first run established.
                    segment.isLaunchOnly() -> "validated"
                    // Everything else waits for one replay, `methodOnly` included. Addressing a
                    // durable affordance rather than a particular list item is a reason to trust a
                    // flow *sooner* - not a reason to trust it after a single run the agent both
                    // performed and graded itself. This is where an automatically distilled tap was
                    // reaching Ready on one observation.
                    else -> "pending_replay"
                },
                environmentFingerprint = attempt.steps.map { it.uiFingerprint }
                    .lastOrNull { it.isNotBlank() }
                    .orEmpty(),
            )
            last = sop
            if (enqueueSettlement) {
                settlementRuntime?.enqueue(
                    goal = "$baseGoal · ${segment.sceneKey}",
                    summary = report.ifBlank { distilled.everMeSummary }.ifBlank { attempt.goal },
                    steps = segment.steps,
                    sop = sop,
                    packageName = sop.packageName.ifBlank { attempt.packageName },
                    appName = sop.appName.ifBlank { attempt.appName },
                )
            }
        }
        attempt.promotedSop = last
        persistScene(attempt, distilled, last)
        return last
    }

    private fun stageEverMeFromAttempt(attempt: MutableAttempt) {
        if (attempt.sceneCard != null) return
        val distilled = TeachingTraceDistiller.distill(attempt.steps)
        if (distilled.replay.isEmpty() && distilled.everMeSummary.isBlank()) return
        persistScene(attempt, distilled, sop = attempt.promotedSop)
    }

    private fun decorateWithEverMeFact(output: String, fact: String): String {
        if (fact.isBlank() || output.contains(EverMeFactMarker)) return output
        val parsed = runCatching { JSONObject(output) }.getOrNull()
        if (parsed != null && (parsed.has("result") || parsed.has("ok") || parsed.has("content"))) {
            parsed.put("everme_scene_fact", fact)
            val result = parsed.optString("result")
            if (result.isNotBlank() && !result.contains(EverMeFactMarker)) {
                parsed.put("result", result.trimEnd() + "\n\n$EverMeFactMarker\n$fact")
            }
            return parsed.toString()
        }
        return output.trimEnd() + "\n\n$EverMeFactMarker\n$fact"
    }

    private fun decoratePhoneAgentOutput(
        output: String,
        reports: List<CompletedPhoneReport>,
        fact: String,
    ): String {
        var result = output
        if (reports.isNotEmpty() && !output.contains("completed_this_turn:")) {
            val digest = buildString {
                appendLine("completed_this_turn:")
                reports.forEach { report ->
                    append("- ")
                    append(report.app.ifBlank { "app" })
                    append(": GUI_TASK_").append(report.marker).append(": ").append(report.summary)
                    appendLine()
                }
                append("Include every item above in the user-facing 简体中文 report; do not omit earlier apps.")
            }
            result = appendLeadBlock(result, digest, reports)
        }
        if (fact.isNotBlank()) {
            result = decorateWithEverMeFact(result, fact)
        }
        return result
    }

    private fun appendLeadBlock(
        output: String,
        block: String,
        reports: List<CompletedPhoneReport>,
    ): String {
        val parsed = runCatching { JSONObject(output) }.getOrNull()
        if (parsed != null && (parsed.has("result") || parsed.has("ok") || parsed.has("content"))) {
            parsed.put(
                "completed_this_turn",
                JSONArray().apply {
                    reports.forEach { report ->
                        put(
                            JSONObject()
                                .put("app", report.app)
                                .put("marker", report.marker)
                                .put("summary", report.summary),
                        )
                    }
                },
            )
            val existing = parsed.optString("result")
            if (existing.isNotBlank()) {
                parsed.put("result", existing.trimEnd() + "\n\n" + block)
            }
            return parsed.toString()
        }
        return output.trimEnd() + "\n\n" + block
    }

    private fun isPhoneSubagentEvent(toolName: String, arguments: JSONObject?): Boolean {
        val profile = arguments?.optString("subagent_type")?.trim().orEmpty()
        if (profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true)) return true
        val title = toolName.lowercase()
        return title.contains("phone agent") ||
            (title.contains("agent swarm") && profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true))
    }

    private fun bindPhoneToolCall(attempt: MutableAttempt, toolCallId: String) {
        if (toolCallId.isBlank()) return
        val previousKey = attempt.currentPhoneToolCallId.ifBlank { attempt.id }
        val isNew = toolCallId !in attempt.phoneAgentCallIds
        attempt.phoneAgentCallIds += toolCallId
        if (isNew && previousKey != toolCallId && previousKey != attempt.id) {
            attempt.eventsAtToolCallStart = attempt.events.size
        }
        if (previousKey != toolCallId && previousKey == attempt.id) {
            val pending = _liveGuiStepsByToolCall.value[attempt.id]
            if (!pending.isNullOrEmpty()) {
                _liveGuiStepsByToolCall.update { current ->
                    current - attempt.id + (toolCallId to pending)
                }
            }
        }
        attempt.currentPhoneToolCallId = toolCallId
        publishLiveGuiSteps(attempt)
    }

    private fun mergeCompletedReports(
        attempt: MutableAttempt,
        event: AgentToolEvent,
        output: String,
    ) {
        val arguments = runCatching { JSONObject(event.argumentsJson) }.getOrNull()
        val items = arguments?.optJSONArray("items")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        val matches = PhoneReportLine.findAll(output).toList()
        if (matches.isEmpty()) {
            val marker = when (attempt.phoneVerdict) {
                GuiTrajectoryVerdict.Success -> "SUCCEEDED"
                GuiTrajectoryVerdict.Failure -> "FAILED"
                else -> return
            }
            upsertCompletedReport(
                attempt,
                CompletedPhoneReport(
                    app = attempt.appName.ifBlank { items.firstOrNull().orEmpty() },
                    marker = marker,
                    summary = attempt.phoneReport.take(240),
                ),
            )
            return
        }
        matches.forEachIndexed { index, match ->
            val app = items.getOrNull(index)
                .orEmpty()
                .ifBlank { attempt.appName }
            upsertCompletedReport(
                attempt,
                CompletedPhoneReport(
                    app = app,
                    marker = match.groupValues[1].uppercase(),
                    summary = match.groupValues[2].trim().take(240),
                ),
            )
        }
    }

    private fun upsertCompletedReport(attempt: MutableAttempt, report: CompletedPhoneReport) {
        val existing = attempt.completedReports.indexOfFirst { item ->
            item.app.equals(report.app, ignoreCase = true) && report.app.isNotBlank()
        }
        if (existing >= 0) {
            attempt.completedReports[existing] = report
        } else {
            attempt.completedReports += report
        }
    }

    private fun persistTurnLearning(
        attempt: MutableAttempt,
        verdict: GuiTrajectoryVerdict,
        report: String,
        sop: AgentModeSop?,
        enqueueSettlement: Boolean,
    ) {
        if (attempt.watching) return
        val hasGui = attempt.events.isNotEmpty() || attempt.steps.isNotEmpty()
        if (!hasGui) return
        if (attempt.sceneCard == null) {
            persistSceneFromAttempt(attempt, verdict, report, sop)
        }
        if (pendingEverMeFact.isBlank()) {
            pendingEverMeFact = attempt.sceneCard?.toEverMeFact().orEmpty()
                .ifBlank { fallbackEverMeFact(attempt, report) }
        }
        val summary = distilledSummary(attempt).ifBlank { fallbackEverMeSummary(attempt, report) }
        if (!attempt.everMePublished) {
            publishEverMe(attempt, sop, summary)
        }
        if (pendingEverMeFact.isNotBlank() || attempt.sceneCard != null) {
            _reviewingEverMe.value = true
        }
        if (enqueueSettlement && sop == null && hasEnoughGuiStepsForSkill(attempt.steps)) {
            val distilled = TeachingTraceDistiller.distill(attempt.steps)
            val steps = distilled.replay.ifEmpty {
                attempt.steps.filter { step ->
                    step.resultOk && step.action in ReusableActions
                }
            }
            if (steps.isNotEmpty()) {
                settlementRuntime?.enqueue(
                    goal = attempt.goal,
                    summary = report.ifBlank { summary }.ifBlank { attempt.goal },
                    steps = steps,
                    sop = null,
                    packageName = attempt.packageName,
                    appName = attempt.appName,
                )
            }
        }
    }

    private fun persistSceneFromAttempt(
        attempt: MutableAttempt,
        verdict: GuiTrajectoryVerdict,
        report: String,
        sop: AgentModeSop?,
    ) {
        val distilled = TeachingTraceDistiller.distill(attempt.steps)
        val fallbackSummary = fallbackEverMeSummary(attempt, report)
        val summary = distilled.everMeSummary.ifBlank { fallbackSummary }
        val stuck = attempt.events.lastOrNull()?.let { event ->
            event.guiLabel.ifBlank { event.action }
        }.orEmpty()
        val withStuck = if (
            verdict != GuiTrajectoryVerdict.Success &&
            stuck.isNotBlank() &&
            !summary.contains(stuck)
        ) {
            "$summary 卡在 $stuck。"
        } else {
            summary
        }
        val replay = distilled.replay.ifEmpty {
            attempt.steps.filter { it.resultOk && it.action in ReusableActions }
        }
        persistScene(
            attempt = attempt,
            distilled = TeachingTraceDistiller.Result(
                repair = distilled.repair,
                replay = replay,
                everMeSummary = withStuck.ifBlank { distilled.everMeSummary },
            ),
            sop = sop,
        )
    }

    private fun fallbackEverMeSummary(attempt: MutableAttempt, report: String = ""): String {
        val app = attempt.appName.ifBlank { attempt.packageName }.ifBlank { "未知应用" }
        val labels = attempt.events.map { it.guiLabel }.filter { it.isNotBlank() }.distinct().take(6)
        val last = attempt.events.lastOrNull()
        return buildString {
            append("GUI 尝试：").append(app)
            if (attempt.goal.isNotBlank()) {
                append("，目标 ").append(attempt.goal.take(80))
            }
            if (labels.isNotEmpty()) {
                append("，控件 ").append(labels.joinToString("、"))
            }
            last?.let { event ->
                append("，停在 ").append(event.guiLabel.ifBlank { event.action })
            }
            val note = attempt.phoneReport.ifBlank { report }.take(240)
            if (note.isNotBlank()) {
                append("。").append(note)
            }
        }
    }

    private fun fallbackEverMeFact(attempt: MutableAttempt, report: String): String {
        val app = attempt.appName.ifBlank { attempt.packageName }.ifBlank { "unknown" }
        return buildString {
            appendLine("#gui #app:${app.take(24)}")
            append("App: ").append(app).appendLine()
            append("Scene: ").append(attempt.goal.take(80)).appendLine()
            append(fallbackEverMeSummary(attempt, report))
        }.trim()
    }

    private fun publishLiveGuiSteps(attempt: MutableAttempt) {
        val key = attempt.currentPhoneToolCallId.ifBlank { attempt.id }
        val slice = attempt.events.drop(attempt.eventsAtToolCallStart.coerceAtLeast(0))
        val ui = slice.map { event -> phoneGuiStepFromEvent(event, attempt.goal) }
        _liveGuiSteps.value = ui
        _liveGuiStepsByToolCall.update { current -> current + (key to ui) }
        persistGuiSteps(key, ui)
    }

    private fun loadPersistedGuiSteps() {
        val dir = guiStepStoreDir ?: return
        if (!dir.isDirectory) return
        val loaded = linkedMapOf<String, List<PhoneGuiStepUi>>()
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.extension.equals("json", ignoreCase = true)) return@forEach
            val steps = parsePhoneGuiSteps(runCatching { file.readText() }.getOrDefault(""))
            if (steps.isNotEmpty()) loaded[file.nameWithoutExtension] = steps
        }
        if (loaded.isNotEmpty()) {
            _liveGuiStepsByToolCall.value = loaded
        }
    }

    private fun persistGuiSteps(toolCallId: String, steps: List<PhoneGuiStepUi>) {
        val dir = guiStepStoreDir ?: return
        val name = guiStepsFileName(toolCallId)
        if (name.isBlank()) return
        dir.mkdirs()
        val target = File(dir, "$name.json")
        val json = encodePhoneGuiSteps(steps)
        if (json.isBlank()) {
            target.delete()
            return
        }
        val staging = File(dir, ".$name.${UUID.randomUUID()}.tmp")
        runCatching {
            staging.writeText(json)
            if (!staging.renameTo(target)) {
                target.writeText(staging.readText())
                staging.delete()
            }
        }.onFailure {
            staging.delete()
        }
    }

    private fun readPersistedGuiSteps(toolCallId: String): String {
        val dir = guiStepStoreDir ?: return ""
        val name = guiStepsFileName(toolCallId)
        if (name.isBlank()) return ""
        val file = File(dir, "$name.json")
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun guiStepsFileName(toolCallId: String): String =
        toolCallId.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun persistScene(
        attempt: MutableAttempt,
        distilled: TeachingTraceDistiller.Result,
        sop: AgentModeSop?,
    ) {
        val card = GuiSceneComposer.fromTeaching(
            goal = attempt.goal,
            appName = sop?.appName.orEmpty().ifBlank { attempt.appName },
            packageName = sop?.packageName.orEmpty().ifBlank { attempt.packageName },
            distilled = distilled,
            sopId = sop?.id.orEmpty(),
        )
        val saved = sceneStore?.upsert(card) ?: card
        attempt.sceneCard = saved
        lastCommittedScene = saved
        pendingEverMeFact = saved.toEverMeFact()
    }

    private fun distilledSummary(attempt: MutableAttempt): String {
        if (attempt.everMeSummary.isNotBlank()) return attempt.everMeSummary
        return TeachingTraceDistiller.distill(attempt.steps).everMeSummary
    }

    private fun publishEverMe(
        attempt: MutableAttempt,
        sop: AgentModeSop?,
        summary: String,
    ) {
        if (attempt.everMePublished) return
        attempt.everMePublished = true
        val scene = attempt.sceneCard ?: lastCommittedScene
        if (pendingEverMeFact.isBlank() && scene != null) {
            pendingEverMeFact = scene.toEverMeFact()
        }
        diagnosticLogger.event(
            category = "agent_mode_learning",
            event = "everme_scene",
            sessionId = attempt.sessionId,
            turnId = attempt.turnId,
            details = mapOf(
                "goal" to attempt.goal,
                "app" to scene?.appName.orEmpty().ifBlank { sop?.appName.orEmpty().ifBlank { attempt.appName } },
                "package" to scene?.packageName.orEmpty().ifBlank { sop?.packageName.orEmpty().ifBlank { attempt.packageName } },
                "sop_id" to sop?.id.orEmpty(),
                "scene_id" to scene?.id.orEmpty(),
                "tags" to scene?.tags.orEmpty().joinToString(" "),
                "summary" to summary,
                "labels" to (scene?.guiLabels ?: sop?.steps?.map { it.guiLabel }.orEmpty())
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(8)
                    .joinToString("|"),
                "pick_slots" to (scene?.pickSlots ?: sop?.steps?.count { it.inputSlot == TeachingTraceDistiller.PickSlot } ?: 0),
            ),
        )
        diagnosticLogger.event(
            category = "agent_mode_learning",
            event = "everme_review",
            sessionId = attempt.sessionId,
            turnId = attempt.turnId,
            details = mapOf(
                "trajectory_id" to attempt.id,
                "event_count" to attempt.events.size,
                "summary" to summary,
            ),
        )
    }

    private fun persistFinal(
        attempt: MutableAttempt,
        verdict: GuiTrajectoryVerdict,
        report: String,
        verdictSource: String,
    ): GuiTrajectoryAttempt {
        val snapshot = attempt.snapshot(
            verdict = verdict,
            report = report,
            completedAtMillis = System.currentTimeMillis(),
        )
        trajectoryStore.upsert(snapshot)
        trajectoryStore.appendVerdict(attempt.id, verdict, verdictSource, report)
        return snapshot
    }

    private fun activeAttempt(sessionId: String): MutableAttempt? {
        if (sessionId.isNotBlank()) return attempts[sessionId]
        return mostRecentAttempt()
    }

    private fun mostRecentAttempt(): MutableAttempt? = attempts.values.maxByOrNull { it.startedAtMillis }

    private fun stepFrom(
        action: String,
        source: String,
        arguments: JSONObject,
        result: JSONObject,
        packageName: String,
        appName: String,
        target: GuiTarget?,
        inputSlot: String,
        uiFingerprint: String,
        pageTargetCount: Int = 0,
        toFingerprint: String = "",
    ): AgentModeSopStep = AgentModeSopStep(
        action = action,
        source = source,
        x = arguments.optionalInt("x"),
        y = arguments.optionalInt("y"),
        x1 = arguments.optionalInt("x1"),
        y1 = arguments.optionalInt("y1"),
        x2 = arguments.optionalInt("x2"),
        y2 = arguments.optionalInt("y2"),
        target = arguments.optString("target"),
        // Values typed by the user/model are intentionally not persisted in a reusable SOP.
        text = if (inputSlot.isBlank()) arguments.optString("text") else "",
        key = arguments.optString("key"),
        packageName = packageName,
        appName = appName,
        screenshotPath = result.optString("screenshot_path"),
        guiLabel = target?.label.orEmpty(),
        guiBounds = target?.bounds.orEmpty(),
        inputSlot = inputSlot,
        resultOk = result.optBoolean("ok", true),
        uiFingerprint = uiFingerprint,
        toFingerprint = toFingerprint,
        pageTargetCount = pageTargetCount,
        resourceId = target?.resourceId.orEmpty(),
        locator = target?.resourceId.orEmpty().ifBlank { target?.label.orEmpty() },
        locatorSpec = GuiLocatorCodec.capture(
            label = target?.label.orEmpty(),
            resourceId = target?.resourceId.orEmpty(),
            klass = target?.klass.orEmpty(),
            hint = target?.hint.orEmpty(),
            desc = target?.desc.orEmpty(),
            editable = target?.editable == true,
            focused = target?.focused == true,
            left = target?.left ?: 0,
            top = target?.top ?: 0,
            right = target?.right ?: 0,
            bottom = target?.bottom ?: 0,
        ),
    )

    private fun learningTag(
        attempt: MutableAttempt,
        event: GuiTrajectoryEvent,
        inputSlot: String,
    ): JSONObject = JSONObject()
        .put("attempt_id", attempt.id)
        .put("event_sequence", event.sequence)
        .put("source", event.source)
        .put("action", event.action)
        .put("gui_label", event.guiLabel)
        .put("gui_bounds", event.guiBounds)
        .put("input_slot", inputSlot)
        .put("candidate_sop_ids", JSONArray(attempt.candidateSopIds))

    private fun resolveGuiTarget(action: String, arguments: JSONObject, uiTree: String): GuiTarget? {
        if (action == "launch") {
            return arguments.optString("target").takeIf { it.isNotBlank() }?.let { GuiTarget(it, "") }
        }
        val nodes = parseGuiTargets(uiTree)
        if (nodes.isEmpty()) return null
        if (action == "text" || action == "clear_text" || action == "search") {
            return nodes.firstOrNull { it.focused }
        }
        if (action == "click_node" || action == "wait_for_label" || action == "scroll_until") {
            val query = arguments.optString("query")
                .ifBlank { arguments.optString("text") }
                .ifBlank { arguments.optString("target") }
            if (query.isBlank()) return null
            val needle = query.removePrefix("#")
            return nodes.firstOrNull { node ->
                node.resourceId.equals(needle, ignoreCase = true) ||
                    node.resourceId.equals(query.substringAfterLast('/'), ignoreCase = true) ||
                    node.label.contains(needle, ignoreCase = true)
            }
        }
        val x = when (action) {
            "tap" -> arguments.optionalInt("x")
            "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "fling" ->
                arguments.optionalInt("x1")
            else -> null
        } ?: return null
        val y = when (action) {
            "tap" -> arguments.optionalInt("y")
            "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "fling" ->
                arguments.optionalInt("y1")
            else -> null
        } ?: return null
        return nodes
            .filter { x in it.left..it.right && y in it.top..it.bottom }
            .minByOrNull { (it.right - it.left).coerceAtLeast(1) * (it.bottom - it.top).coerceAtLeast(1) }
    }

    private fun parseGuiTargets(uiTree: String): List<GuiTarget> = buildList {
        uiTree.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val bounds = BoundsPattern.find(line) ?: return@forEach
            val prefix = line.substring(0, bounds.range.first).trim()
            val focused = prefix.endsWith(" focus") || prefix.contains(" focus ")
            val label = prefix.removeSuffix(" focus").removeSuffix(" tap").trim()
            if (label.isBlank()) return@forEach
            val after = line.substring(bounds.range.last + 1).trim()
            val resourceId = after.removePrefix("#").trim()
            add(
                GuiTarget(
                    label = label.take(160),
                    bounds = bounds.value,
                    left = bounds.groupValues[1].toInt(),
                    top = bounds.groupValues[2].toInt(),
                    right = bounds.groupValues[3].toInt(),
                    bottom = bounds.groupValues[4].toInt(),
                    focused = focused,
                    resourceId = resourceId,
                    klass = if (focused) "EditText" else "",
                    editable = focused,
                ),
            )
        }
    }

    private fun sanitizedArguments(arguments: JSONObject): JSONObject = JSONObject().apply {
        arguments.keys().forEach { key ->
            val value = arguments.opt(key)
            if (key.contains("text", ignoreCase = true) ||
                key.contains("password", ignoreCase = true) ||
                key.contains("token", ignoreCase = true)
            ) {
                val length = value?.toString()?.length ?: 0
                put("${key}_present", length > 0)
                put("${key}_length", length)
            } else {
                put(key, value)
            }
        }
    }

    private fun extractReport(raw: String): String {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        val candidate = parsed?.optString("result")
            .orEmpty()
            .ifBlank { parsed?.optString("output").orEmpty() }
            .ifBlank { parsed?.optString("content").orEmpty() }
            .ifBlank { raw }
        return candidate
            .replace(SuccessMarker, "", ignoreCase = true)
            .replace(FailureMarker, "", ignoreCase = true)
            .replace(NeedsTeachingMarker, "", ignoreCase = true)
            .replace(WatchingMarker, "", ignoreCase = true)
            .trim()
            .take(2_000)
    }

    private fun fingerprint(value: String): String {
        if (value.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private data class GuiTarget(
        val label: String,
        val bounds: String,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val focused: Boolean = false,
        val resourceId: String = "",
        val klass: String = "",
        val hint: String = "",
        val desc: String = "",
        val editable: Boolean = false,
    )

    private data class MutableAttempt(
        val id: String,
        val sessionId: String,
        val turnId: String,
        val goal: String,
        val candidateSopIds: List<String>,
        val startedAtMillis: Long = System.currentTimeMillis(),
        val events: MutableList<GuiTrajectoryEvent> = mutableListOf(),
        val steps: MutableList<AgentModeSopStep> = mutableListOf(),
        val usedSopIds: MutableSet<String> = linkedSetOf(),
        val phoneAgentCallIds: MutableSet<String> = linkedSetOf(),
        var currentPhoneToolCallId: String = "",
        var eventsAtToolCallStart: Int = 0,
        val completedReports: MutableList<CompletedPhoneReport> = mutableListOf(),
        var packageName: String = "",
        var appName: String = "",
        var lastUiTree: String = "",
        var userTaught: Boolean = false,
        var phoneVerdict: GuiTrajectoryVerdict = GuiTrajectoryVerdict.Active,
        var phoneReport: String = "",
        var watching: Boolean = false,
        var teachingCommitted: Boolean = false,
        var everMePublished: Boolean = false,
        var everMeSummary: String = "",
        var promotedSop: AgentModeSop? = null,
        var sceneCard: GuiSceneCard? = null,
    ) {
        fun nextSequence(): Int = events.size + 1

        fun snapshot(
            verdict: GuiTrajectoryVerdict = GuiTrajectoryVerdict.Active,
            report: String = phoneReport,
            completedAtMillis: Long? = null,
        ): GuiTrajectoryAttempt = GuiTrajectoryAttempt(
            id = id,
            sessionId = sessionId,
            turnId = turnId,
            goal = goal,
            verdict = verdict,
            report = report,
            packageName = packageName,
            appName = appName,
            userTaught = userTaught,
            candidateSopIds = candidateSopIds,
            usedSopIds = usedSopIds.toList(),
            events = events.toList(),
            steps = steps.toList(),
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
        )
    }

    companion object {
        const val SuccessMarker = "GUI_TASK_SUCCEEDED:"
        const val FailureMarker = "GUI_TASK_FAILED:"
        const val NeedsTeachingMarker = "GUI_TASK_NEEDS_TEACHING:"
        const val WatchingMarker = "GUI_TASK_WATCHING:"
        const val EverMeFactMarker = "everme_scene_fact:"
        private val PhoneReportLine = Regex(
            """GUI_TASK_(SUCCEEDED|FAILED|NEEDS_TEACHING|WATCHING):\s*(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )

        internal val ReusableActions = setOf(
            "launch",
            "tap",
            "click_node",
            "swipe",
            "swipe_left",
            "swipe_right",
            "swipe_up",
            "swipe_down",
            "key",
            "text",
            "search",
            "clear_text",
            "back",
            "home",
            "recents",
            "notifications",
            "wait_for_label",
        )
        private val BoundsPattern = Regex("\\((\\d+),(\\d+)\\)-\\((\\d+),(\\d+)\\)")

        private fun looksLikePackage(value: String): Boolean =
            value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
    }
}
