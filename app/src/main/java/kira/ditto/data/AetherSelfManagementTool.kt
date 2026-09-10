package kira.ditto.data

import kira.ditto.data.pi.PiKernelBridge
import kira.ditto.termux.TermuxSetupIssue
import kira.ditto.termux.TermuxSetupState
import java.util.Locale
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private const val DefaultDeveloperLogTailChars = 20_000
private const val MaxDeveloperLogTailChars = 80_000

class AetherSelfManagementTool(
    private val settingsRepository: SettingsRepository,
    private val extensionsRepository: AgentExtensionsRepository,
    private val skillManager: AgentSkillManager,
    private val rootSetupController: RootSetupController,
    private val agentModeController: AgentModeController,
    private val scheduledTaskManager: ScheduledTaskManager,
    private val piKernelBridge: PiKernelBridge,
    private val sessionId: String,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    fun toolDefinitions(): List<JSONObject> = listOf(
        buildAetherToolDefinition(
            name = "aether_config_get",
            description = "Read Aether app configuration for general preferences, reliability, Skills, Termux, Agent Mode, scheduled tasks, and developer diagnostics. Provider and model configuration is intentionally omitted.",
            properties = JSONObject().apply {
                put(
                    "categories",
                    JSONObject().apply {
                        put("type", "array")
                        put("description", "Optional categories to read. Omit or pass an empty array to read all supported categories.")
                        put(
                            "items",
                            JSONObject().apply {
                                put("type", "string")
                                put(
                                    "enum",
                                    JSONArray(
                                        listOf(
                                            "general",
                                            "reliability",
                                            "agent_skills",
                                            "termux",
                                            "agent_mode",
                                            "scheduled_tasks",
                                            "developer",
                                        )
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        ),
        buildAetherToolDefinition(
            name = "aether_config_set",
            description = "Modify allowed Aether settings. This tool cannot modify LLM provider, model, base URL, provider API keys, or default model selections.",
            properties = JSONObject().apply {
                put(
                    "category",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("general", "reliability", "termux", "agent_mode")))
                        put("description", "Allowed settings category to update.")
                    },
                )
                put(
                    "settings",
                    JSONObject().apply {
                        put("type", "object")
                        put("description", "Patch object for the selected category.")
                        put("additionalProperties", true)
                    },
                )
            },
            required = listOf("category", "settings"),
        ),
        buildAetherToolDefinition(
            name = "aether_skill_manage",
            description = "List, install, enable, disable, or remove Ditto Agent Skills. Remote installs accept GitHub repository URLs or zip URLs supported by Ditto.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("list", "install_remote", "remove", "set_enabled")))
                    },
                )
                put("skill_id", JSONObject().apply { put("type", "string") })
                put("url", JSONObject().apply { put("type", "string") })
                put("enabled", JSONObject().apply { put("type", "boolean") })
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_termux_manage",
            description = "Inspect or repair Aether's Termux integration without using the bash tool. Root setup may trigger a system su request.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("inspect_setup", "inspect_root_setup", "configure_root_access")))
                    },
                )
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_runtime_manage",
            description = "Read or switch the current chat session runtime. The switch applies to the next model call and preserves independent Alpine and Termux workspaces.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("status", "set")))
                    },
                )
                put(
                    "runtime",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("alpine", "termux")))
                    },
                )
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_agent_mode_manage",
            description = "Read or change Agent Mode authorization settings, refresh status, request Shizuku permission, and manage the current virtual display. Does not operate the display UI; use agent_display for that when Agent Mode is enabled for the chat.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "enum",
                            JSONArray(
                                listOf(
                                    "inspect_authorization",
                                    "set_authorization",
                                    "refresh_authorization",
                                    "request_shizuku_permission",
                                    "stop_display",
                                    "refresh_displays",
                                )
                            ),
                        )
                    },
                )
                put("enabled", JSONObject().apply { put("type", "boolean") })
                put(
                    "method",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("root", "shizuku")))
                    },
                )
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_scheduled_task_manage",
            description = "List, create, update, enable, disable, or remove Aether scheduled tasks. Scheduled tasks wake Aether at the next matching time and run the prompt as an automated Agent turn.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("list", "create", "update", "remove", "set_enabled")))
                    },
                )
                put("task_id", JSONObject().apply { put("type", "string") })
                put("name", JSONObject().apply { put("type", "string") })
                put("prompt", JSONObject().apply { put("type", "string") })
                put("session_id", JSONObject().apply { put("type", "string") })
                put("enabled", JSONObject().apply { put("type", "boolean") })
                put(
                    "schedule",
                    JSONObject().apply {
                        put("type", "object")
                        put(
                            "description",
                            "Schedule object. interval: {type:'interval', interval_minutes:30, active_start_time:'13:00', active_end_time:'18:00'}; daily: {type:'daily', times:['09:00','17:30']}; weekly: {type:'weekly', days_of_week:['mon','fri'], time:'10:00'}.",
                        )
                        put("additionalProperties", true)
                    },
                )
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_extension_manage",
            description = "List or reload source-compatible Pi extensions for the current Aether session, or invoke a registered Pi extension command. Extensions are discovered from ~/.pi/agent/extensions, the workspace .pi/extensions directory, and ~/.aether/extensions. Custom Pi TUI components are not supported.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("list", "reload", "invoke_command")))
                    },
                )
                put(
                    "command",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "Registered extension command name, with or without a leading slash.")
                    },
                )
                put(
                    "args",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "Raw command argument string.")
                    },
                )
            },
            required = listOf("action"),
        ),
        buildAetherToolDefinition(
            name = "aether_developer_manage",
            description = "Read Aether developer diagnostics such as recent diagnostic events or last crash details. Sensitive values are redacted.",
            properties = JSONObject().apply {
                put(
                    "action",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("read_diagnostics")))
                    },
                )
                put(
                    "include",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray(listOf("events", "last_crash", "both")))
                    },
                )
                put("max_chars", JSONObject().apply { put("type", "integer") })
            },
            required = listOf("action"),
        ),
    )

    suspend fun execute(
        toolName: String,
        argumentsJson: String,
    ): String = when (toolName) {
        "aether_config_get" -> executeConfigGet(argumentsJson)
        "aether_config_set" -> executeConfigSet(argumentsJson)
        "aether_skill_manage" -> executeSkillManage(argumentsJson)
        "aether_termux_manage" -> executeTermuxManage(argumentsJson)
        "aether_agent_mode_manage" -> executeAgentModeManage(argumentsJson)
        "aether_scheduled_task_manage" -> executeScheduledTaskManage(argumentsJson)
        "aether_extension_manage" -> executeExtensionManage(argumentsJson)
        "aether_developer_manage" -> executeDeveloperManage(argumentsJson)
        else -> failure("Unknown Aether self-management tool '$toolName'.")
    }

    private suspend fun executeConfigGet(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        val requestedCategories = parseCategories(arguments.optJSONArray("categories"))
        val categories = if (requestedCategories.isEmpty()) {
            SupportedConfigCategories
        } else {
            requestedCategories
        }
        val settings = settingsRepository.settings.first()
        val extensionState = extensionsRepository.extensionState.first()
        val payload = JSONObject()
        categories.forEach { category ->
            when (category) {
                "general" -> payload.put("general", generalSettingsJson(settings))
                "reliability" -> payload.put("reliability", reliabilitySettingsJson(settings))
                "agent_skills" -> payload.put("agent_skills", skillsJson(extensionState.installedSkills))
                "termux" -> payload.put("termux", termuxStatusJson())
                "agent_mode" -> payload.put("agent_mode", agentModeSettingsJson(settings))
                "scheduled_tasks" -> payload.put("scheduled_tasks", scheduledTasksJson(scheduledTaskManager.snapshot()))
                "developer" -> payload.put("developer", developerSummaryJson())
                else -> return failure("Unsupported category '$category'.")
            }
        }
        return success(payload) {
            put("stdout", "Read ${categories.size} Aether configuration categories.")
        }
    }

    private suspend fun executeConfigSet(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        val category = arguments.optString("category").trim().lowercase(Locale.US)
        val patch = arguments.optJSONObject("settings") ?: return failure("settings must be an object.")
        val current = settingsRepository.settings.first()
        val updated = when (category) {
            "general" -> current.copy(
                language = if (patch.hasAny("language", "app_language")) {
                    AppLanguage.fromStorage(patch.optStringAny("language", "app_language"), current.language)
                } else {
                    current.language
                },
                themeMode = if (patch.hasAny("theme_mode", "themeMode")) {
                    AppThemeMode.fromStorage(patch.optStringAny("theme_mode", "themeMode"))
                } else {
                    current.themeMode
                },
            )

            "reliability" -> current.copy(
                llmInactivityReconnectTimeoutSeconds = if (
                    patch.hasAny(
                            "llm_inactivity_reconnect_timeout_seconds",
                            "llmInactivityReconnectTimeoutSeconds",
                        )
                ) {
                    normalizeLlmInactivityReconnectTimeoutSeconds(
                        patch.optIntAny(
                            "llm_inactivity_reconnect_timeout_seconds",
                            "llmInactivityReconnectTimeoutSeconds",
                        )
                    )
                } else {
                    current.llmInactivityReconnectTimeoutSeconds
                },
                keepTasksRunningInBackground = if (
                    patch.hasAny("keep_tasks_running_in_background", "keepTasksRunningInBackground")
                ) {
                    patch.optBooleanAny(
                        "keep_tasks_running_in_background",
                        "keepTasksRunningInBackground",
                        current.keepTasksRunningInBackground,
                    )
                } else {
                    current.keepTasksRunningInBackground
                },
                notifyOnTaskCompletion = if (
                    patch.hasAny("notify_on_task_completion", "notifyOnTaskCompletion")
                ) {
                    patch.optBooleanAny(
                        "notify_on_task_completion",
                        "notifyOnTaskCompletion",
                        current.notifyOnTaskCompletion,
                    )
                } else {
                    current.notifyOnTaskCompletion
                },
            )

            "termux" -> current.copy(
                termuxEnvironmentVariables = if (patch.hasAny("environment_variables", "environmentVariables")) {
                    parseTermuxEnvironmentVariablesPatch(
                        patch.optJSONArray("environment_variables")
                            ?: patch.optJSONArray("environmentVariables")
                    )
                } else {
                    current.termuxEnvironmentVariables
                },
            )

            "agent_mode" -> current.copy(
                agentModeAuthorizationEnabled = if (
                    patch.hasAny("agent_mode_authorization_enabled", "authorization_enabled", "enabled")
                ) {
                    patch.optBooleanAny(
                        "agent_mode_authorization_enabled",
                        "authorization_enabled",
                        current.agentModeAuthorizationEnabled,
                    )
                } else {
                    current.agentModeAuthorizationEnabled
                },
                agentModeAuthorizationMethod = if (
                    patch.hasAny("agent_mode_authorization_method", "authorization_method", "method")
                ) {
                    AgentModeAuthorizationMethod.fromStorage(
                        patch.optStringAny(
                            "agent_mode_authorization_method",
                            "authorization_method",
                            "method",
                        ),
                        defaultValue = current.agentModeAuthorizationMethod,
                    )
                } else {
                    current.agentModeAuthorizationMethod
                },
            )

            else -> return failure("Unsupported or read-only category '$category'.")
        }
        settingsRepository.updateSettings(updated)
        if (category == "agent_mode") {
            agentModeController.refreshAuthorization(updated)
        }
        return success(JSONObject().put(category, configCategoryJson(category, updated))) {
            put("stdout", "Updated Aether $category settings.")
        }
    }

    private suspend fun executeSkillManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        return when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
            "list" -> {
                val skills = extensionsRepository.extensionState.first().installedSkills
                success(JSONObject().put("skills", skillsJson(skills))) {
                    put("stdout", "Listed ${skills.size} installed skills.")
                }
            }

            "install_remote" -> {
                val url = arguments.optString("url").trim()
                if (url.isBlank()) return failure("url is required for install_remote.")
                val installed = skillManager.installSkillFromRemote(url)
                    .getOrElse { throwable -> return failure(throwable.message ?: "Skill install failed.") }
                piKernelBridge.reloadSession(sessionId)
                success(JSONObject().put("skill", installedSkillJson(installed))) {
                    put("stdout", "Installed skill '${installed.name}'.")
                }
            }

            "remove" -> {
                val skillId = arguments.optString("skill_id").trim()
                    .ifBlank { arguments.optString("skillId").trim() }
                if (skillId.isBlank()) return failure("skill_id is required for remove.")
                skillManager.uninstallSkill(skillId)
                    .getOrElse { throwable -> return failure(throwable.message ?: "Skill removal failed.") }
                piKernelBridge.reloadSession(sessionId)
                success(JSONObject().put("skill_id", skillId)) {
                    put("stdout", "Removed skill '$skillId'.")
                }
            }

            "set_enabled" -> {
                val skillId = arguments.optString("skill_id").trim()
                    .ifBlank { arguments.optString("skillId").trim() }
                if (skillId.isBlank()) return failure("skill_id is required for set_enabled.")
                if (!arguments.has("enabled")) return failure("enabled is required for set_enabled.")
                val enabled = arguments.optBoolean("enabled")
                extensionsRepository.setSkillEnabled(skillId, enabled)
                piKernelBridge.reloadSession(sessionId)
                success(JSONObject().put("skill_id", skillId).put("enabled", enabled)) {
                    put("stdout", "Set skill '$skillId' enabled=$enabled.")
                }
            }

            else -> failure("Unsupported skill action '$action'.")
        }
    }

    private suspend fun executeTermuxManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        return when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
            "inspect_setup" -> success(
                JSONObject().put(
                    "termux",
                    termuxSetupStateJson(
                        TermuxSetupState(
                            issue = TermuxSetupIssue.NotInstalled,
                            detail = "Termux App integration has been removed. Local tools run in Alpine.",
                        ),
                    ),
                ),
            )
            "inspect_root_setup" -> success(JSONObject().put("root_setup", rootSetupStateJson(rootSetupController.inspect())))
            "configure_root_access" -> {
                val rootState = rootSetupController.configureLocalAccess()
                success(JSONObject().put("root_setup", rootSetupStateJson(rootState))) {
                    put(
                        "stdout",
                        if (rootState.isReady) {
                            "Root setup completed."
                        } else {
                            "Root setup did not complete: ${rootState.detail.ifBlank { rootState.issue.name }}"
                        },
                    )
                }
            }

            else -> failure("Unsupported Termux action '$action'.")
        }
    }

    private suspend fun executeAgentModeManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        val current = settingsRepository.settings.first()
        return when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
            "inspect_authorization" -> success(JSONObject().put("agent_mode", agentModeSettingsJson(current)))

            "set_authorization" -> {
                val updated = current.copy(
                    agentModeAuthorizationEnabled = arguments.optNullableBoolean("enabled")
                        ?: current.agentModeAuthorizationEnabled,
                    agentModeAuthorizationMethod = arguments.optString("method").trim()
                        .takeIf(String::isNotBlank)
                        ?.let {
                            AgentModeAuthorizationMethod.fromStorage(
                                it,
                                defaultValue = current.agentModeAuthorizationMethod,
                            )
                        }
                        ?: current.agentModeAuthorizationMethod,
                )
                settingsRepository.updateSettings(updated)
                agentModeController.refreshAuthorization(updated)
                success(JSONObject().put("agent_mode", agentModeSettingsJson(updated))) {
                    put("stdout", "Updated Agent Mode authorization settings.")
                }
            }

            "refresh_authorization" -> {
                agentModeController.refreshAuthorization(current)
                success(JSONObject().put("agent_mode", agentModeSettingsJson(current))) {
                    put("stdout", "Refreshed Agent Mode authorization.")
                }
            }

            "request_shizuku_permission" -> {
                val state = agentModeController.requestShizukuPermission()
                success(JSONObject().put("authorization", agentModeAuthorizationStateJson(state))) {
                    put("stdout", state.detail.ifBlank { state.issue.name })
                }
            }

            "stop_display" -> {
                agentModeController.stopDisplay()
                success(JSONObject().put("display", agentModeDisplayStateJson(agentModeController.displayState.value))) {
                    put("stdout", "Stopped Agent Mode display if one was active.")
                }
            }

            "refresh_displays" -> {
                agentModeController.refreshDisplays(current)
                success(JSONObject().put("display", agentModeDisplayStateJson(agentModeController.displayState.value))) {
                    put("stdout", "Refreshed Agent Mode display list.")
                }
            }

            else -> failure("Unsupported Agent Mode action '$action'.")
        }
    }

    private suspend fun executeScheduledTaskManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        return when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
            "list" -> {
                val tasks = scheduledTaskManager.snapshot()
                success(JSONObject().put("tasks", scheduledTasksJson(tasks))) {
                    put("stdout", "Listed ${tasks.size} scheduled tasks.")
                }
            }

            "create" -> {
                val name = arguments.optString("name").trim().ifBlank { "Scheduled task" }
                val prompt = arguments.optString("prompt").trim()
                if (prompt.isBlank()) return failure("prompt is required for create.")
                val schedule = parseScheduledTaskSchedule(arguments.optJSONObject("schedule"))
                    ?: return failure("schedule is required and must be a valid interval, daily, or weekly schedule.")
                val task = scheduledTaskManager.upsertTask(
                    ScheduledTask(
                        name = name,
                        prompt = prompt,
                        schedule = schedule,
                        isEnabled = arguments.optNullableBoolean("enabled") ?: true,
                        sessionId = "",
                        createdBy = ScheduledTaskCreator.Agent,
                    )
                )
                success(JSONObject().put("task", task.toPublicJson())) {
                    put("stdout", "Created scheduled task '${task.name}'.")
                }
            }

            "update" -> {
                val taskId = optAetherString(arguments, "task_id", "taskId")
                if (taskId.isBlank()) return failure("task_id is required for update.")
                val existing = scheduledTaskManager.findTask(taskId)
                    ?: return failure("Scheduled task '$taskId' was not found.")
                val schedule = if (arguments.has("schedule") && !arguments.isNull("schedule")) {
                    parseScheduledTaskSchedule(arguments.optJSONObject("schedule"))
                        ?: return failure("schedule must be a valid interval, daily, or weekly schedule.")
                } else {
                    existing.schedule
                }
                val enabled = arguments.optNullableBoolean("enabled") ?: existing.isEnabled
                val updated = scheduledTaskManager.upsertTask(
                    existing.copy(
                        name = arguments.optString("name").trim().ifBlank { existing.name },
                        prompt = arguments.optString("prompt").trim().ifBlank { existing.prompt },
                        schedule = schedule,
                        isEnabled = enabled,
                        sessionId = "",
                    )
                )
                success(JSONObject().put("task", updated.toPublicJson())) {
                    put("stdout", "Updated scheduled task '${updated.name}'.")
                }
            }

            "remove" -> {
                val taskId = optAetherString(arguments, "task_id", "taskId")
                if (taskId.isBlank()) return failure("task_id is required for remove.")
                scheduledTaskManager.removeTask(taskId)
                success(JSONObject().put("task_id", taskId)) {
                    put("stdout", "Removed scheduled task '$taskId'.")
                }
            }

            "set_enabled" -> {
                val taskId = optAetherString(arguments, "task_id", "taskId")
                if (taskId.isBlank()) return failure("task_id is required for set_enabled.")
                if (!arguments.has("enabled")) return failure("enabled is required for set_enabled.")
                val task = scheduledTaskManager.setTaskEnabled(taskId, arguments.optBoolean("enabled"))
                    ?: return failure("Scheduled task '$taskId' was not found.")
                success(JSONObject().put("task", task.toPublicJson())) {
                    put("stdout", "Set scheduled task '${task.name}' enabled=${task.isEnabled}.")
                }
            }

            else -> failure("Unsupported scheduled task action '$action'.")
        }
    }

    private fun executeDeveloperManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        return when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
            "read_diagnostics" -> {
                val include = arguments.optString("include").trim().lowercase(Locale.US).ifBlank { "both" }
                val maxChars = arguments.optInt("max_chars", DefaultDeveloperLogTailChars)
                    .coerceIn(1_000, MaxDeveloperLogTailChars)
                val output = JSONObject()
                if (include == "events" || include == "both") {
                    output.put("events_tail", diagnosticLogger.readEventsText().takeLast(maxChars))
                }
                if (include == "last_crash" || include == "both") {
                    output.put("last_crash", diagnosticLogger.readLastCrashText().takeLast(maxChars))
                }
                success(output) {
                    put("stdout", "Read Aether diagnostics.")
                }
            }

            else -> failure("Unsupported developer action '$action'.")
        }
    }

    private suspend fun executeExtensionManage(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidJson()
        return runCatching {
            when (val action = arguments.optString("action").trim().lowercase(Locale.US)) {
                "list" -> {
                    val payload = piKernelBridge.listExtensions(sessionId)
                    success(payload) {
                        put(
                            "stdout",
                            "Found ${payload.optJSONArray("extension_paths")?.length() ?: 0} loaded Pi extensions.",
                        )
                    }
                }

                "reload" -> {
                    val payload = piKernelBridge.reloadExtensions(sessionId)
                    success(payload) {
                        put(
                            "stdout",
                            when {
                                payload.optBoolean("scheduled") ->
                                    "Pi extension reload is scheduled for the end of the current turn."
                                payload.optBoolean("reloaded") ->
                                    "Reloaded Pi extensions."
                                else ->
                                    "Pi extension reload was rejected. Inspect the returned errors."
                            },
                        )
                    }
                }

                "invoke_command" -> {
                    val command = arguments.optString("command").trim()
                    if (command.isBlank()) return failure("command is required for invoke_command.")
                    val payload = piKernelBridge.invokeExtensionCommand(
                        sessionId = sessionId,
                        command = command,
                        args = arguments.optString("args"),
                    )
                    success(payload) {
                        put("stdout", "Invoked Pi extension command '$command'.")
                    }
                }

                else -> failure("Unsupported Pi extension action '$action'.")
            }
        }.getOrElse { throwable ->
            failure(throwable.message ?: "Pi extension operation failed.")
        }
    }

    private suspend fun termuxStatusJson(): JSONObject =
        JSONObject()
            .put(
                "setup",
                termuxSetupStateJson(
                    TermuxSetupState(
                        issue = TermuxSetupIssue.NotInstalled,
                        detail = "Termux App integration has been removed. Local tools run in Alpine.",
                    ),
                ),
            )
            .put("root_setup", rootSetupStateJson(rootSetupController.inspect()))
            .put(
                "environment_variables",
                JSONArray().apply {
                    settingsRepository.settings.first().termuxEnvironmentVariables.forEach { variable ->
                        put(
                            JSONObject()
                                .put("name", variable.name)
                                .put(
                                    "value",
                                    if (shouldRedactKey(variable.name)) redactSecret(variable.value) else variable.value,
                                )
                        )
                    }
                },
            )

    private fun generalSettingsJson(settings: AppSettings): JSONObject =
        JSONObject()
            .put("language", settings.language.storageValue)
            .put("theme_mode", settings.themeMode.storageValue)

    private fun reliabilitySettingsJson(settings: AppSettings): JSONObject =
        JSONObject()
            .put("llm_inactivity_reconnect_timeout_seconds", settings.llmInactivityReconnectTimeoutSeconds)
            .put("keep_tasks_running_in_background", settings.keepTasksRunningInBackground)
            .put("notify_on_task_completion", settings.notifyOnTaskCompletion)

    private fun agentModeSettingsJson(settings: AppSettings): JSONObject =
        JSONObject()
            .put("authorization_enabled", settings.agentModeAuthorizationEnabled)
            .put("authorization_method", settings.agentModeAuthorizationMethod.storageValue)
            .put("authorization", agentModeAuthorizationStateJson(agentModeController.authorizationState.value))
            .put("display", agentModeDisplayStateJson(agentModeController.displayState.value))

    private fun configCategoryJson(
        category: String,
        settings: AppSettings,
    ): JSONObject = when (category) {
        "general" -> generalSettingsJson(settings)
        "reliability" -> reliabilitySettingsJson(settings)
        "agent_mode" -> agentModeSettingsJson(settings)
        else -> JSONObject()
    }

    private fun developerSummaryJson(): JSONObject =
        JSONObject()
            .put("diagnostic_events_available", diagnosticLogger.readEventsText().isNotBlank())
            .put("last_crash_available", diagnosticLogger.readLastCrashText().isNotBlank())
            .put("tools", JSONArray(listOf("aether_developer_manage")))

    private fun scheduledTasksJson(tasks: List<ScheduledTask>): JSONArray =
        JSONArray().apply {
            tasks.sortedWith(compareBy<ScheduledTask> { it.nextRunAtMillis ?: Long.MAX_VALUE }.thenBy { it.name.lowercase(Locale.US) })
                .forEach { put(it.toPublicJson()) }
        }

    private fun skillsJson(skills: List<InstalledSkill>): JSONArray =
        JSONArray().apply {
            skills.sortedBy { it.name.lowercase(Locale.US) }.forEach { put(installedSkillJson(it)) }
        }

    private fun installedSkillJson(skill: InstalledSkill): JSONObject =
        JSONObject()
            .put("id", skill.id)
            .put("name", skill.name)
            .put("description", skill.description.replace("Aether", "Ditto"))
            .put("action_label", skill.quickActionLabel())
            .put("is_enabled", skill.isEnabled)
            .put("source", JSONObject().apply {
                put("kind", skill.source.kind.storageValue)
                put("label", skill.source.label)
                put("uri", skill.source.uri)
                put("ref", skill.source.ref)
                put("subpath", skill.source.subpath)
            })
            .put("allowed_tools", JSONArray().apply { skill.allowedTools.forEach(::put) })
            .put("diagnostics", JSONArray().apply { skill.diagnostics.forEach(::put) })
            .put("resource_count", skill.resourceEntries.size)
            .put("installed_at_millis", skill.installedAtMillis)
            .put("updated_at_millis", skill.updatedAtMillis)

    private fun parseTermuxEnvironmentVariablesPatch(
        array: JSONArray?,
    ): List<TermuxEnvironmentVariable> {
        if (array == null) return emptyList()
        return normalizeTermuxEnvironmentVariables(
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TermuxEnvironmentVariable(
                            name = item.optString("name").ifBlank { item.optString("key") },
                            value = item.optString("value"),
                        )
                    )
                }
            }
        )
    }

    private fun termuxSetupStateJson(state: TermuxSetupState): JSONObject =
        JSONObject()
            .put("issue", state.issue.name)
            .put("detail", state.detail)
            .put("previously_configured", state.previouslyConfigured)
            .put("is_ready", state.isReady)

    private fun rootSetupStateJson(state: RootSetupState): JSONObject =
        JSONObject()
            .put("issue", state.issue.name)
            .put("detail", state.detail)
            .put("root_available", state.rootAvailable)
            .put("su_path", state.suPath)
            .put("did_launch_termux_for_background", state.didLaunchTermuxForBackground)
            .put("last_updated_millis", state.lastUpdatedMillis)
            .put("is_ready", state.isReady)
            .put("is_running", state.isRunning)

    private fun agentModeAuthorizationStateJson(state: AgentModeAuthorizationState): JSONObject =
        JSONObject()
            .put("issue", state.issue.name)
            .put("detail", state.detail)
            .put("is_ready", state.isReady)

    private fun agentModeDisplayStateJson(state: AgentModeDisplayState): JSONObject =
        JSONObject()
            .put("is_active", state.isActive)
            .put("display_id", state.displayId ?: JSONObject.NULL)
            .put("width", state.width)
            .put("height", state.height)
            .put("is_live_preview_active", state.isLivePreviewActive)
            .put("status", state.status)
            .put("last_updated_millis", state.lastUpdatedMillis)
            .put(
                "displays",
                JSONArray().apply {
                    state.displays.forEach { display ->
                        put(
                            JSONObject()
                                .put("display_id", display.displayId)
                                .put("name", display.name)
                                .put("width", display.width)
                                .put("height", display.height)
                                .put("is_aether_display", display.isAetherDisplay)
                        )
                    }
                },
            )

    private fun buildAetherToolDefinition(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", buildPiToolParameters(properties, required))
            },
        )
    }

    private fun buildPiToolParameters(
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject(properties.toString()))
        put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    private fun keyValueArraySchema(description: String): JSONObject =
        JSONObject().apply {
            put("type", "array")
            put("description", description)
            put(
                "items",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties",
                        JSONObject().apply {
                            put("key", JSONObject().apply { put("type", "string") })
                            put("value", JSONObject().apply { put("type", "string") })
                        },
                    )
                    put("required", JSONArray(listOf("key", "value")))
                    put("additionalProperties", false)
                },
            )
        }

    private fun stdioEnvironmentSchema(): JSONObject =
        JSONObject().apply {
            put(
                "description",
                "Runtime environment for stdio MCP servers when provided as a string, or environment variables when provided as an array.",
            )
            put(
                "oneOf",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("default", "termux", "alpine")))
                        },
                    )
                    put(keyValueArraySchema("Environment variables for stdio MCP servers."))
                },
            )
        }

    private fun stringArraySchema(description: String): JSONObject =
        JSONObject().apply {
            put("type", "array")
            put("description", description)
            put(
                "items",
                JSONObject().apply {
                    put("type", "string")
                },
            )
        }

    private fun parseArguments(argumentsJson: String): JSONObject? =
        runCatching { JSONObject(argumentsJson) }.getOrNull()

    private fun parseCategories(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val category = array.optString(index).trim().lowercase(Locale.US)
                if (category.isNotBlank()) add(category)
            }
        }.distinct()
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }

    private fun success(
        payload: JSONObject = JSONObject(),
        configure: JSONObject.() -> Unit = {},
    ): String = JSONObject().apply {
        put("ok", true)
        payload.keys().forEach { key -> put(key, payload.opt(key)) }
        configure()
    }.toString()

    private fun failure(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("errmsg", message)
            .toString()

    private fun invalidJson(): String = failure("Arguments were not valid JSON.")

    private fun redactSecret(value: String): String =
        if (value.isBlank()) "" else "[REDACTED]"

    private fun shouldRedactKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return SensitiveKeyFragments.any { it in normalized }
    }

    private fun JSONObject.hasAny(vararg names: String): Boolean =
        names.any(::has)

    private fun JSONObject.optStringAny(vararg names: String): String =
        names.firstOrNull(::has)?.let(::optString).orEmpty()

    private fun JSONObject.optIntAny(vararg names: String): Int? =
        names.firstOrNull(::has)?.let { optInt(it) }

    private fun JSONObject.optBooleanAny(
        primary: String,
        secondary: String,
        defaultValue: Boolean,
    ): Boolean = when {
        has(primary) -> optBoolean(primary, defaultValue)
        has(secondary) -> optBoolean(secondary, defaultValue)
        else -> defaultValue
    }

    private fun JSONObject.optBooleanAny(
        first: String,
        second: String,
        third: String,
        defaultValue: Boolean,
    ): Boolean = when {
        has(first) -> optBoolean(first, defaultValue)
        has(second) -> optBoolean(second, defaultValue)
        has(third) -> optBoolean(third, defaultValue)
        else -> defaultValue
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? =
        if (has(name)) optBoolean(name) else null

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (has(name)) optLong(name) else null

    private fun optAetherString(
        arguments: JSONObject,
        primary: String,
        secondary: String,
    ): String = arguments.optString(primary).trim().ifBlank {
        arguments.optString(secondary).trim()
    }

    private companion object {
        val SupportedConfigCategories = listOf(
            "general",
            "reliability",
            "agent_skills",
            "termux",
            "agent_mode",
            "scheduled_tasks",
            "developer",
        )
        val SensitiveKeyFragments = listOf(
            "apikey",
            "api_key",
            "authorization",
            "auth",
            "bearer",
            "token",
            "secret",
            "password",
            "key",
        )
    }
}
