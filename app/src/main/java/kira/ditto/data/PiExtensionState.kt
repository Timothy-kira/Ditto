package kira.ditto.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.piExtensionStateDataStore by preferencesDataStore(
    name = "aether_pi_extension_state",
)

data class PiExtensionLoadOptions(
    val disabledExtensionPaths: Set<String> = emptySet(),
    val disabledPackageSources: Set<String> = emptySet(),
) {
    fun toJsonArrays(): Pair<List<String>, List<String>> =
        disabledExtensionPaths.toList() to disabledPackageSources.toList()
}

internal val DefaultDisabledPreinstalledExtensionPaths: Set<String> = setOf(
    "/root/.aether/extensions/pi-web-access",
    "/root/.aether/extensions/pi-mcp-adapter",
    "/root/.aether/extensions/pi-subagents",
)

internal val DefaultDisabledPreinstalledExtensionIds: Set<String> = setOf(
    "import:aether:/root/.aether/extensions/pi-web-access",
    "import:aether:/root/.aether/extensions/pi-mcp-adapter",
    "import:aether:/root/.aether/extensions/pi-subagents",
)

class PiExtensionStateRepository(
    private val context: Context,
) {
    val disabledExtensionIds: Flow<Set<String>> =
        context.piExtensionStateDataStore.data.map { preferences ->
            (preferences[DISABLED_EXTENSION_IDS] ?: DefaultDisabledPreinstalledExtensionIds)
                .map(::normalizeExtensionStateId)
                .toSet()
        }

    suspend fun setEnabled(
        extensionId: String,
        enabled: Boolean,
    ) {
        val normalizedId = extensionId.trim()
        if (normalizedId.isBlank()) return
        val stableId = normalizeExtensionStateId(normalizedId)
        val baseName = stableId.substringAfterLast('/')
        context.piExtensionStateDataStore.edit { preferences ->
            val disabledIds = (preferences[DISABLED_EXTENSION_IDS] ?: DefaultDisabledPreinstalledExtensionIds)
                .mapTo(mutableSetOf(), ::normalizeExtensionStateId)
            if (enabled) {
                disabledIds.remove(stableId)
                disabledIds.removeAll { it.substringAfterLast('/') == baseName }
            } else {
                disabledIds.add(stableId)
            }
            preferences[DISABLED_EXTENSION_IDS] = disabledIds
        }
    }

    suspend fun loadOptions(): PiExtensionLoadOptions =
        loadOptionsForIds(disabledExtensionIds.first())

    suspend fun replaceDisabledExtensionIds(extensionIds: Set<String>) {
        context.piExtensionStateDataStore.edit { preferences ->
            preferences[DISABLED_EXTENSION_IDS] = extensionIds
                .map(::normalizeExtensionStateId)
                .filter(String::isNotBlank)
                .toSet()
        }
    }

    private companion object {
        val DISABLED_EXTENSION_IDS = stringSetPreferencesKey("disabled_extension_ids")
    }
}

internal fun loadOptionsForIds(
    disabledExtensionIds: Set<String>,
): PiExtensionLoadOptions {
    val disabledExtensionPaths = mutableSetOf<String>()
    val disabledPackageSources = mutableSetOf<String>()
    disabledExtensionIds.forEach { rawId ->
        val id = rawId.trim()
        when {
            id.startsWith("package:") -> {
                id.removePrefix("package:")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(disabledPackageSources::add)
            }

            id.startsWith("import:") -> {
                id.substringAfter(':', "")
                    .substringAfter(':', "")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(::normalizeImportedExtensionPath)
                    ?.let(disabledExtensionPaths::add)
            }

            id.startsWith("/") -> {
                disabledExtensionPaths.add(normalizeImportedExtensionPath(id))
            }
        }
    }
    return PiExtensionLoadOptions(
        disabledExtensionPaths = disabledExtensionPaths,
        disabledPackageSources = disabledPackageSources,
    )
}

internal fun normalizeExtensionStateId(rawId: String): String {
    val id = rawId.trim()
    if (!id.startsWith("import:")) return id
    val scope = id.substringAfter(':', "").substringBefore(':', "").trim()
    val importedPath = id.substringAfter(':', "").substringAfter(':', "").trim()
    if (scope.isBlank() || importedPath.isBlank()) return id
    return "import:$scope:${normalizeImportedExtensionPath(importedPath)}"
}

internal fun normalizeImportedExtensionPath(rawPath: String): String {
    val path = rawPath.trim()
    val guestRoots = listOf(
        "/root/.aether/extensions",
        "/root/.pi/agent/extensions",
    )
    guestRoots.forEach { guestRoot ->
        val rootIndex = path.lastIndexOf(guestRoot)
        if (rootIndex >= 0) return path.substring(rootIndex)
    }
    return path
}
