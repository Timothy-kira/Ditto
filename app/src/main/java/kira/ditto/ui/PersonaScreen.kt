package kira.ditto.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.InstalledSkill
import kira.ditto.data.McpServerConfig
import kira.ditto.data.PersonaKnowledgeFile
import kira.ditto.data.PersonaKnowledgeImportPhase
import kira.ditto.data.PersonaProfile
import kira.ditto.data.humation.HumationCatalog
import kira.ditto.data.humation.HumationColorPresets
import kira.ditto.data.humation.resolveHumationState
import kira.ditto.data.humation.toOptions
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurface
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PersonaPage { List, Edit }

private val PersonaKnowledgeMimeTypes = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/msword",
    "application/vnd.ms-powerpoint",
    "application/vnd.ms-excel",
    "text/markdown",
    "text/plain",
    "text/html",
    "text/csv",
    "application/json",
    "application/rtf",
    "text/rtf",
    "*/*",
)

@Composable
internal fun PersonaListPage(
    personas: List<PersonaProfile>,
    title: String,
    description: String,
    createContentDescription: String,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onStartChat: (PersonaProfile) -> Unit,
    onBack: () -> Unit,
    joinLabel: String = "",
    onJoin: (() -> Unit)? = null,
) {
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        trailingContentDescription = createContentDescription,
        onTrailingAction = onCreate,
        secondaryTrailingIcon = if (onJoin != null) Icons.Rounded.Link else null,
        secondaryTrailingContentDescription = joinLabel,
        onSecondaryTrailingAction = { onJoin?.invoke() },
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (personas.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.persona_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.persona_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.persona_create),
                        onClick = onCreate,
                    )
                    if (onJoin != null) {
                        Spacer(Modifier.height(8.dp))
                        SettingsActionButton(
                            label = joinLabel,
                            onClick = onJoin,
                            icon = Icons.Rounded.Link,
                        )
                    }
                }
            }
        } else {
            SettingsCardGroup {
                personas.forEachIndexed { index, persona ->
                    if (index > 0) CardDivider()
                    SettingsNavRow(
                        leading = {
                            HumationAvatarImage(
                                spec = persona.avatarSpec(),
                                size = 36.dp,
                                contentDescription = persona.name,
                            )
                        },
                        title = persona.name.ifBlank { stringResource(R.string.persona_untitled) },
                        subtitle = personaSubtitle(persona),
                        showChevron = false,
                        onClick = { onStartChat(persona) },
                        onLongClick = { onOpen(persona.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonaEditPage(
    persona: PersonaProfile?,
    installedSkills: List<InstalledSkill>,
    mcpServers: List<McpServerConfig>,
    knowledgeImport: PersonaKnowledgeImportProgress? = null,
    onPersist: (PersonaProfile) -> Unit,
    onDone: (PersonaProfile) -> Unit,
    onDelete: () -> Unit,
    onImportKnowledge: (persona: PersonaProfile, bytes: ByteArray, displayName: String, mimeType: String) -> Unit,
    onRemoveKnowledge: (personaId: String, fileId: String) -> Unit,
    onStartChat: (PersonaProfile) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { runCatching { HumationCatalog.get(context) }.getOrNull() }
    val personaId = remember(persona?.id) { persona?.id ?: UUID.randomUUID().toString() }
    var name by remember(personaId) { mutableStateOf(TextFieldValue(persona?.name.orEmpty())) }
    var instructions by remember(personaId) { mutableStateOf(TextFieldValue(persona?.instructions.orEmpty())) }
    var memoryNotes by remember(personaId) { mutableStateOf(TextFieldValue(persona?.memoryNotes.orEmpty())) }
    var skillIds by remember(personaId) {
        mutableStateOf(
            if (persona != null) persona.skillIds else defaultPersonaMemorySkillIds(installedSkills),
        )
    }
    var mcpServerIds by remember(personaId) {
        mutableStateOf(
            if (persona != null) persona.mcpServerIds else defaultPersonaMemoryMcpIds(mcpServers),
        )
    }
    var avatarSeed by remember(personaId) {
        mutableStateOf(persona?.avatarSeed?.ifBlank { personaId } ?: personaId)
    }
    var avatarSelections by remember(personaId) { mutableStateOf(persona?.avatarSelections.orEmpty()) }
    var avatarColors by remember(personaId) { mutableStateOf(persona?.avatarColors.orEmpty()) }
    var avatarBackground by remember(personaId) {
        mutableStateOf(persona?.avatarBackground.orEmpty())
    }
    var colorSlot by remember(personaId) { mutableStateOf("hair") }
    val knowledgeFiles = persona?.knowledgeFiles.orEmpty()

    fun snapshot(): PersonaProfile = currentPersona(
        id = personaId,
        name = name.text,
        instructions = instructions.text,
        memoryNotes = memoryNotes.text,
        knowledgeFiles = knowledgeFiles,
        skillIds = skillIds,
        mcpServerIds = mcpServerIds,
        avatarSeed = avatarSeed,
        avatarSelections = avatarSelections,
        avatarColors = avatarColors,
        avatarBackground = avatarBackground,
        createdAtMillis = persona?.createdAtMillis ?: System.currentTimeMillis(),
        activeSessionId = persona?.activeSessionId.orEmpty(),
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val saved = snapshot()
        onPersist(saved)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@launch
            onImportKnowledge(
                saved,
                bytes,
                displayName.ifBlank { "file" },
                context.contentResolver.getType(uri).orEmpty(),
            )
        }
    }

    SubPageScaffold(
        title = if (persona == null) {
            stringResource(R.string.persona_create)
        } else {
            stringResource(R.string.persona_edit)
        },
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        trailingContentDescription = stringResource(R.string.persona_upload_knowledge),
        onTrailingAction = {
            onPersist(snapshot())
            picker.launch(PersonaKnowledgeMimeTypes)
        },
    ) {
        SettingsCardGroup {
            PersonaAvatarEditor(
                persona = snapshot(),
                catalog = catalog,
                colorSlot = colorSlot,
                onColorSlot = { colorSlot = it },
                onShuffle = {
                    avatarSeed = UUID.randomUUID().toString()
                    avatarSelections = emptyMap()
                    val palette = HumationColorPresets
                    fun pick() = palette.random()
                    avatarColors = mapOf(
                        "hair" to pick(),
                        "clothes" to pick(),
                        "bottom" to pick(),
                        "skin" to listOf("FFFFFF", "FFE8D6", "F4A261", "E9C46A", "D4A373").random(),
                    )
                    avatarBackground = listOf("F6F5F4", "FFFFFF", "8ECAE6", "FFB703", "E9C46A").random()
                },
                onSelectPart = { slotId, partId ->
                    avatarSelections = avatarSelections + (slotId to partId)
                },
                onPickColor = { slot, hex ->
                    if (slot == "background") {
                        avatarBackground = hex
                    } else {
                        avatarColors = avatarColors + (slot to hex)
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.persona_name),
                value = name,
                onValueChange = { name = it },
            )
            CardDivider()
            ChatGptTextField(
                label = stringResource(R.string.persona_instructions),
                value = instructions,
                minLines = 4,
                onValueChange = { instructions = it },
            )
            CardDivider()
            ChatGptTextField(
                label = stringResource(R.string.persona_memory),
                value = memoryNotes,
                minLines = 4,
                onValueChange = { memoryNotes = it },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.persona_knowledge),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        SettingsCardGroup {
            val shownFiles = knowledgeFiles.ifEmpty {
                if (knowledgeImport != null) {
                    listOf(
                        PersonaKnowledgeFile(
                            id = knowledgeImport.fileId.ifBlank { "importing" },
                            displayName = knowledgeImport.displayName,
                            relativePath = "",
                            sizeBytes = 0L,
                            sliceCount = 0,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            if (shownFiles.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(R.string.persona_knowledge_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            } else {
                shownFiles.forEachIndexed { index, file ->
                    if (index > 0) CardDivider()
                    val importing = knowledgeImport?.takeIf {
                        it.displayName == file.displayName || (it.fileId.isNotBlank() && it.fileId == file.id)
                    }
                    KnowledgeFileRow(
                        file = file,
                        importProgress = importing,
                        onRemove = { onRemoveKnowledge(personaId, file.id) },
                    )
                }
            }
        }

        if (installedSkills.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.persona_skills),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    installedSkills.filter { it.isEnabled }.forEach { skill ->
                        SettingsChoiceRow(
                            title = skill.name,
                            subtitle = skill.description.ifBlank { skill.actionLabel },
                            selected = skill.id in skillIds,
                            onClick = {
                                skillIds = if (skill.id in skillIds) {
                                    skillIds.filterNot { it == skill.id }
                                } else {
                                    skillIds + skill.id
                                }
                            },
                        )
                    }
                }
            }
        }

        if (mcpServers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.persona_mcp),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mcpServers.filter { it.isEnabled }.forEach { server ->
                        SettingsChoiceRow(
                            title = server.displayName,
                            subtitle = server.actionLabel.ifBlank { server.transport.transportType.name },
                            selected = server.id in mcpServerIds,
                            onClick = {
                                mcpServerIds = if (server.id in mcpServerIds) {
                                    mcpServerIds.filterNot { it == server.id }
                                } else {
                                    mcpServerIds + server.id
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingsActionButton(
            label = stringResource(R.string.common_done),
            onClick = { onDone(snapshot()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        SettingsActionButton(
            label = stringResource(R.string.persona_start_chat),
            onClick = {
                val saved = snapshot()
                onPersist(saved)
                onStartChat(saved)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (persona != null) {
            Spacer(Modifier.height(12.dp))
            SettingsActionButton(
                label = stringResource(R.string.common_delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Delete,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaAvatarEditor(
    persona: PersonaProfile,
    catalog: HumationCatalog?,
    colorSlot: String,
    onColorSlot: (String) -> Unit,
    onShuffle: () -> Unit,
    onSelectPart: (slotId: String, partId: String) -> Unit,
    onPickColor: (slot: String, hex: String) -> Unit,
) {
    val spec = persona.avatarSpec()
    val resolved = catalog?.let {
        runCatching { resolveHumationState(it.manifest, spec.toOptions()) }.getOrNull()
    }
    var partSlot by remember { mutableStateOf("head") }
    val slotLabels = mapOf(
        "head" to stringResource(R.string.persona_avatar_slot_head),
        "body" to stringResource(R.string.persona_avatar_slot_body),
        "bottom" to stringResource(R.string.persona_avatar_slot_bottom),
        "item" to stringResource(R.string.persona_avatar_slot_item),
        "glasses" to stringResource(R.string.persona_avatar_slot_glasses),
    )
    val colorLabels = mapOf(
        "hair" to stringResource(R.string.persona_avatar_color_hair),
        "skin" to stringResource(R.string.persona_avatar_color_skin),
        "clothes" to stringResource(R.string.persona_avatar_color_clothes),
        "bottom" to stringResource(R.string.persona_avatar_color_bottom),
        "stroke" to stringResource(R.string.persona_avatar_color_stroke),
        "background" to stringResource(R.string.persona_avatar_color_background),
    )
    val slotParts = catalog?.partsForSlot(partSlot).orEmpty()
    val selectedPartId = resolved?.selections?.get(partSlot).orEmpty()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.persona_avatar),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HumationAvatarImage(spec = spec, size = 88.dp, contentDescription = persona.name)
            Spacer(Modifier.width(16.dp))
            SettingsActionButton(
                label = stringResource(R.string.persona_avatar_shuffle),
                onClick = onShuffle,
                icon = Icons.Rounded.Refresh,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.persona_avatar_parts),
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            slotLabels.forEach { (slot, label) ->
                CompactChip(
                    label = label,
                    selected = partSlot == slot,
                    onClick = { partSlot = slot },
                )
            }
        }
        if (slotParts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slotParts.forEach { part ->
                    CompactChip(
                        label = part.name.replace('-', ' ').ifBlank { part.id },
                        selected = part.id == selectedPartId,
                        onClick = { onSelectPart(partSlot, part.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.persona_avatar_colors),
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colorLabels.forEach { (slot, label) ->
                CompactChip(
                    label = label,
                    selected = colorSlot == slot,
                    onClick = { onColorSlot(slot) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HumationColorPresets.forEach { hex ->
                val current = if (colorSlot == "background") {
                    spec.background.ifBlank { catalog?.manifest?.defaultsBackground.orEmpty() }
                } else {
                    spec.colors[colorSlot] ?: resolved?.colors?.get(colorSlot).orEmpty()
                }
                HumationColorSwatch(
                    hex = hex,
                    selected = current.equals(hex, ignoreCase = true),
                    onClick = { onPickColor(colorSlot, hex) },
                )
            }
        }
    }
}

@Composable
private fun KnowledgeFileRow(
    file: PersonaKnowledgeFile,
    importProgress: PersonaKnowledgeImportProgress? = null,
    onRemove: () -> Unit,
) {
    val displayedProgress by animateFloatAsState(
        targetValue = importProgress?.progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "persona_knowledge_import_progress",
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        if (importProgress != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .fillMaxWidth(displayedProgress.coerceAtLeast(0.04f))
                    .background(AetherPrimary.copy(alpha = 0.22f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                )
                Text(
                    text = when {
                        importProgress?.phase == PersonaKnowledgeImportPhase.Parsing ->
                            stringResource(R.string.persona_knowledge_parsing)
                        importProgress != null ->
                            stringResource(R.string.persona_knowledge_embedding)
                        file.sliceCount > 0 ->
                            "${formatBytes(file.sizeBytes)} · ${file.sliceCount}"
                        else -> formatBytes(file.sizeBytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            if (importProgress == null) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_remove),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onRemove),
                )
            }
        }
    }
}

private fun currentPersona(
    id: String,
    name: String,
    instructions: String,
    memoryNotes: String,
    knowledgeFiles: List<PersonaKnowledgeFile>,
    skillIds: List<String>,
    mcpServerIds: List<String>,
    avatarSeed: String,
    avatarSelections: Map<String, String>,
    avatarColors: Map<String, String>,
    avatarBackground: String,
    createdAtMillis: Long,
    activeSessionId: String = "",
): PersonaProfile = PersonaProfile(
    id = id,
    name = name.trim().ifBlank { "Persona" },
    instructions = instructions.trim(),
    memoryNotes = memoryNotes.trim(),
    knowledgeFiles = knowledgeFiles,
    skillIds = skillIds,
    mcpServerIds = mcpServerIds,
    avatarSeed = avatarSeed.ifBlank { id },
    avatarSelections = avatarSelections,
    avatarColors = avatarColors,
    avatarBackground = avatarBackground,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = System.currentTimeMillis(),
    activeSessionId = activeSessionId,
)

@Composable
private fun personaSubtitle(persona: PersonaProfile): String {
    val parts = buildList {
        if (persona.memoryNotes.isNotBlank()) add(stringResource(R.string.persona_badge_memory))
        if (persona.knowledgeFiles.isNotEmpty()) {
            add(stringResource(R.string.persona_badge_knowledge, persona.knowledgeFiles.size))
        }
        if (persona.skillIds.isNotEmpty()) {
            add(stringResource(R.string.persona_badge_skills, persona.skillIds.size))
        }
        if (persona.mcpServerIds.isNotEmpty()) {
            add(stringResource(R.string.persona_badge_mcp, persona.mcpServerIds.size))
        }
    }
    return parts.joinToString(" · ").ifBlank { stringResource(R.string.persona_badge_instructions) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun CompactChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
    } else {
        AetherSurface
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = AetherOnSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

internal fun looksLikePersonaMemoryPlugin(id: String, vararg labels: String): Boolean {
    val hay = buildString {
        append(id)
        labels.forEach { label ->
            append(' ')
            append(label)
        }
    }.lowercase()
    return "everme" in hay || "ever-me" in hay || "ever_me" in hay
}

internal fun defaultPersonaMemorySkillIds(skills: List<InstalledSkill>): List<String> =
    skills.filter { skill ->
        skill.isEnabled && looksLikePersonaMemoryPlugin(
            skill.id,
            skill.name,
            skill.actionLabel,
            skill.description,
        )
    }.map { it.id }

internal fun defaultPersonaMemoryMcpIds(servers: List<McpServerConfig>): List<String> =
    servers.filter { server ->
        server.isEnabled && looksLikePersonaMemoryPlugin(
            server.id,
            server.displayName,
            server.actionLabel,
        )
    }.map { it.id }

