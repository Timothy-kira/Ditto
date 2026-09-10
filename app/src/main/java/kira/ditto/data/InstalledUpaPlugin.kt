package kira.ditto.data

data class InstalledUpaPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val source: String,
    val installedAtEpochMs: Long,
    val everMeTrajectory: Boolean,
    val toolNames: List<String>,
    val warningCount: Int,
    val displayName: String = "",
    val icon: String = "",
    val releasedAt: String = "",
    val sourceRaw: String = "",
) {
    fun inAppName(): String = displayName.ifBlank { name }

    fun installSource(): String {
        if (sourceRaw.isNotBlank()) return sourceRaw
        val value = source.trim()
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("github:", ignoreCase = true)
        ) {
            return value
        }
        return if (value.contains('/')) "github:$value" else value
    }
}
