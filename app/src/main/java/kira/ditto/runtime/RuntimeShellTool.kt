package kira.ditto.runtime

import kira.ditto.data.AppSettings
import org.json.JSONObject

class RuntimeShellTool(
    private val router: RuntimeRouter,
) {
    suspend fun execute(
        settings: AppSettings,
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)? = null,
    ): String {
        val arguments = parseArguments(argumentsJson)
            ?: return invalidArguments("Arguments were not valid JSON.")
        val environment = arguments.runtimeEnvironment()
        val runtime = router.runtimeFor(settings, environment)
            ?: return router.setupRequiredError(environment)
        arguments.remove("environment")
        arguments.remove("runtime")
        return prefixRunId(
            runtime = runtime,
            rawResult = runtime.execute(arguments.toString(), onProgress),
        )
    }

    suspend fun fetch(
        settings: AppSettings,
        argumentsJson: String,
    ): String {
        val arguments = parseArguments(argumentsJson)
            ?: return invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        router.runtimeForRunId(runId)?.let { (runtime, innerRunId) ->
            arguments.put("run_id", innerRunId)
            return prefixRunId(runtime, runtime.fetchExecution(arguments.toString()))
        }
        val runtime = router.runtimeFor(settings, arguments.runtimeEnvironment())
            ?: return router.setupRequiredError(arguments.runtimeEnvironment())
        return prefixRunId(runtime, runtime.fetchExecution(arguments.toString()))
    }

    suspend fun kill(
        settings: AppSettings,
        argumentsJson: String,
    ): String {
        val arguments = parseArguments(argumentsJson)
            ?: return invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        router.runtimeForRunId(runId)?.let { (runtime, innerRunId) ->
            arguments.put("run_id", innerRunId)
            return prefixRunId(runtime, runtime.killExecution(arguments.toString()))
        }
        val runtime = router.runtimeFor(settings, arguments.runtimeEnvironment())
            ?: return router.setupRequiredError(arguments.runtimeEnvironment())
        return prefixRunId(runtime, runtime.killExecution(arguments.toString()))
    }

    suspend fun killByRunId(runId: String): String {
        router.runtimeForRunId(runId)?.let { (runtime, innerRunId) ->
            return prefixRunId(runtime, runtime.killExecutionByRunId(innerRunId))
        }
        return prefixRunId(
            runtime = router.runtimeById(kira.ditto.data.LocalRuntimeId.Alpine),
            rawResult = router.runtimeById(kira.ditto.data.LocalRuntimeId.Alpine)
                .killExecutionByRunId(runId),
        )
    }

    private fun prefixRunId(
        runtime: LocalRuntime,
        rawResult: String,
    ): String {
        val json = parseArguments(rawResult) ?: return rawResult
        json.put("runtime", runtime.id.storageValue)
        val runId = json.optString("run_id").trim()
        if (runId.isNotBlank() && !runId.startsWith("${runtime.id.storageValue}:")) {
            json.put("run_id", "${runtime.id.storageValue}:$runId")
        }
        return json.toString()
    }

    private fun parseArguments(rawValue: String): JSONObject? =
        runCatching { JSONObject(rawValue) }.getOrNull()

    private fun invalidArguments(message: String): String =
        JSONObject().put("ok", false).put("errmsg", message).toString()
}
