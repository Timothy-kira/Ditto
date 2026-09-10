package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/** MCP whose tool list is generated from verified per-app GUI flows. */
internal object PhoneAppFlowMcp {
    const val PluginId = "aether-phone-app"
    const val ServerName = "phone_app"
    const val RecallToolName = "recall_gui_flows"
    const val RunToolName = "run_gui_flow"
    const val PipelineToolName = "run_gui_pipeline"

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    @Suppress("UNUSED_PARAMETER")
    fun listToolsResult(catalogs: List<PhoneAppCatalog>): JSONObject {
        val tools = JSONArray()
            .put(recallToolDefinition())
            .put(runToolDefinition())
            .put(pipelineToolDefinition())
        return JSONObject().put("tools", tools)
    }

    fun shouldDumpBeforeStep(
        existingNodes: List<AgentModeUiTree.RichNode>,
        needsLocator: Boolean,
    ): Boolean = needsLocator && existingNodes.isEmpty()

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val imageData = parsed?.optString("screenshot_base64").orEmpty()
        val imageMimeType = parsed?.optString("screenshot_mime_type")
            .orEmpty()
            .ifBlank { "image/jpeg" }
        val structured = parsed?.let { JSONObject(it.toString()) } ?: JSONObject().put("raw", rawOutput)
        structured.remove("screenshot_base64")
        AgentModePortal.stripBulkyFieldsForModel(structured)
        val visible = structured.toString()
        val isError = structured.optBoolean("ok", true) == false
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", visible),
        )
        if (imageData.isNotBlank()) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", imageData)
                    .put("mimeType", imageMimeType),
            )
        }
        return JSONObject()
            .put("content", content)
            .put("structuredContent", structured)
            .put("isError", isError)
    }

    /** Null means [rawSopId] is a scene without a promoted SOP. */
    fun resolveRunSopId(rawSopId: String, sceneStore: GuiSceneStore?): String? {
        val trimmed = rawSopId.trim()
        if (!trimmed.startsWith("scene-")) return trimmed
        return sceneStore?.findById(trimmed)?.sopId?.takeIf { it.isNotBlank() }
    }

    fun sceneNotSopJson(sceneId: String): String = JSONObject()
        .put("ok", false)
        .put("code", AgentModeSafety.SceneNotSopCode)
        .put(
            "errmsg",
            "sop_id '$sceneId' is a GUI scene, not a verified SOP. Call recall_gui_flows and use a real sop_id, or dump_tree for L0. Do not treat MCP timeout as a downed backend.",
        )
        .put("scene_id", sceneId)
        .put("consistent", "no")
        .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
        .toString()

    @Suppress("unused")
    private fun toolDefinition(primitive: PhoneAppPrimitive): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        if (primitive.acceptsQuery) {
            properties.put(
                "text",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Query or body to paste into this distilled flow."),
            )
            required.put("text")
        }
        if (primitive.kind == PhoneAppPrimitiveKind.OpenItem) {
            properties.put(
                "item",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Visible label of the instance row to open. Do not reuse coordinates."),
            )
            properties.put(
                "target",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Alias of item."),
            )
        }
        return JSONObject()
            .put("name", primitive.toolName)
            .put("description", primitive.description)
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)
                    .put("additionalProperties", false),
            )
    }

    private fun recallToolDefinition(): JSONObject = JSONObject()
        .put("name", RecallToolName)
        .put(
            "description",
            "Recall verified GUI SOPs relevant to a phone task. Use before raw tapping for a recurring app workflow.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "task",
                            JSONObject().put("type", "string").put("description", "The complete delegated phone task."),
                        )
                        .put(
                            "package_name",
                            JSONObject().put("type", "string").put("description", "Optional Android package filter."),
                        ),
                )
                .put("required", JSONArray().put("task"))
                .put("additionalProperties", false),
        )

    private fun runToolDefinition(): JSONObject = JSONObject()
        .put("name", RunToolName)
        .put(
            "description",
            "Run one verified GUI SOP returned by recall_gui_flows. Locates controls on the accessibility tree; no per-step JPEG. On MACRO_MISMATCH or SCENE_NOT_SOP, replan the current segment — do not keep the old tap list.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "sop_id",
                            JSONObject().put("type", "string").put("description", "Stable SOP id from recall_gui_flows."),
                        )
                        .put(
                            "text",
                            JSONObject().put("type", "string").put("description", "Optional query/body for the SOP's text slot."),
                        ),
                )
                .put("required", JSONArray().put("sop_id"))
                .put("additionalProperties", false),
        )

    private fun pipelineToolDefinition(): JSONObject = JSONObject()
        .put("name", PipelineToolName)
        .put(
            "description",
            "Compose verified GUI macros in order. Each step is a Ready SOP or distilled tool plus slots. " +
                "A MACRO_MISMATCH interrupts only the current segment (degraded_to=replan); completed prefix stays. " +
                "Do not splice raw exploration taps here.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "steps",
                            JSONObject()
                                .put("type", "array")
                                .put(
                                    "description",
                                    "Ordered macros: {sop_id|tool, slots:{query,item}}. item may be {{last.pick}}.",
                                )
                                .put("items", JSONObject().put("type", "object").put("additionalProperties", true)),
                        ),
                )
                .put("required", JSONArray().put("steps"))
                .put("additionalProperties", false),
        )
}

internal object GuiPipelineSlots {
    fun interpolate(value: String, lastPick: String): String =
        value.replace("{{last.pick}}", lastPick).replace("{{last.item}}", lastPick)
}

class PhoneAppFlowExecutor(
    private val catalogStore: PhoneAppCatalogStore,
    private val sopStore: AgentModeSopStore,
    private val agentModeController: AgentModeController,
    private val sceneStore: GuiSceneStore? = null,
    private val signalStore: GuiSignalStore? = null,
) {
    fun validateSop(sopId: String): AgentModeSop? {
        val sop = sopStore.findById(sopId) ?: return null
        return if (sop.validationState == "validated" && sop.maturity == AgentModeSopMaturity.Ready) {
            sop
        } else {
            null
        }
    }

    suspend fun invoke(
        toolName: String,
        arguments: JSONObject,
        settings: AppSettings,
        workspaceDirectory: String,
    ): String {
        if (toolName.equals(PhoneAppFlowMcp.RecallToolName, ignoreCase = true)) {
            return recall(arguments)
        }
        if (toolName.equals(PhoneAppFlowMcp.PipelineToolName, ignoreCase = true)) {
            return runPipeline(arguments, settings, workspaceDirectory)
        }
        return replayNamed(toolName, arguments, settings, workspaceDirectory)
    }

    private fun recall(arguments: JSONObject): String {
        val task = arguments.optString("task")
        if (task.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "MISSING_RECALL_TASK")
                .put("errmsg", "Missing required 'task'.")
                .toString()
        }
        val hits = catalogStore.recallReady(
            task = task,
            packageName = arguments.optString("package_name"),
        ).filter { (_, primitive) ->
            val sop = sopStore.findById(primitive.sopId)
            sop?.promoted == true &&
                sop.maturity == AgentModeSopMaturity.Ready &&
                sop.validationState == "validated"
        }
        return JSONObject()
            .put("ok", true)
            .put("query", task)
            .put("count", hits.size)
            .put(
                "flows",
                JSONArray().apply {
                    hits.forEach { (catalog, primitive) ->
                        put(
                            JSONObject()
                                .put("sop_id", primitive.sopId)
                                .put("tool_name", primitive.toolName)
                                .put("title", primitive.title)
                                .put("description", primitive.description)
                                .put("app_name", catalog.appName)
                                .put("package_name", catalog.packageName)
                                .put("confidence", primitive.confidence)
                                .put("source", primitive.provenance)
                                .put("accepts_text", primitive.acceptsQuery)
                                .put("gui_labels", JSONArray(primitive.guiLabels)),
                        )
                    }
                },
            )
            .put("stdout", if (hits.isEmpty()) "No verified GUI flow matched." else "Recalled ${hits.size} verified GUI flow(s).")
            .toString()
    }

    private suspend fun runPipeline(
        arguments: JSONObject,
        settings: AppSettings,
        workspaceDirectory: String,
    ): String {
        val steps = arguments.optJSONArray("steps")
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "MISSING_PIPELINE_STEPS")
                .put("errmsg", "Missing required 'steps'.")
                .toString()
        val completed = JSONArray()
        var lastPick = ""
        var lastOutput = JSONObject().put("ok", true)
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            val slots = step.optJSONObject("slots") ?: JSONObject()
            val callArgs = JSONObject()
            val sopId = step.optString("sop_id")
            val tool = step.optString("tool").ifBlank { step.optString("name") }
            if (sopId.isNotBlank()) callArgs.put("sop_id", sopId)
            slots.keys().forEach { key ->
                val raw = slots.optString(key)
                val interpolated = GuiPipelineSlots.interpolate(raw, lastPick)
                callArgs.put(key, interpolated)
                when (key) {
                    "query", "text" -> callArgs.put("text", interpolated)
                    "item", "target" -> {
                        callArgs.put("item", interpolated)
                        callArgs.put("target", interpolated)
                    }
                }
            }
            val name = when {
                sopId.isNotBlank() -> PhoneAppFlowMcp.RunToolName
                tool.isNotBlank() -> tool
                else -> PhoneAppFlowMcp.RunToolName
            }
            val raw = replayNamed(name, callArgs, settings, workspaceDirectory)
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("ok", false).put("raw", raw) }
            if (!parsed.optBoolean("ok", true) ||
                parsed.optString("code") in AgentModeSafety.InterruptCodes
            ) {
                if (parsed.optString("code").isBlank()) {
                    parsed.put("code", AgentModeSafety.MacroMismatchCode)
                }
                parsed.put("completed_steps", completed)
                parsed.put("failed_step", index)
                parsed.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                parsed.put("consistent", "no")
                return parsed.toString()
            }
            lastPick = parsed.optString("pick").ifBlank {
                parsed.optString("relocated_gui_label")
            }
            lastOutput = parsed
            completed.put(
                JSONObject()
                    .put("index", index)
                    .put("sop_id", parsed.optString("sop_id"))
                    .put("tool", parsed.optString("tool"))
                    .put("ok", true),
            )
        }
        return lastOutput
            .put("completed_steps", completed)
            .put("ok", true)
            .put("stdout", "Replayed ${completed.length()} GUI macro(s).")
            .toString()
    }

    private suspend fun replayNamed(
        toolName: String,
        arguments: JSONObject,
        settings: AppSettings,
        workspaceDirectory: String,
    ): String {
        val match = if (toolName.equals(PhoneAppFlowMcp.RunToolName, ignoreCase = true)) {
            val rawSopId = arguments.optString("sop_id").trim()
            val sopId = PhoneAppFlowMcp.resolveRunSopId(rawSopId, sceneStore)
                ?: return PhoneAppFlowMcp.sceneNotSopJson(rawSopId)
            catalogStore.findReadySop(sopId)
        } else {
            catalogStore.findReadyTool(toolName)
        }
            ?: return JSONObject()
                .put("ok", false)
                .put("code", AgentModeSafety.MacroMismatchCode)
                .put("errmsg", "No verified GUI flow matched '$toolName'.")
                .put("consistent", "no")
                .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                .toString()
        val primitive = match.second
        val sop = sopStore.findById(primitive.sopId)
            ?: return JSONObject()
                .put("ok", false)
                .put("errmsg", "Distilled SOP ${primitive.sopId} is missing.")
                .put("consistent", "no")
                .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                .toString()
        if (!sop.promoted ||
            sop.maturity != AgentModeSopMaturity.Ready ||
            sop.validationState != "validated"
        ) {
            return JSONObject()
                .put("ok", false)
                .put("code", "SOP_NOT_READY")
                .put("errmsg", "Distilled SOP ${primitive.sopId} is not ready for replay. Replan this segment with recall_gui_flows or dump_tree.")
                .put("sop_id", primitive.sopId)
                .put("suggested_mode", "l0")
                .put("consistent", "no")
                .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                .toString()
        }
        val environmentMismatch = sop.environmentFingerprint.isNotBlank() && run {
            val probe = dumpTree(settings, workspaceDirectory)
            val currentFingerprint = probe.optJSONObject("tree_diff")
                ?.optString("fingerprint")
                .orEmpty()
            currentFingerprint.isNotBlank() &&
                sop.environmentFingerprint.isNotBlank() &&
                currentFingerprint != sop.environmentFingerprint &&
                probe.optBoolean("structure_blind")
        }
        if (environmentMismatch) {
            return mismatch(
                JSONObject().put("ok", false),
                "The app layout changed since this SOP was taught. Re-teach or use dump_tree.",
                sop.steps.firstOrNull()?.guiLabel.orEmpty(),
                primitive,
            )
        }
        if (sop.failureStreak >= 2 && arguments.optBoolean("verify", false).not()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "SOP_NOT_READY")
                .put(
                    "errmsg",
                    "SOP ${primitive.sopId} failed ${sop.failureStreak} times in a row. " +
                        "Recall a verified flow or replan with dump_tree instead of forcing this macro.",
                )
                .put("sop_id", primitive.sopId)
                .put("suggested_mode", "l0")
                .put("consistent", "no")
                .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                .toString()
        }
        val overrideText = arguments.optString("text").ifBlank { arguments.optString("query") }
        var lastOutput = ""
        var lastPick = ""
        var nodes = emptyList<AgentModeUiTree.RichNode>()
        var compact = ""
        sop.steps.forEachIndexed { _, step ->
            val payload = step.toExecuteArguments().put("skip_capture", true)
            val normalizedAction = PhoneUiMcp.normalizeAction(step.action)
            if (step.kind == TeachingTraceDistiller.KindRepair) {
                return@forEachIndexed
            }
            val needsLocator =
                (!step.locatorSpec.isEmpty() &&
                    (normalizedAction == "tap" || normalizedAction == "click_node")) ||
                    (normalizedAction == "tap" &&
                        (step.kind == TeachingTraceDistiller.KindInstance ||
                            step.inputSlot == TeachingTraceDistiller.PickSlot ||
                            step.guiLabel.isNotBlank()))
            if (PhoneAppFlowMcp.shouldDumpBeforeStep(nodes, needsLocator)) {
                val dump = dumpTree(settings, workspaceDirectory)
                nodes = AgentModePortal.nodesFromReceipt(dump)
                compact = dump.optString("click_targets_text").ifBlank { dump.optString("ui_tree") }
            }
            fun pageReceipt(): JSONObject {
                val array = JSONArray()
                nodes.forEach { array.put(AgentModePortal.toNodeJson(it)) }
                return JSONObject()
                    .put("nodes", array)
                    .put("click_targets_text", compact)
            }
            if (!step.locatorSpec.isEmpty() &&
                (normalizedAction == "tap" || normalizedAction == "click_node")
            ) {
                val relocated = GuiLocatorCodec.resolve(nodes, step.locatorSpec)
                    ?: GuiLocatorCodec.resolveFromCompact(compact, step.locatorSpec)
                if (relocated == null) {
                    return mismatch(
                        pageReceipt(),
                        "Could not resolve the remembered control. Teach this step again; " +
                            "do not reuse hot-search text or old coordinates.",
                        step.guiLabel,
                        primitive,
                    )
                }
                payload.put("action", "click_node")
                payload.put(
                    "query",
                    relocated.id.ifBlank {
                        if (relocated.clickIndex > 0) "#${relocated.clickIndex}" else {
                            step.locatorSpec.resourceId.ifBlank {
                                step.locatorSpec.staticDesc.ifBlank { relocated.label }
                            }
                        }
                    },
                )
                payload.remove("x")
                payload.remove("y")
            } else if (
                normalizedAction == "tap" &&
                (step.kind == TeachingTraceDistiller.KindInstance ||
                    step.inputSlot == TeachingTraceDistiller.PickSlot)
            ) {
                val wanted = arguments.optString("target")
                    .ifBlank { arguments.optString("item") }
                    .ifBlank { overrideText }
                if (wanted.isBlank()) {
                    return JSONObject()
                        .put("ok", false)
                        .put("code", "MISSING_FLOW_ARGUMENT")
                        .put(
                            "errmsg",
                            "This step picks a user-specific item (for example a field). " +
                                "Pass item= for this user; do not reuse the taught tap coordinates.",
                        )
                        .put("tool", primitive.toolName)
                        .put("sop_id", primitive.sopId)
                        .toString()
                }
                val relocated = nodes.firstOrNull { it.label.contains(wanted, ignoreCase = true) }
                    ?: AgentModeUiTree.findTarget(compact, wanted)?.let { found ->
                        nodes.firstOrNull { it.label == found.label }
                    }
                if (relocated == null) {
                    return mismatch(
                        pageReceipt(),
                        "Could not find '$wanted' on this page. Teach the pick again; " +
                            "do not reuse another user's field coordinates.",
                        wanted,
                        primitive,
                    )
                }
                payload.put("action", "click_node")
                payload.put("query", relocated.id.ifBlank { "#${relocated.clickIndex}" })
                payload.remove("x")
                payload.remove("y")
                payload.put("relocated_gui_label", relocated.label)
                lastPick = relocated.label
            } else if (normalizedAction == "tap" && step.guiLabel.isNotBlank()) {
                val relocated = nodes.firstOrNull { it.label.contains(step.guiLabel, ignoreCase = true) }
                    ?: AgentModeUiTree.findTarget(compact, step.guiLabel)?.let { found ->
                        nodes.firstOrNull { it.label == found.label }
                    }
                if (relocated == null) {
                    return mismatch(
                        pageReceipt(),
                        "Expected GUI label '${step.guiLabel}' is no longer visible. Use raw agent_display controls to repair the task.",
                        step.guiLabel,
                        primitive,
                    )
                }
                payload.put("action", "click_node")
                payload.put("query", relocated.id.ifBlank { "#${relocated.clickIndex}" })
                payload.remove("x")
                payload.remove("y")
                payload.put("relocated_gui_label", relocated.label)
            }
            if (normalizedAction == "text" && step.inputSlot.isNotBlank()) {
                val value = arguments.optString(step.inputSlot)
                    .ifBlank { overrideText }
                if (value.isBlank()) {
                    return JSONObject()
                        .put("ok", false)
                        .put("code", "MISSING_FLOW_ARGUMENT")
                        .put("errmsg", "Missing required '${step.inputSlot}' for ${primitive.toolName}.")
                        .put("tool", primitive.toolName)
                        .put("sop_id", primitive.sopId)
                        .toString()
                }
                payload.put("text", value)
            } else if (
                primitive.acceptsQuery &&
                overrideText.isNotBlank() &&
                normalizedAction == "text"
            ) {
                payload.put("text", overrideText)
            }
            lastOutput = agentModeController.execute(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = workspaceDirectory,
                argumentsJson = payload.toString(),
            )
            val stepResult = runCatching { JSONObject(lastOutput) }.getOrNull()
            if (stepResult != null) {
                val nextNodes = AgentModePortal.nodesFromReceipt(stepResult)
                if (nextNodes.isNotEmpty()) {
                    nodes = nextNodes
                    compact = stepResult.optString("click_targets_text").ifBlank {
                        stepResult.optString("ui_tree")
                    }
                }
            }
            if (stepResult?.optBoolean("ok", true) == false) {
                return mismatch(
                    stepResult,
                    stepResult.optString("errmsg").ifBlank { "Macro step failed." },
                    step.guiLabel,
                    primitive,
                )
            }
            if (lastPick.isBlank()) {
                lastPick = stepResult?.optString("relocated_gui_label").orEmpty()
            }
        }
        val parsed = runCatching { JSONObject(lastOutput) }.getOrNull()
        return JSONObject(parsed?.toString() ?: "{}")
            .put("ok", parsed?.optBoolean("ok", true) ?: false)
            .put("tool", primitive.toolName)
            .put("sop_id", primitive.sopId)
            .put("app", match.first.appName)
            .put("app_name", match.first.appName)
            .put("package_name", match.first.packageName)
            .put("pick", lastPick)
            .put("stdout", "Replayed ${primitive.toolName} via ${primitive.sopId}")
            .toString()
    }

    private suspend fun dumpTree(settings: AppSettings, workspaceDirectory: String): JSONObject {
        val raw = agentModeController.execute(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = workspaceDirectory,
            argumentsJson = JSONObject()
                .put("action", "dump_tree")
                .put("cheap", true)
                .put("limit", AgentModeUiTree.DefaultTargetLimit)
                .toString(),
        )
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }
    }

    private fun mismatch(
        dump: JSONObject,
        message: String,
        expected: String,
        primitive: PhoneAppPrimitive,
    ): String {
        // Real-time demotion: a macro that no longer matches the live page loses
        // a success vote immediately instead of waiting for the outer turn verdict.
        runCatching { sopStore.recordFeedback(primitive.sopId, succeeded = false) }
        return JSONObject(dump.toString())
            .put("ok", false)
            .put("code", AgentModeSafety.MacroMismatchCode)
            .put("errmsg", message)
            .put("expected_gui_label", expected)
            .put("tool", primitive.toolName)
            .put("sop_id", primitive.sopId)
            .put("consistent", "no")
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            .put("suggested_mode", "l0")
            .also { GuiFailureSignals.attach(it, action = "run_gui_flow") }
            .also {
                signalStore?.appendReceipt(
                    attemptId = "macro-replay",
                    where = "phone_app/${primitive.toolName}",
                    result = it,
                    sopId = primitive.sopId,
                )
            }
            .toString()
    }
}
