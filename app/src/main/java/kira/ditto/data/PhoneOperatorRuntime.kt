package kira.ditto.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class PhoneDeskPhase {
    Idle,
    Dispatching,
    Working,
    Listening,
    Teaching,
    Reporting,
}

data class PhoneDeskHandoffState(
    val phase: PhoneDeskPhase = PhoneDeskPhase.Idle,
    val task: String = "",
    val report: String = "",
    /** Non-empty after the operator actually opens a real app (not the launcher). */
    val openedAppPackage: String = "",
    val openedAppLabel: String = "",
)

class PhoneOperatorRuntime(
    private val agentModeController: AgentModeController,
    private val sopStore: AgentModeSopStore,
    private val settlementRuntime: PhoneSettlementRuntime? = null,
    private val catalogStore: PhoneAppCatalogStore? = null,
    private val flowExecutor: PhoneAppFlowExecutor? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
) : AgentModeUserGestureListener {
    private val _handoff = MutableStateFlow(PhoneDeskHandoffState())
    val handoff: StateFlow<PhoneDeskHandoffState> = _handoff.asStateFlow()
    private val dispatchEpoch = AtomicInteger(0)
    private val dispatchMutex = Mutex()
    private var inflightTask: String? = null
    private var inflightResult: CompletableDeferred<String>? = null
    private val traceLock = Any()
    private val currentTrace = mutableListOf<AgentModeSopStep>()
    private var lastSuccessfulTrace: List<AgentModeSopStep> = emptyList()
    private var lastSuccessfulGoal: String = ""

    init {
        agentModeController.userGestureListener = this
    }

    override fun onUserTap(normalizedX: Int, normalizedY: Int) {
        recordDemoStep(
            AgentModeSopStep(
                action = "tap",
                source = "demo",
                x = normalizedX,
                y = normalizedY,
            ),
        )
    }

    override fun onUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    ) {
        recordDemoStep(
            AgentModeSopStep(
                action = "swipe",
                source = "demo",
                x1 = normalizedX1,
                y1 = normalizedY1,
                x2 = normalizedX2,
                y2 = normalizedY2,
            ),
        )
    }

    fun abandon() {
        dispatchEpoch.incrementAndGet()
        _handoff.value = PhoneDeskHandoffState()
    }

    suspend fun dispatch(
        arguments: JSONObject,
        settings: AppSettings,
        workspaceDirectory: String,
    ): String {
        val action = arguments.optString("action").trim().lowercase()
        val task = arguments.optString("task").trim()
        if (action == "save_sop") {
            return saveSop(task.ifBlank { lastSuccessfulGoal })
        }
        if (action.isNotBlank() && action != "dispatch") {
            return JSONObject()
                .put("ok", false)
                .put("errmsg", "Unsupported phone_desk action '$action'. Use dispatch or save_sop.")
                .toString()
        }
        if (task.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("errmsg", "Missing required 'task'.")
                .toString()
        }
        val slot = dispatchMutex.withLock {
            val existing = inflightResult
            if (existing != null && inflightTask == task && !existing.isCompleted) {
                existing to false
            } else {
                if (existing != null && !existing.isCompleted && inflightTask != task) {
                    abandon()
                }
                val deferred = CompletableDeferred<String>()
                inflightTask = task
                inflightResult = deferred
                deferred to true
            }
        }
        val (joined, owner) = slot
        if (!owner) {
            return joined.await()
        }
        val successCriteria = arguments.optString("success_criteria").trim()
        val sopId = arguments.optString("sop_id").trim()
        val epoch = dispatchEpoch.get()
        return try {
            val result = runDispatchedTask(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                task = task,
                successCriteria = successCriteria,
                sopId = sopId,
                epoch = epoch,
            )
            joined.complete(result)
            result
        } catch (error: Throwable) {
            val failed = JSONObject()
                .put("ok", false)
                .put("errmsg", error.message ?: "Phone operator failed.")
                .toString()
            joined.complete(failed)
            failed
        } finally {
            dispatchMutex.withLock {
                if (inflightResult === joined) {
                    inflightResult = null
                    inflightTask = null
                }
            }
        }
    }

    private suspend fun runDispatchedTask(
        settings: AppSettings,
        workspaceDirectory: String,
        task: String,
        successCriteria: String,
        sopId: String,
        epoch: Int,
    ): String {
        _handoff.value = PhoneDeskHandoffState(
            phase = PhoneDeskPhase.Dispatching,
            task = task,
        )
        return try {
            val report = runOperatorLoop(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                task = task,
                successCriteria = successCriteria,
                sopId = sopId,
                epoch = epoch,
            )
            if (isAbandoned(epoch)) {
                return JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Phone operator abandoned.")
                    .toString()
            }
            _handoff.value = PhoneDeskHandoffState(
                phase = PhoneDeskPhase.Reporting,
                task = task,
                report = report,
            )
            JSONObject()
                .put("ok", true)
                .put("report", report)
                .put("stdout", report)
                .toString()
        } catch (error: Throwable) {
            if (error is OperatorAbandonedException || isAbandoned(epoch)) {
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Phone operator abandoned.")
                    .toString()
            } else {
                _handoff.value = PhoneDeskHandoffState(
                    phase = PhoneDeskPhase.Idle,
                    task = task,
                    report = error.message.orEmpty(),
                )
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", error.message ?: "Phone operator failed.")
                    .toString()
            }
        } finally {
            delay(400)
            if (!isAbandoned(epoch) && _handoff.value.phase == PhoneDeskPhase.Reporting) {
                _handoff.value = _handoff.value.copy(phase = PhoneDeskPhase.Idle)
            }
        }
    }

    private fun saveSop(goal: String): String {
        if (goal.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("errmsg", "Missing required 'task' to save as SOP.")
                .toString()
        }
        val steps = synchronized(traceLock) {
            currentTrace.toList().ifEmpty { lastSuccessfulTrace }
        }
        if (steps.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("errmsg", "No phone-UI steps to freeze into an SOP yet.")
                .toString()
        }
        val sop = sopStore.saveExplicit(goal, steps)
        return JSONObject()
            .put("ok", true)
            .put("sop_id", sop.id)
            .put("report", "Saved SOP ${sop.id} for '$goal' with ${sop.steps.size} steps.")
            .put("stdout", "Saved SOP ${sop.id}")
            .toString()
    }

    private suspend fun runOperatorLoop(
        settings: AppSettings,
        workspaceDirectory: String,
        task: String,
        successCriteria: String,
        sopId: String,
        epoch: Int,
    ): String {
        val workspace = workspaceDirectory.ifBlank { "/tmp" }
        synchronized(traceLock) { currentTrace.clear() }
        if (isAbandoned(epoch)) throw OperatorAbandonedException()
        agentModeController.execute(
            settings = settings,
            workspaceDirectory = workspace,
            termuxWorkspaceDirectory = workspace,
            argumentsJson = JSONObject()
                .put("action", "start")
                .put("skip_capture", true)
                .toString(),
        )
        _handoff.value = _handoff.value.copy(phase = PhoneDeskPhase.Working)
        if (isAbandoned(epoch)) throw OperatorAbandonedException()
        val matchedSop = sopStore.findById(sopId) ?: sopStore.findByGoal(task)?.takeIf { it.promoted }
        if (matchedSop != null && matchedSop.steps.isNotEmpty()) {
            replaySop(settings, workspace, matchedSop)
        }
        val history = JSONArray()
        var lastReport = ""
        var lastShot: JSONObject? = null
        for (step in 0 until MaxOperatorSteps) {
            if (isAbandoned(epoch)) throw OperatorAbandonedException()
            val snapshot = agentModeController.execute(
                settings = settings,
                workspaceDirectory = workspace,
                termuxWorkspaceDirectory = workspace,
                argumentsJson = JSONObject()
                    .put("action", "screenshot")
                    .put("skip_workspace", true)
                    .toString(),
            )
            val parsedShot = runCatching { JSONObject(snapshot) }.getOrNull()
            lastShot = parsedShot
            noteOpenedApp(
                packageName = parsedShot?.optString("package_name").orEmpty(),
                appLabel = parsedShot?.optString("app_name").orEmpty(),
            )
            val image = parsedShot?.optString("screenshot_base64").orEmpty()
            val mime = parsedShot?.optString("screenshot_mime_type").orEmpty().ifBlank { "image/jpeg" }
            val userContent = JSONArray().apply {
                put(
                    JSONObject()
                        .put("type", "text")
                        .put(
                            "text",
                            buildString {
                                append("Task: ").append(task).append('\n')
                                if (successCriteria.isNotBlank()) {
                                    append("Done when: ").append(successCriteria).append('\n')
                                }
                                if (matchedSop != null) {
                                    append("A matching SOP ").append(matchedSop.id)
                                    append(" was already replayed. Correct remaining UI only.\n")
                                }
                                append("Display is ")
                                append(parsedShot?.optInt("width") ?: 0)
                                append('x')
                                append(parsedShot?.optInt("height") ?: 0)
                                append(" px. Coordinates are 0..1000 on both axes.\n")
                                val uiTree = parsedShot?.optString("ui_tree").orEmpty()
                                if (uiTree.isNotBlank()) {
                                    append("Accessibility UI tree (native apps have no HTML DOM):\n")
                                    append(uiTree).append('\n')
                                }
                                append("Step ").append(step + 1).append('/').append(MaxOperatorSteps)
                                append(". Keep acting with open_app, type_text, clear_text, tap, swipe, back")
                                val distilled = catalogStore?.operatorHint(
                                    parsedShot?.optString("package_name").orEmpty(),
                                    task,
                                ).orEmpty()
                                if (distilled.isNotBlank()) {
                                    append(". ").append(distilled)
                                }
                                append(". Report only when the task is done.")
                            },
                        ),
                )
                if (image.isNotBlank()) {
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:$mime;base64,$image"),
                            ),
                    )
                }
            }
            history.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", userContent),
            )
            pruneOperatorHistoryImages(history)
            val reply = completeOperatorTurn(settings, history)
            history.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", reply),
            )
            val commands = parseOperatorCommands(reply)
            var reported: String? = null
            var executedUi = false
            for (command in commands) {
                val commandAction = command.optString("action").trim().lowercase()
                if (commandAction == "wait") {
                    delay(400)
                    continue
                }
                if (commandAction == "report" || commandAction == "done") {
                    if (executedUi) {
                        continue
                    }
                    reported = command.optString("summary").ifBlank {
                        command.optString("report").ifBlank { reply }
                    }
                    break
                }
                executedUi = true
                val toolArguments = operatorArguments(command, commandAction)
                val toolResult = if (catalogStore?.findReadyTool(commandAction) != null && flowExecutor != null) {
                    flowExecutor.invoke(
                        toolName = commandAction,
                        arguments = command,
                        settings = settings,
                        workspaceDirectory = workspace,
                    )
                } else {
                    agentModeController.execute(
                        settings = settings,
                        workspaceDirectory = workspace,
                        termuxWorkspaceDirectory = workspace,
                        argumentsJson = toolArguments.toString(),
                    )
                }
                val parsedResult = runCatching { JSONObject(toolResult) }.getOrNull()
                if (commandAction == "open_app" || commandAction == "launch") {
                    noteOpenedApp(
                        packageName = parsedResult?.optString("package_name").orEmpty()
                            .ifBlank { command.optString("package_name") }
                            .ifBlank { command.optString("package") }
                            .ifBlank { command.optString("target") },
                        appLabel = parsedResult?.optString("app_name").orEmpty()
                            .ifBlank { command.optString("app_name") }
                            .ifBlank { command.optString("target") },
                    )
                }
                appendTraceStep(
                    fromCommand = command,
                    action = commandAction,
                    source = "operator",
                    screenshotPath = parsedResult?.optString("screenshot_path")
                        ?: parsedShot?.optString("screenshot_path").orEmpty(),
                    parsedShot = parsedResult ?: parsedShot,
                )
                val sanitized = AetherToolExecutor.sanitizeToolOutputForConversation(
                    AgentDisplayMcp.ToolName,
                    toolResult,
                )
                lastReport = JSONObject()
                    .put("did", commandAction)
                    .put("result", runCatching { JSONObject(sanitized) }.getOrNull() ?: sanitized)
                    .toString()
            }
            if (reported != null) {
                val traceActions = synchronized(traceLock) { currentTrace.map { it.action } }
                if (
                    isPrematureReport(
                        task = task,
                        successCriteria = successCriteria,
                        stepIndex = step,
                        traceActions = traceActions,
                    )
                ) {
                    history.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                "Premature report rejected. Trace so far: ${
                                    traceActions.joinToString(",").ifBlank { "(none)" }
                                }. The task is not done. Look at the next screenshot and continue. " +
                                    "Do not report until the user's full request is visible on screen.",
                            ),
                    )
                    continue
                }
                lastReport = reported
                return finishSuccess(task, lastReport, lastShot)
            }
        }
        return lastReport.ifBlank { "Operator reached the step limit without a report." }
            .let { leftover ->
                val traceActions = synchronized(traceLock) { currentTrace.map { it.action } }
                if (
                    isPrematureReport(
                        task = task,
                        successCriteria = successCriteria,
                        stepIndex = MaxOperatorSteps,
                        traceActions = traceActions,
                    )
                ) {
                    error("Operator stopped before finishing the task. Trace: ${traceActions.joinToString(",")}")
                }
                leftover
            }
    }

    private suspend fun replaySop(
        settings: AppSettings,
        workspace: String,
        sop: AgentModeSop,
    ) {
        sop.steps.forEach { step ->
            val action = step.action.trim().lowercase()
            if (action.isBlank() || action == "report" || action == "done") return@forEach
            agentModeController.execute(
                settings = settings,
                workspaceDirectory = workspace,
                termuxWorkspaceDirectory = workspace,
                argumentsJson = step.toExecuteArguments()
                    .put("skip_capture", true)
                    .toString(),
            )
            appendTraceStep(step)
            if (action == "open_app" || action == "launch") {
                noteOpenedApp(
                    packageName = step.packageName,
                    appLabel = step.appName.ifBlank { step.target },
                )
            }
            delay(80)
        }
    }

    private fun finishSuccess(task: String, summary: String, lastShot: JSONObject?): String {
        val steps = synchronized(traceLock) { currentTrace.toList() }
        lastSuccessfulTrace = steps
        lastSuccessfulGoal = task
        val sop = if (steps.isNotEmpty()) sopStore.recordSuccess(task, steps) else sopStore.findByGoal(task)
        val appName = steps.map { it.appName }.lastOrNull { it.isNotBlank() }
            ?: lastShot?.optString("app_name").orEmpty()
        val packageName = steps.map { it.packageName }.lastOrNull { it.isNotBlank() }
            ?: lastShot?.optString("package_name").orEmpty()
        settlementRuntime?.enqueue(
            goal = task,
            summary = summary,
            steps = steps,
            sop = sop,
            packageName = packageName,
            appName = appName,
        )
        return buildString {
            append(summary.trim())
            append("\n\n[GUI tags]\n")
            append("goal=").append(task).append('\n')
            if (appName.isNotBlank()) append("app=").append(appName).append('\n')
            if (packageName.isNotBlank()) append("package=").append(packageName).append('\n')
            append("steps=").append(steps.joinToString(",") { it.action }).append('\n')
            val paths = steps.map { it.screenshotPath }.filter { it.isNotBlank() }.distinct()
            if (paths.isNotEmpty()) {
                append("screenshot_paths=").append(paths.joinToString(";")).append('\n')
            }
            if (sop != null) {
                append("sop_id=").append(sop.id).append('\n')
                append("sop_success_count=").append(sop.successCount).append('\n')
                if (sop.promoted) append("sop_ready=true\n")
            }
        }
    }

    private fun recordDemoStep(step: AgentModeSopStep) {
        val current = _handoff.value
        if (current.phase == PhoneDeskPhase.Idle) return
        appendTraceStep(step)
        if (current.phase == PhoneDeskPhase.Working || current.phase == PhoneDeskPhase.Dispatching) {
            _handoff.value = current.copy(phase = PhoneDeskPhase.Teaching)
        }
    }

    private fun appendTraceStep(
        fromCommand: JSONObject,
        action: String,
        source: String,
        screenshotPath: String,
        parsedShot: JSONObject?,
    ) {
        appendTraceStep(
            AgentModeSopStep(
                action = action,
                source = source,
                x = fromCommand.optionalInt("x"),
                y = fromCommand.optionalInt("y"),
                x1 = fromCommand.optionalInt("x1"),
                y1 = fromCommand.optionalInt("y1"),
                x2 = fromCommand.optionalInt("x2"),
                y2 = fromCommand.optionalInt("y2"),
                target = fromCommand.optString("target"),
                text = fromCommand.optString("text"),
                key = fromCommand.optString("key"),
                packageName = parsedShot?.optString("package_name").orEmpty()
                    .ifBlank { fromCommand.optString("target") },
                appName = parsedShot?.optString("app_name").orEmpty()
                    .ifBlank { fromCommand.optString("target") },
                screenshotPath = screenshotPath,
            ),
        )
    }

    private fun appendTraceStep(step: AgentModeSopStep) {
        if (step.action.isBlank()) return
        synchronized(traceLock) {
            currentTrace += step
        }
    }

    private fun noteOpenedApp(packageName: String, appLabel: String = "") {
        val pkg = packageName.trim()
        val label = appLabel.trim()
        if (pkg.isBlank() && label.isBlank()) return
        if (isHomeOrSystemUiPackage(pkg)) return
        val current = _handoff.value
        if (current.phase == PhoneDeskPhase.Idle) return
        if (current.openedAppPackage == pkg &&
            (label.isBlank() || current.openedAppLabel == label)
        ) {
            return
        }
        _handoff.value = current.copy(
            openedAppPackage = pkg.ifBlank { current.openedAppPackage.ifBlank { label } },
            openedAppLabel = label.ifBlank { current.openedAppLabel },
        )
    }

    private fun operatorArguments(command: JSONObject, commandAction: String): JSONObject {
        val arguments = PhoneUiMcp.toExecuteArguments(commandAction, command)
        if (arguments.optString("action").isBlank()) {
            arguments.put("action", commandAction)
        }
        arguments.put("skip_capture", true)
        return arguments
    }

    private fun pruneOperatorHistoryImages(history: JSONArray) {
        var lastUserIndex = -1
        for (index in 0 until history.length()) {
            if (history.optJSONObject(index)?.optString("role") == "user") {
                lastUserIndex = index
            }
        }
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            if (message.optString("role") != "user" || index == lastUserIndex) continue
            val content = message.optJSONArray("content") ?: continue
            val textOnly = JSONArray()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                if (part.optString("type") != "image_url") textOnly.put(part)
            }
            message.put("content", textOnly)
        }
        val overflow = history.length() - MaxOperatorHistoryMessages
        if (overflow > 0) {
            val kept = JSONArray()
            for (index in overflow until history.length()) {
                kept.put(history.getJSONObject(index))
            }
            while (history.length() > 0) history.remove(0)
            for (index in 0 until kept.length()) {
                history.put(kept.getJSONObject(index))
            }
        }
    }

    private fun completeOperatorTurn(
        settings: AppSettings,
        history: JSONArray,
    ): String {
        val url = chatCompletionsUrl(settings.baseUrl)
        val token = embeddingBearerToken(
            apiKey = settings.apiKey,
            oauthCredentialJson = settings.oauthCredentialJson,
            environment = settings.providerEnvironmentVariables.map { it.name to it.value },
            customHeaders = settings.customHeaders,
        )
        val body = JSONObject()
            .put("model", settings.modelId)
            .put("temperature", 0.2)
            .put("max_tokens", OperatorMaxTokens)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", OperatorSystemPrompt),
                ).apply {
                    for (index in 0 until history.length()) {
                        put(history.getJSONObject(index))
                    }
                },
            )
            .toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .applyAetherLlmHeaders(settings.userAgent, settings.customHeaders)
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Operator model failed (${response.code}): ${payload.take(240)}")
            }
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
                ?: error("Operator model returned a non-JSON body.")
            return extractAssistantText(parsed)
        }
    }

    private fun parseOperatorCommands(raw: String): List<JSONObject> {
        val root = parseOperatorCommand(raw)
        val batch = root.optJSONArray("actions") ?: return listOf(root)
        val commands = buildList {
            for (index in 0 until batch.length()) {
                val item = batch.optJSONObject(index) ?: continue
                add(item)
                if (size >= 5) break
            }
        }
        return commands.ifEmpty { listOf(root) }
    }

    private fun parseOperatorCommand(raw: String): JSONObject =
        parseOperatorCommandJson(raw)

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun isAbandoned(epoch: Int): Boolean = dispatchEpoch.get() != epoch

    companion object {
        private const val MaxOperatorSteps = 24
        private const val MaxOperatorHistoryMessages = 8
        private const val OperatorMaxTokens = 4096
        private const val OperatorSystemPrompt =
            "You operate one Android virtual phone continuously. You are not a coding agent. " +
                "Ignore any CLI, git, or programming instructions. Look at the screenshot and " +
                "return JSON: either one action object, or {\"actions\":[...]} with up to 5 UI " +
                "actions on this same screen. Exclusive operator MCP tools: open_app, type_text, " +
                "clear_text. Also tap, swipe, back, wait, report. Fields: " +
                "{\"action\":\"open_app|type_text|clear_text|tap|swipe|back|wait|report\"," +
                "\"x\":0-1000,\"y\":0-1000,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":0," +
                "\"target\":\"package or app name\",\"text\":\"full string to paste\"," +
                "\"summary\":\"...\"}. " +
                "Open apps with open_app, do not hunt icons unless launch fails. " +
                "Tap the exact widget. After tapping an input, type_text with the COMPLETE string " +
                "or clear_text to empty it. Text is pasted from the clipboard; never use the IME. " +
                "Keep acting until the assigned task is finished, then action=report once. " +
                "Never put report in the same JSON as other actions. " +
                "Never report after only opening an app unless that was the entire task. " +
                "If the screen is black or still loading, return {\"action\":\"wait\"}. " +
                "Do not report after every tap. The desk lead only sees your final report. " +
                "If a compact accessibility UI tree is listed, treat it as the screen DOM and " +
                "prefer those tap bounds. Native apps have no HTML. " +
                "If distilled app tools such as xhs_search are listed, prefer them. " +
                "Coordinates are 0..1000."

        internal fun parseOperatorCommandJson(raw: String): JSONObject {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()?.let { parsed ->
                    if (parsed.optString("action").isNotBlank() || parsed.optJSONArray("actions") != null) {
                        return parsed
                    }
                }
            }
            return JSONObject().put("action", "wait")
        }

        internal fun taskNeedsMoreThanLaunch(task: String, successCriteria: String): Boolean {
            if (successCriteria.isNotBlank()) return true
            val trimmed = task.trim()
            if (trimmed.isBlank()) return false
            val lower = trimmed.lowercase()
            if (MoreThanLaunchMarkers.any { marker -> lower.contains(marker) }) return true
            val compact = trimmed.replace(Regex("\\s+"), "")
            if (LaunchOnlyCompact.matches(compact)) return false
            if (LaunchOnlySpaced.matches(trimmed)) return false
            return true
        }

        internal fun isPrematureReport(
            task: String,
            successCriteria: String,
            stepIndex: Int,
            traceActions: List<String>,
        ): Boolean {
            if (!taskNeedsMoreThanLaunch(task, successCriteria)) return false
            if (stepIndex <= 0) return true
            val meaningful = traceActions.map { it.trim().lowercase() }.filter { action ->
                action.isNotBlank() && action !in IgnoredTraceActions
            }
            if (meaningful.isEmpty()) return true
            return meaningful.all { it in LaunchOnlyActions }
        }

        private val MoreThanLaunchMarkers = listOf(
            "搜", "search", "找", "最新", "发", "send", "赞", "like",
            "评论", "comment", "然后", "并", "给", "浏览", "看", "帖",
        )
        private val LaunchOnlyCompact = Regex(
            """^(打开|開啟|open)[\w\u4e00-\u9fff]{1,12}$""",
            RegexOption.IGNORE_CASE,
        )
        private val LaunchOnlySpaced = Regex(
            """^(打开|開啟|open)\s+\S+$""",
            RegexOption.IGNORE_CASE,
        )
        private val LaunchOnlyActions = setOf("launch", "open_app")
        private val IgnoredTraceActions = setOf("wait", "screenshot", "start", "status")

        internal fun isHomeOrSystemUiPackage(packageName: String): Boolean {
            val pkg = packageName.trim().lowercase()
            if (pkg.isBlank()) return true
            return pkg.contains("launcher") ||
                pkg.contains("systemui") ||
                pkg == "com.miui.home" ||
                pkg == "com.huawei.android.launcher" ||
                pkg == "com.google.android.apps.nexuslauncher"
        }

        internal fun chatCompletionsUrl(baseUrl: String): String {
            val normalized = baseUrl.trim().trimEnd('/')
            if (normalized.endsWith("/chat/completions")) {
                return if (
                    normalized.contains("api.kimi.com/coding") &&
                    !normalized.contains("/v1/")
                ) {
                    normalized.replace("/coding/chat/completions", "/coding/v1/chat/completions")
                } else {
                    normalized
                }
            }
            if (normalized.endsWith("/responses")) {
                return normalized.removeSuffix("/responses") + "/chat/completions"
            }
            if (normalized.endsWith("/coding")) {
                return "$normalized/v1/chat/completions"
            }
            return "$normalized/chat/completions"
        }

        internal fun extractAssistantText(payload: JSONObject): String {
            val apiError = payload.optJSONObject("error")
            if (apiError != null) {
                kotlin.error("Operator model failed: ${apiError.optString("message").ifBlank { apiError.toString() }}")
            }
            val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            val finishReason = choice?.optString("finish_reason").orEmpty()
            val message = choice?.optJSONObject("message")
            val content = stringifyContent(message?.opt("content"))
            val reasoning = stringifyContent(message?.opt("reasoning_content"))
                .ifBlank { stringifyContent(message?.opt("reasoning")) }
            val text = content.ifBlank { reasoning }
            if (text.isBlank()) {
                if (finishReason.equals("length", ignoreCase = true)) {
                    error("Operator model truncated before a JSON action. Retry.")
                }
                error("Operator model returned an empty reply.")
            }
            return text
        }

        internal fun stringifyContent(raw: Any?): String {
            if (raw == null || raw == JSONObject.NULL) return ""
            if (raw is String) return if (raw == "null") "" else raw
            if (raw is JSONArray) {
                return buildString {
                    for (index in 0 until raw.length()) {
                        val part = raw.optJSONObject(index)
                        val piece = when {
                            part == null -> raw.optString(index)
                            else -> part.optString("text").ifBlank {
                                part.optString("output_text")
                            }
                        }
                        append(piece)
                    }
                }
            }
            return raw.toString().takeIf { it != "null" }.orEmpty()
        }
    }
}

private class OperatorAbandonedException : RuntimeException("Phone operator abandoned.")
