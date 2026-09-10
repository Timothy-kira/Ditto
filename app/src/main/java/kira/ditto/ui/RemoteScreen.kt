package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.RemoteMachine
import kira.ditto.data.isLegacyLanKimiWebUrl
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant

private enum class RemotePage { List, Add }

@Composable
fun RemoteScreen(
    machines: List<RemoteMachine>,
    sessions: List<ChatSession>,
    isConnecting: Boolean,
    localRunning: Boolean,
    localUrl: String,
    localBusy: Boolean,
    onConnectUrl: (String) -> Unit,
    onOpenRemote: (RemoteMachine) -> Unit,
    onRemoveRemote: (RemoteMachine) -> Unit,
    onStartLocal: () -> Unit,
    onStopLocal: () -> Unit,
    onRefreshLocal: () -> Unit,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(RemotePage.List.name) }
    val currentPage = runCatching { RemotePage.valueOf(page) }.getOrDefault(RemotePage.List)

    LaunchedEffect(Unit) {
        onRefreshLocal()
    }

    Box(Modifier.fillMaxSize()) {
        PredictiveBackHost(
            onBack = {
                when (currentPage) {
                    RemotePage.Add -> page = RemotePage.List.name
                    RemotePage.List -> onBack()
                }
            },
        ) {
            AnimatedContent(
                targetState = currentPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    pageStackContentTransform(
                        isForward = initialState == RemotePage.List && targetState == RemotePage.Add,
                    )
                },
                label = "remote_page_stack",
            ) { targetPage ->
                Box(pageStackGpuLayer()) {
                    when (targetPage) {
                        RemotePage.List -> RemoteListPage(
                            machines = machines,
                            sessions = sessions,
                            isConnecting = isConnecting,
                            localRunning = localRunning,
                            localUrl = localUrl,
                            localBusy = localBusy,
                            onAdd = { page = RemotePage.Add.name },
                            onOpenRemote = onOpenRemote,
                            onRemoveRemote = onRemoveRemote,
                            onStartLocal = onStartLocal,
                            onStopLocal = onStopLocal,
                            onBack = onBack,
                        )
                        RemotePage.Add -> RemoteAddPage(
                            isConnecting = isConnecting,
                            onConnectUrl = onConnectUrl,
                            onBack = { page = RemotePage.List.name },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteListPage(
    machines: List<RemoteMachine>,
    sessions: List<ChatSession>,
    isConnecting: Boolean,
    localRunning: Boolean,
    localUrl: String,
    localBusy: Boolean,
    onAdd: () -> Unit,
    onOpenRemote: (RemoteMachine) -> Unit,
    onRemoveRemote: (RemoteMachine) -> Unit,
    onStartLocal: () -> Unit,
    onStopLocal: () -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.remote_title),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        trailingContentDescription = stringResource(R.string.remote_add),
        onTrailingAction = onAdd,
    ) {
        Text(
            text = stringResource(R.string.remote_official_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            if (localRunning) {
                Text(
                    text = stringResource(R.string.remote_local_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (localUrl.isNotBlank()) {
                    Text(
                        text = localUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                SettingsActionButton(
                    label = stringResource(R.string.remote_stop_local),
                    onClick = onStopLocal,
                    enabled = !localBusy,
                    isLoading = localBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                SettingsActionButton(
                    label = stringResource(R.string.remote_start_local),
                    onClick = onStartLocal,
                    enabled = !localBusy,
                    isLoading = localBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (isConnecting) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.remote_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (machines.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = LucideIcons.SquareTerminal,
                        contentDescription = null,
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.remote_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.remote_official_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.remote_add),
                        onClick = onAdd,
                        icon = Icons.Rounded.Add,
                    )
                }
            }
        } else {
            SettingsCardGroup {
                machines.forEach { machine ->
                    val sessionCount = sessions.count { it.remoteMachineId == machine.id }
                    val subtitle = when {
                        isLegacyLanKimiWebUrl(machine.baseUrl) ->
                            stringResource(R.string.remote_official_legacy)
                        sessionCount > 0 ->
                            stringResource(R.string.remote_machine_sessions, sessionCount, machine.baseUrl)
                        else -> machine.baseUrl
                    }
                    SettingsNavRow(
                        icon = LucideIcons.SquareTerminal,
                        title = machine.name,
                        subtitle = subtitle,
                        subtitleMaxLines = 2,
                        onClick = { onOpenRemote(machine) },
                        onLongClick = { onRemoveRemote(machine) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.remote_remove_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun RemoteAddPage(
    isConnecting: Boolean,
    onConnectUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    SubPageScaffold(
        title = stringResource(R.string.remote_add),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.remote_official_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.remote_url_label),
                value = url,
                placeholder = stringResource(R.string.remote_official_hint),
                onValueChange = { url = it },
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsActionButton(
            label = stringResource(R.string.remote_official_open),
            onClick = { onConnectUrl(url.text) },
            enabled = url.text.isNotBlank() && !isConnecting,
            isLoading = isConnecting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isConnecting) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.remote_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
