package kira.ditto.runtime

import kira.ditto.data.LocalRuntimeId
import kira.ditto.termux.TermuxBashTool
import kira.ditto.termux.TermuxContract
import kira.ditto.termux.TermuxSetupIssue

class TermuxRuntime(
    private val bashTool: TermuxBashTool,
) : LocalRuntime {
    override val id: LocalRuntimeId = LocalRuntimeId.Termux
    override val displayName: String = "Termux"
    override val homeDirectory: String = TermuxContract.HomeDirectory
    override val workspaceRoot: String = "${TermuxContract.HomeDirectory}/.aether/workspace"
    override val managedCommandsDirectory: String = TermuxContract.ManagedCommandsDirectory

    override suspend fun inspectSetup(): LocalRuntimeSetupState {
        val state = bashTool.inspectSetup()
        return LocalRuntimeSetupState(
            runtimeId = id,
            issue = when (state.issue) {
                TermuxSetupIssue.Ready -> LocalRuntimeIssue.Ready
                TermuxSetupIssue.NotInstalled -> LocalRuntimeIssue.NotInstalled
                TermuxSetupIssue.PermissionMissing -> LocalRuntimeIssue.PermissionMissing
                TermuxSetupIssue.ExternalAppsDisabled -> LocalRuntimeIssue.ExternalAppsDisabled
                TermuxSetupIssue.DispatchFailed -> LocalRuntimeIssue.DispatchFailed
            },
            detail = state.detail,
        )
    }

    override suspend fun execute(
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)?,
    ): String = bashTool.execute(argumentsJson, onProgress)

    override suspend fun fetchExecution(argumentsJson: String): String =
        bashTool.fetchExecution(argumentsJson)

    override suspend fun killExecution(argumentsJson: String): String =
        bashTool.killExecution(argumentsJson)

    override suspend fun killExecutionByRunId(
        runId: String,
        tailBytes: Int,
    ): String = bashTool.killExecutionByRunId(runId, tailBytes)

    override suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
    ): String = bashTool.executeCommand(command, workingDirectory, awaitTimeoutMillis)
}
