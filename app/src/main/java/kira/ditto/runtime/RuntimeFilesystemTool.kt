package kira.ditto.runtime

import kira.ditto.data.AppSettings
import kira.ditto.termux.ShellCommandExecutor
import kira.ditto.termux.TermuxFilesystemTool
import org.json.JSONObject

class RuntimeFilesystemTool(
    private val router: RuntimeRouter,
) {
    suspend fun executeRead(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeRead(args) }

    suspend fun executeWrite(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeWrite(args) }

    suspend fun executeEdit(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeEdit(args) }

    suspend fun executeGrep(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeGrep(args) }

    suspend fun executeFind(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeFind(args) }

    suspend fun executeLs(settings: AppSettings, argumentsJson: String): String =
        execute(settings, argumentsJson) { tool, args -> tool.executeLs(args) }

    private suspend fun execute(
        settings: AppSettings,
        argumentsJson: String,
        block: suspend (TermuxFilesystemTool, String) -> String,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Arguments were not valid JSON.").toString()
        val environment = arguments.runtimeEnvironment()
        val runtime = router.runtimeFor(settings, environment)
            ?: return router.setupRequiredError(environment)
        arguments.remove("environment")
        arguments.remove("runtime")
        val tool = TermuxFilesystemTool(
            commandExecutor = ShellCommandExecutor { command, workingDirectory ->
                runtime.executeCommand(command, workingDirectory)
            },
            homeDirectory = runtime.homeDirectory,
        )
        val raw = block(tool, arguments.toString())
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        json.put("runtime", runtime.id.storageValue)
        return json.toString()
    }
}
