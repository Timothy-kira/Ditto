package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.HostSecretStore
import kira.ditto.data.InstalledUpaPlugin
import kira.ditto.data.KaggleCliRuntime
import kira.ditto.data.inferMcpSecretSlots
import kira.ditto.ui.theme.AetherOnPrimary
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.upa.UpaInstallPreview
import kira.ditto.upa.UpaManifest
import kira.ditto.upa.UpaPermissionScope
import kira.ditto.upa.UpaRiskFinding
import kira.ditto.upa.UpaRiskLevel
import kira.ditto.upa.classifyUpaPermission
import kira.ditto.upa.inAppDisplayName
import kira.ditto.upa.parseUpaInstallRef

private enum class UpaPluginPage { List, Spec, Install, Detail }

@Composable
fun UpaPluginScreen(
    installedPlugins: List<InstalledUpaPlugin>,
    isCheckingUpdates: Boolean,
    disabledUiPlugins: Set<String>,
    revokedPermissions: Map<String, List<String>>,
    mcpServers: List<kira.ditto.data.McpServerConfig> = emptyList(),
    mcpBindings: Map<String, List<String>> = emptyMap(),
    onPluginRenderUiChange: (String, Boolean) -> Unit,
    onPluginPermissionChange: (String, String, Boolean) -> Unit,
    onPluginMcpBindingChange: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onScanSource: (String, (kira.ditto.data.UpaScanProgress) -> Unit, (Result<List<UpaInstallPreview>>) -> Unit) -> Unit,
    onInstallPreview: (UpaInstallPreview, (Result<InstalledUpaPlugin>) -> Unit) -> Unit,
    onUninstall: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(UpaPluginPage.List.name) }
    var selectedPluginId by rememberSaveable { mutableStateOf("") }
    val currentPage = runCatching { UpaPluginPage.valueOf(page) }.getOrDefault(UpaPluginPage.List)

    Box(Modifier.fillMaxSize()) {
        PredictiveBackHost(
            onBack = {
                if (currentPage != UpaPluginPage.List) {
                    page = UpaPluginPage.List.name
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
                        isForward = initialState == UpaPluginPage.List && targetState != UpaPluginPage.List,
                    )
                },
                label = "upa_plugin_page_stack",
            ) { targetPage ->
                Box(pageStackGpuLayer()) {
                when (targetPage) {
                            UpaPluginPage.List -> UpaPluginListPage(
                                installedPlugins = installedPlugins,
                                isCheckingUpdates = isCheckingUpdates,
                                onInstall = { page = UpaPluginPage.Install.name },
                                onOpenSpec = { page = UpaPluginPage.Spec.name },
                                onOpenPlugin = { id ->
                                    selectedPluginId = id
                                    page = UpaPluginPage.Detail.name
                                },
                                onCheckUpdates = onCheckUpdates,
                                onBack = onBack,
                            )
                            UpaPluginPage.Spec -> UpaPluginSpecPage(
                                onBack = { page = UpaPluginPage.List.name },
                            )
                            UpaPluginPage.Install -> UpaPluginInstallPage(
                                onScanSource = onScanSource,
                                onInstallPreview = onInstallPreview,
                                onBack = { page = UpaPluginPage.List.name },
                            )
                            UpaPluginPage.Detail -> UpaPluginDetailPage(
                                plugin = installedPlugins.firstOrNull { it.id == selectedPluginId },
                                disabledUiPlugins = disabledUiPlugins,
                                revokedPermissions = revokedPermissions,
                                mcpServers = mcpServers,
                                mcpBindings = mcpBindings,
                                onPluginRenderUiChange = onPluginRenderUiChange,
                                onPluginPermissionChange = onPluginPermissionChange,
                                onPluginMcpBindingChange = onPluginMcpBindingChange,
                                onUninstall = { id ->
                                    onUninstall(id)
                                    selectedPluginId = ""
                                    page = UpaPluginPage.List.name
                                },
                                onMissing = {
                                    selectedPluginId = ""
                                    page = UpaPluginPage.List.name
                                },
                                onBack = { page = UpaPluginPage.List.name },
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun UpaPluginListPage(
    installedPlugins: List<InstalledUpaPlugin>,
    isCheckingUpdates: Boolean,
    onInstall: () -> Unit,
    onOpenSpec: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.upa_plugin_title),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        trailingContentDescription = stringResource(R.string.upa_plugin_install),
        onTrailingAction = onInstall,
    ) {
        Text(
            text = stringResource(R.string.upa_plugin_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            SettingsNavRow(
                icon = LucideIcons.Plug,
                title = stringResource(R.string.upa_plugin_spec),
                subtitle = stringResource(R.string.upa_plugin_spec_subtitle),
                onClick = onOpenSpec,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.upa_plugin_check_updates),
                subtitle = if (isCheckingUpdates) {
                    stringResource(R.string.upa_plugin_checking_updates)
                } else {
                    stringResource(R.string.upa_plugin_check_updates_subtitle)
                },
                showChevron = false,
                subtitleMaxLines = 2,
                onClick = onCheckUpdates,
            )
        }
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            if (installedPlugins.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.upa_plugin_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.upa_plugin_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.upa_plugin_install),
                        onClick = onInstall,
                        icon = Icons.Rounded.Link,
                    )
                }
            } else {
                installedPlugins.forEachIndexed { index, plugin ->
                    if (index > 0) CardDivider()
                    val subtitle = if (plugin.id == "health.apps.card") {
                        "${plugin.version} · 已从仓库移除，建议卸载"
                    } else {
                        "${plugin.version} · ${plugin.source}"
                    }
                    if (isKaggleBrand(plugin.icon, plugin.id)) {
                        SettingsNavRow(
                            iconPainter = painterResource(R.drawable.ic_kaggle),
                            title = plugin.inAppName(),
                            subtitle = subtitle,
                            showChevron = true,
                            onClick = { onOpenPlugin(plugin.id) },
                        )
                    } else {
                        SettingsNavRow(
                            icon = upaBrandImageVector(plugin.icon),
                            title = plugin.inAppName(),
                            subtitle = subtitle,
                            showChevron = true,
                            onClick = { onOpenPlugin(plugin.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpaPluginDetailPage(
    plugin: InstalledUpaPlugin?,
    disabledUiPlugins: Set<String>,
    revokedPermissions: Map<String, List<String>>,
    mcpServers: List<kira.ditto.data.McpServerConfig>,
    mcpBindings: Map<String, List<String>>,
    onPluginRenderUiChange: (String, Boolean) -> Unit,
    onPluginPermissionChange: (String, String, Boolean) -> Unit,
    onPluginMcpBindingChange: (String, String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onMissing: () -> Unit,
    onBack: () -> Unit,
) {
    val library = rememberUpaPluginLibrary()
    var pendingUninstall by remember { mutableStateOf(false) }
    val pluginId = plugin?.id.orEmpty()
    val manifest = remember(pluginId, library) {
        if (pluginId.isBlank() || library == null) null else library.readManifestBlocking(pluginId)
    }
    LaunchedEffect(plugin) {
        if (plugin == null) onMissing()
    }
    if (plugin == null) return
    val permissions = manifest?.permissions.orEmpty()
    val revoked = revokedPermissions[plugin.id].orEmpty().toSet()
    SubPageScaffold(
        title = plugin.inAppName(),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Delete,
        trailingContentDescription = stringResource(R.string.upa_plugin_uninstall),
        onTrailingAction = { pendingUninstall = true },
    ) {
        Text(
            text = "${plugin.version} · ${plugin.source}",
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.upa_plugin_render_ui),
                    subtitle = "",
                    checked = plugin.id !in disabledUiPlugins,
                    onCheckedChange = { onPluginRenderUiChange(plugin.id, it) },
                )
            }
        }
        val bindableServers = mcpServers.filter { server ->
            server.isEnabled && !kira.ditto.upa.isUpaMcpServerId(server.id)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.upa_plugin_bind_mcp),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = stringResource(R.string.upa_plugin_bind_mcp_body),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (bindableServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.upa_plugin_bind_mcp_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    bindableServers.forEachIndexed { index, server ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        SettingsToggleRow(
                            title = server.displayName.ifBlank { server.id },
                            subtitle = server.id,
                            checked = server.id in mcpBindings[plugin.id].orEmpty(),
                            onCheckedChange = { onPluginMcpBindingChange(plugin.id, server.id, it) },
                        )
                    }
                }
            }
        }
        val boundServers = mcpServers.filter { server ->
            server.id in mcpBindings[plugin.id].orEmpty()
        }
        val boundSlots = boundServers.flatMap(::inferMcpSecretSlots)
        if (boundSlots.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            McpPluginSecretSection(
                servers = boundServers,
                slots = boundSlots,
            )
        }
        if (permissions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.upa_plugin_permissions),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            SettingsCardGroup {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    permissions.forEachIndexed { index, wire ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        SettingsToggleRow(
                            title = upaPermissionLabel(wire),
                            subtitle = "",
                            checked = wire !in revoked,
                            onCheckedChange = { onPluginPermissionChange(plugin.id, wire, it) },
                        )
                    }
                }
            }
        }
        if (plugin.id == "kaggle.cli.card") {
            Spacer(Modifier.height(16.dp))
            KaggleAccountSection()
        }
    }
    if (pendingUninstall) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = false },
            title = { Text(stringResource(R.string.upa_plugin_uninstall_title)) },
            text = { Text(stringResource(R.string.upa_plugin_uninstall_body, plugin.inAppName())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = false
                        onUninstall(plugin.id)
                    },
                ) {
                    Text(stringResource(R.string.upa_plugin_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun McpPluginSecretSection(
    servers: List<kira.ditto.data.McpServerConfig>,
    slots: List<kira.ditto.data.McpSecretSlot>,
) {
    val context = LocalContext.current
    val store = remember(context) { HostSecretStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var values by remember(slots.map { it.id }) {
        mutableStateOf(slots.associate { it.id to TextFieldValue("") })
    }
    val configured = remember(revision, slots) {
        slots.filter { store.has(it.id) }.map { it.id }.toSet()
    }
    McpSecretSlotsEditor(
        servers = servers,
        slots = slots,
        values = values,
        configuredIds = configured,
        onValueChange = { id, next -> values = values + (id to next) },
        onSaveSlot = { slot ->
            val value = values[slot.id]?.text.orEmpty().trim()
            if (value.isNotBlank()) {
                runCatching { store.put(slot.id, value) }
                values = values + (slot.id to TextFieldValue(""))
                revision += 1
            }
        },
        onDeleteSlot = { slot ->
            runCatching { store.delete(slot.id) }
            values = values + (slot.id to TextFieldValue(""))
            revision += 1
        },
        title = stringResource(R.string.upa_plugin_mcp_secrets),
        body = stringResource(R.string.upa_plugin_mcp_secrets_body),
    )
}

@Composable
private fun KaggleAccountSection() {
    val context = LocalContext.current
    val runtime = remember(context) { KaggleCliRuntime(context) }
    var username by remember { mutableStateOf(TextFieldValue("")) }
    var token by remember { mutableStateOf(TextFieldValue("")) }
    var revision by remember { mutableIntStateOf(0) }
    val accounts = remember(revision) { runtime.listAccounts() }
    val activeId = remember(revision) { runtime.activeAccountId() }
    val currentLabel = stringResource(R.string.upa_plugin_kaggle_current)
    val selectedLabel = stringResource(R.string.upa_plugin_kaggle_selected)
    Text(
        text = stringResource(R.string.upa_plugin_accounts),
        style = MaterialTheme.typography.bodySmall,
        color = AetherOnSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    SettingsCardGroup {
        if (accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.upa_plugin_kaggle_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            accounts.forEachIndexed { index, account ->
                if (index > 0) CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = account.selected,
                        onCheckedChange = {
                            runtime.toggleSelectedAccount(account.id)
                            revision += 1
                        },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    ) {
                        Text(
                            text = account.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                        )
                        Text(
                            text = buildList {
                                if (account.id == activeId) add(currentLabel)
                                if (account.selected) add(selectedLabel)
                            }.joinToString(" · ").ifBlank { selectedLabel },
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            runtime.setActiveAccount(account.id)
                            revision += 1
                        },
                    ) {
                        Text(stringResource(R.string.upa_plugin_kaggle_use))
                    }
                    IconButton(
                        onClick = {
                            runtime.removeAccount(account.id)
                            revision += 1
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.upa_plugin_uninstall),
                            tint = AetherOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsCardGroup {
        ChatGptTextField(
            label = stringResource(R.string.upa_plugin_username),
            value = username,
            onValueChange = { username = it },
        )
        ChatGptTextField(
            label = stringResource(R.string.upa_plugin_token),
            value = token,
            isSecret = true,
            placeholder = stringResource(R.string.upa_plugin_token_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            onValueChange = { token = it },
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingsActionButton(
        label = stringResource(R.string.upa_plugin_kaggle_add),
        onClick = {
            runCatching { runtime.addAccount(username.text, token.text) }
                .onSuccess {
                    username = TextFieldValue("")
                    token = TextFieldValue("")
                    revision += 1
                }
        },
        icon = Icons.Rounded.Add,
    )
}

@Composable
private fun upaPermissionLabel(wire: String): String = when (wire) {
    "network" -> stringResource(R.string.upa_perm_network)
    "storage.read" -> stringResource(R.string.upa_perm_storage_read)
    "storage.write" -> stringResource(R.string.upa_perm_storage_write)
    "clipboard" -> stringResource(R.string.upa_perm_clipboard)
    "html.sandbox" -> stringResource(R.string.upa_perm_html_sandbox)
    "host.apps.read" -> stringResource(R.string.upa_perm_host_apps_read)
    "host.health.read" -> stringResource(R.string.upa_perm_host_health_read)
    "host.location.read" -> stringResource(R.string.upa_perm_host_location_read)
    "host.calendar.read" -> stringResource(R.string.upa_perm_host_calendar_read)
    "host.storage.read" -> stringResource(R.string.upa_perm_host_storage_read)
    else -> wire
}

@Composable
private fun UpaPluginSpecPage(
    onBack: () -> Unit,
) {
    var expandedKey by rememberSaveable { mutableStateOf("") }
    SubPageScaffold(
        title = stringResource(R.string.upa_plugin_spec),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.upa_plugin_spec_body),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            UpaSpecCapRow(
                key = "mcp",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.Zap,
                title = stringResource(R.string.upa_plugin_cap_mcp),
                subtitle = stringResource(R.string.upa_plugin_cap_mcp_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "ui",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.Plug,
                title = stringResource(R.string.upa_plugin_cap_ui),
                subtitle = stringResource(R.string.upa_plugin_cap_ui_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "a2a",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.Brain,
                title = stringResource(R.string.upa_plugin_cap_a2a),
                subtitle = stringResource(R.string.upa_plugin_cap_a2a_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "template",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.SquarePen,
                title = stringResource(R.string.upa_plugin_cap_template),
                subtitle = stringResource(R.string.upa_plugin_cap_template_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "html",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.Cursor,
                title = stringResource(R.string.upa_plugin_cap_html),
                subtitle = stringResource(R.string.upa_plugin_cap_html_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "everme",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.ChartNoAxesColumn,
                title = stringResource(R.string.upa_plugin_cap_everme),
                subtitle = stringResource(R.string.upa_plugin_cap_everme_body),
            )
            CardDivider()
            UpaSpecCapRow(
                key = "permissions",
                expandedKey = expandedKey,
                onToggle = { expandedKey = it },
                icon = LucideIcons.Brain,
                title = stringResource(R.string.upa_plugin_cap_permissions),
                subtitle = stringResource(R.string.upa_plugin_cap_permissions_body),
            )
        }
    }
}

@Composable
private fun UpaSpecCapRow(
    key: String,
    expandedKey: String,
    onToggle: (String) -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val expanded = expandedKey == key
    SettingsNavRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        showChevron = false,
        subtitleMaxLines = if (expanded) Int.MAX_VALUE else 1,
        onClick = { onToggle(if (expanded) "" else key) },
    )
}

@Composable
private fun UpaPluginInstallPage(
    onScanSource: (String, (kira.ditto.data.UpaScanProgress) -> Unit, (Result<List<UpaInstallPreview>>) -> Unit) -> Unit,
    onInstallPreview: (UpaInstallPreview, (Result<InstalledUpaPlugin>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var source by remember { mutableStateOf(TextFieldValue("")) }
    val parsed = remember(source.text) { parseUpaInstallRef(source.text) }
    var previews by remember { mutableStateOf<List<UpaInstallPreview>>(emptyList()) }
    var scanError by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var actionPhase by remember { mutableStateOf(UpaInstallActionPhase.Parse) }
    var actionRunning by remember { mutableStateOf(false) }
    var actionProgress by remember { mutableFloatStateOf(0f) }
    var scanProgress by remember { mutableStateOf<kira.ditto.data.UpaScanProgress?>(null) }
    var parseRejected by remember { mutableStateOf(false) }
    var parseSuccessFlash by remember { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()
    val installable = previews.filterNot { it.blocked }
    SubPageScaffold(
        title = stringResource(R.string.upa_plugin_install),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.upa_plugin_install_body),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.upa_plugin_install_hint),
                value = source,
                minLines = 2,
                onValueChange = {
                    source = it
                    previews = emptyList()
                    scanError = ""
                    actionPhase = UpaInstallActionPhase.Parse
                    parseRejected = false
                    parseSuccessFlash = false
                },
            )
        }
        if (parseRejected && source.text.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.upa_plugin_source_bad),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        val actionLabel = when {
            actionRunning && actionPhase == UpaInstallActionPhase.Scan && scanProgress != null ->
                upaScanPhaseLabel(scanProgress!!)
            parseSuccessFlash -> stringResource(R.string.upa_plugin_parse_ok)
            actionPhase == UpaInstallActionPhase.Parse -> stringResource(R.string.upa_plugin_parse)
            previews.isEmpty() -> stringResource(R.string.upa_plugin_scan)
            else -> stringResource(R.string.upa_plugin_scan_again)
        }
        Spacer(Modifier.height(16.dp))
        UpaInstallActionButton(
            label = actionLabel,
            running = actionRunning,
            progress = actionProgress,
            enabled = source.text.isNotBlank(),
            icon = LucideIcons.Search,
            onClick = {
                when (actionPhase) {
                    UpaInstallActionPhase.Parse -> {
                        actionRunning = true
                        parseRejected = false
                        actionScope.launch {
                            animateUpaActionProgress(ParseActionDurationMillis) { actionProgress = it }
                            actionRunning = false
                            actionProgress = 0f
                            if (parsed != null) {
                                parseSuccessFlash = true
                                delay(700)
                                parseSuccessFlash = false
                                actionPhase = UpaInstallActionPhase.Scan
                            } else {
                                parseRejected = true
                            }
                        }
                    }
                    UpaInstallActionPhase.Scan -> {
                        actionRunning = true
                        scanError = ""
                        actionProgress = 0.02f
                        scanProgress = kira.ditto.data.UpaScanProgress(
                            0.02f,
                            kira.ditto.data.UpaScanPhase.ParseSource,
                        )
                        onScanSource(
                            source.text,
                            { progress ->
                                actionProgress = progress.fraction
                                scanProgress = progress
                            },
                        ) { result ->
                            actionScope.launch {
                                actionProgress = 1f
                                delay(160)
                                actionRunning = false
                                actionProgress = 0f
                                scanProgress = null
                                result.onSuccess { previews = it }
                                    .onFailure { scanError = it.message.orEmpty() }
                            }
                        }
                    }
                }
            },
        )
        if (scanError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.upa_plugin_scan_failed, scanError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        previews.forEach { scanned ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = scanned.manifest.inAppDisplayName().ifBlank { scanned.manifest.name },
                style = MaterialTheme.typography.titleSmall,
                color = AetherOnSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (scanned.blocked) R.string.upa_plugin_blocked else R.string.upa_plugin_scan_ok,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (scanned.blocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    AetherOnSurface
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            UpaPermissionGrantCard(scanned.manifest)
            Spacer(Modifier.height(12.dp))
            SettingsCardGroup {
                scanned.findings.forEachIndexed { index, finding ->
                    if (index > 0) CardDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = upaRiskLevelLabel(finding.level),
                            style = MaterialTheme.typography.labelMedium,
                            color = upaRiskLevelColor(finding.level),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = upaRiskFindingText(finding),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                        )
                    }
                }
            }
        }
        if (installable.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SettingsActionButton(
                label = if (installable.size == 1) {
                    stringResource(R.string.upa_plugin_confirm_install)
                } else {
                    stringResource(R.string.upa_plugin_confirm_install_all, installable.size)
                },
                onClick = {
                    installing = true
                    fun installNext(index: Int) {
                        if (index >= installable.size) {
                            installing = false
                            return
                        }
                        onInstallPreview(installable[index]) { result ->
                            if (result.isFailure || index == installable.lastIndex) {
                                installing = false
                            } else {
                                installNext(index + 1)
                            }
                        }
                    }
                    installNext(0)
                },
                isLoading = installing,
                icon = Icons.Rounded.Add,
            )
        }
    }
}

private enum class UpaInstallActionPhase { Parse, Scan }

@Composable
private fun upaScanPhaseLabel(progress: kira.ditto.data.UpaScanProgress): String {
    val host = progress.host.lowercase()
    return when (progress.phase) {
        kira.ditto.data.UpaScanPhase.ParseSource ->
            stringResource(R.string.upa_plugin_scan_parse_source)
        kira.ditto.data.UpaScanPhase.DownloadManifest -> when {
            host.contains("jsdelivr") -> stringResource(R.string.upa_plugin_scan_download_jsdelivr)
            host.contains("github") -> stringResource(R.string.upa_plugin_scan_download_github)
            else -> stringResource(R.string.upa_plugin_scan_download)
        }
        kira.ditto.data.UpaScanPhase.ParseManifest ->
            stringResource(R.string.upa_plugin_scan_parse_manifest)
        kira.ditto.data.UpaScanPhase.ListFiles ->
            stringResource(R.string.upa_plugin_scan_list_files)
        kira.ditto.data.UpaScanPhase.ScanSecrets ->
            stringResource(R.string.upa_plugin_scan_secrets)
        kira.ditto.data.UpaScanPhase.ScanPermissions ->
            stringResource(R.string.upa_plugin_scan_permissions)
        kira.ditto.data.UpaScanPhase.ScanUi ->
            stringResource(R.string.upa_plugin_scan_ui)
        kira.ditto.data.UpaScanPhase.ScanMcp ->
            stringResource(R.string.upa_plugin_scan_mcp)
        kira.ditto.data.UpaScanPhase.ScanScripts ->
            stringResource(R.string.upa_plugin_scan_scripts)
    }
}

private const val ParseActionDurationMillis = 900

// 按钮进度填充动画的缓动曲线。设计意图：先快后慢再快——起步迅速给出即时反馈，
// 中段放缓暗示"仍在处理"，尾段冲刺收尾，避免线性填充的机械感。
private val UpaActionProgressEasing = Easing { fraction ->
    when {
        fraction < 0.35f -> 0.55f * (fraction / 0.35f)
        fraction < 0.75f -> 0.55f + 0.15f * ((fraction - 0.35f) / 0.40f)
        else -> 0.70f + 0.30f * ((fraction - 0.75f) / 0.25f)
    }
}

// 解析没有网络，仍用短动画给出按压反馈。扫描进度由 GitHub 下载/列目录/风险检查回调驱动。
private suspend fun animateUpaActionProgress(durationMillis: Int, onProgress: (Float) -> Unit) {
    val animatable = Animatable(0f)
    animatable.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = durationMillis, easing = UpaActionProgressEasing),
    ) {
        onProgress(value)
    }
}

@Composable
private fun UpaInstallActionButton(
    label: String,
    running: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val displayedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "upa_install_progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (running) AetherSurfaceHigh else AetherPrimary)
            .clickable(enabled = enabled && !running, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(displayedProgress)
                    .background(AetherPrimary),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (running) AetherOnSurface else AetherOnPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) +
                        slideInVertically(animationSpec = tween(220)) { it / 3 }) togetherWith
                        (fadeOut(animationSpec = tween(120)) +
                            slideOutVertically(animationSpec = tween(180)) { -it / 3 })
                },
                label = "upa_install_action_label",
            ) { targetLabel ->
                Text(
                    text = targetLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (running) AetherOnSurface else AetherOnPrimary,
                )
            }
        }
    }
}

@Composable
private fun upaRiskLevelLabel(level: UpaRiskLevel): String = when (level) {
    UpaRiskLevel.Block -> stringResource(R.string.upa_plugin_risk_block)
    UpaRiskLevel.Warn -> stringResource(R.string.upa_plugin_risk_warn)
    UpaRiskLevel.Info -> stringResource(R.string.upa_plugin_risk_info)
}

@Composable
private fun upaRiskLevelColor(level: UpaRiskLevel): Color = when (level) {
    UpaRiskLevel.Block -> MaterialTheme.colorScheme.error
    UpaRiskLevel.Warn -> MaterialTheme.colorScheme.tertiary
    UpaRiskLevel.Info -> AetherOnSurfaceVariant
}

@Composable
private fun upaRiskFindingText(finding: UpaRiskFinding): String {
    val arg = finding.args.firstOrNull().orEmpty()
    return when (finding.code) {
        "secret.manifest" -> stringResource(R.string.upa_risk_secret_manifest)
        "secret.file" -> stringResource(R.string.upa_risk_secret_file, arg)
        "source.host" -> stringResource(R.string.upa_risk_source_host, arg)
        "source.insecure" -> stringResource(R.string.upa_risk_source_insecure)
        "protocol.no_ui" -> stringResource(R.string.upa_risk_no_ui)
        "perm.html_trusted" -> stringResource(R.string.upa_risk_html_trusted)
        "perm.network" -> stringResource(R.string.upa_risk_network)
        "perm.html_sandbox" -> stringResource(R.string.upa_risk_html_sandbox)
        "memory.everme_trajectory" -> stringResource(R.string.upa_risk_everme)
        "mcp.import" -> stringResource(R.string.upa_risk_mcp_import, arg)
        "agent.remote" -> stringResource(R.string.upa_risk_agent_remote, arg)
        "files.scripts" -> stringResource(R.string.upa_risk_scripts, arg)
        "tools.listed" -> stringResource(R.string.upa_risk_tools, arg)
        "protocol.released_at" -> stringResource(R.string.upa_risk_released_at)
        "protocol.released_at_invalid" -> stringResource(R.string.upa_risk_released_at_invalid)
        "protocol.released_at_future" -> stringResource(R.string.upa_risk_released_at_future)
        "perm.host_write" -> stringResource(R.string.upa_risk_host_write, arg)
        "perm.guest" -> stringResource(R.string.upa_risk_guest, arg)
        "perm.host_read" -> stringResource(R.string.upa_risk_host_read, arg)
        else -> finding.code
    }
}

@Composable
private fun UpaPermissionGrantCard(manifest: UpaManifest) {
    val labels = mapOf(
        "network" to stringResource(R.string.upa_perm_network),
        "storage.read" to stringResource(R.string.upa_perm_storage_read),
        "storage.write" to stringResource(R.string.upa_perm_storage_write),
        "clipboard" to stringResource(R.string.upa_perm_clipboard),
        "html.sandbox" to stringResource(R.string.upa_perm_html_sandbox),
        "host.apps.read" to stringResource(R.string.upa_perm_host_apps_read),
        "host.health.read" to stringResource(R.string.upa_perm_host_health_read),
        "host.location.read" to stringResource(R.string.upa_perm_host_location_read),
        "host.calendar.read" to stringResource(R.string.upa_perm_host_calendar_read),
        "host.storage.read" to stringResource(R.string.upa_perm_host_storage_read),
    )
    val none = stringResource(R.string.upa_plugin_perm_none)
    val guest = manifest.permissions.mapNotNull { wire ->
        classifyUpaPermission(wire)?.takeIf { it.scope == UpaPermissionScope.Guest }?.wire
    }
    val host = manifest.permissions.mapNotNull { wire ->
        classifyUpaPermission(wire)?.takeIf { it.scope == UpaPermissionScope.Host }?.wire
    }
    SettingsCardGroup {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (host.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.upa_plugin_perm_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = host.joinToString("\n") { labels[it] ?: it },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
            if (guest.isNotEmpty()) {
                if (host.isNotEmpty()) Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.upa_plugin_perm_guest),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = guest.joinToString("\n") { labels[it] ?: it },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
            if (host.isEmpty() && guest.isEmpty()) {
                Text(
                    text = none,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
        }
    }
}
