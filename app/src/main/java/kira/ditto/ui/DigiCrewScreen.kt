package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.InstalledSkill
import kira.ditto.data.McpServerConfig
import kira.ditto.data.PersonaProfile
import kira.ditto.ui.theme.AetherOnSurfaceVariant

private enum class DigiCrewPage { List, Edit, Join }

/**
 * DigiCrew is where personas live. The shell is DigiCrew's — its title and its three top-bar
 * buttons (back, invite code, new) — while every page inside it is the persona surface: the
 * roster, and the persona editor. Crews themselves are no longer edited by hand; the only crew
 * affordance left is joining someone else's by invite code.
 */
@Composable
fun DigiCrewScreen(
    personas: List<PersonaProfile>,
    installedSkills: List<InstalledSkill>,
    mcpServers: List<McpServerConfig>,
    knowledgeImport: PersonaKnowledgeImportProgress? = null,
    onSavePersona: (PersonaProfile) -> Unit,
    onDeletePersona: (String) -> Unit,
    onImportKnowledge: (persona: PersonaProfile, bytes: ByteArray, displayName: String, mimeType: String) -> Unit,
    onRemoveKnowledge: (personaId: String, fileId: String) -> Unit,
    onStartChat: (PersonaProfile) -> Unit,
    onJoinInvite: (String) -> Unit,
    onBack: () -> Unit,
    initialEditId: String = "",
    onConsumedEditId: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(DigiCrewPage.List.name) }
    var editingId by rememberSaveable { mutableStateOf("") }
    val currentPage = runCatching { DigiCrewPage.valueOf(page) }.getOrDefault(DigiCrewPage.List)
    val editing = personas.firstOrNull { it.id == editingId }

    fun openCreate() {
        editingId = ""
        page = DigiCrewPage.Edit.name
    }

    fun openEdit(id: String) {
        editingId = id
        page = DigiCrewPage.Edit.name
    }

    LaunchedEffect(initialEditId) {
        if (initialEditId.isNotBlank()) {
            openEdit(initialEditId)
            onConsumedEditId()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PredictiveBackHost(
            onBack = {
                if (currentPage != DigiCrewPage.List) {
                    page = DigiCrewPage.List.name
                } else {
                    onBack()
                }
            },
        ) {
            AnimatedContent(
                targetState = currentPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    pageStackContentTransform(
                        isForward = initialState == DigiCrewPage.List && targetState != DigiCrewPage.List,
                    )
                },
                label = "digicrew_page_stack",
            ) { targetPage ->
                Box(pageStackGpuLayer()) {
                    when (targetPage) {
                        DigiCrewPage.List -> PersonaListPage(
                            personas = personas,
                            title = stringResource(R.string.digicrew_title),
                            description = stringResource(R.string.digicrew_description),
                            createContentDescription = stringResource(R.string.persona_create),
                            onCreate = ::openCreate,
                            onOpen = ::openEdit,
                            onStartChat = onStartChat,
                            onBack = onBack,
                            joinLabel = stringResource(R.string.digicrew_join),
                            onJoin = { page = DigiCrewPage.Join.name },
                        )
                        DigiCrewPage.Join -> DigiCrewJoinPage(
                            onJoin = { code ->
                                onJoinInvite(code)
                                page = DigiCrewPage.List.name
                            },
                            onBack = { page = DigiCrewPage.List.name },
                        )
                        DigiCrewPage.Edit -> PersonaEditPage(
                            persona = editing,
                            installedSkills = installedSkills,
                            mcpServers = mcpServers,
                            knowledgeImport = knowledgeImport?.takeIf {
                                it.personaId == editing?.id || it.personaId == editingId
                            },
                            onPersist = { saved ->
                                onSavePersona(saved)
                                editingId = saved.id
                            },
                            onDone = { saved ->
                                onSavePersona(saved)
                                editingId = saved.id
                                page = DigiCrewPage.List.name
                            },
                            onDelete = {
                                editing?.id?.let(onDeletePersona)
                                page = DigiCrewPage.List.name
                            },
                            onImportKnowledge = onImportKnowledge,
                            onRemoveKnowledge = onRemoveKnowledge,
                            onStartChat = { saved ->
                                onSavePersona(saved)
                                onStartChat(saved)
                            },
                            onBack = { page = DigiCrewPage.List.name },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DigiCrewJoinPage(
    onJoin: (String) -> Unit,
    onBack: () -> Unit,
) {
    var invite by remember { mutableStateOf(TextFieldValue("")) }
    SubPageScaffold(
        title = stringResource(R.string.digicrew_join),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.digicrew_join_body),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.digicrew_invite_code),
                value = invite,
                minLines = 2,
                onValueChange = { invite = it },
            )
        }
        Spacer(Modifier.height(20.dp))
        SettingsActionButton(
            label = stringResource(R.string.digicrew_join),
            onClick = { onJoin(invite.text) },
            modifier = Modifier.fillMaxWidth(),
            enabled = invite.text.isNotBlank(),
            icon = Icons.Rounded.Link,
        )
    }
}
