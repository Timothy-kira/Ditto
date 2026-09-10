package kira.ditto.data

enum class LocalRuntimeId(val storageValue: String) {
    Alpine("alpine"),
}

data class AlpineEnvironmentVariable(
    val name: String,
    val value: String,
)

fun normalizeAlpineEnvironmentVariables(
    variables: List<AlpineEnvironmentVariable>,
): List<AlpineEnvironmentVariable> = variables
    .map { it.copy(name = it.name.trim(), value = it.value.trim()) }
    .filter { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    .distinctBy { it.name }

interface AetherDiagnosticLogger {
    data object NoOp : AetherDiagnosticLogger
}
