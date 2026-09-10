package kira.ditto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.McpSecretKind
import kira.ditto.data.McpSecretSlot
import kira.ditto.data.McpServerConfig
import kira.ditto.data.McpTransportConfig
import kira.ditto.ui.theme.AetherOnSurfaceVariant

@Composable
internal fun mcpSecretSlotLabel(
    kind: McpSecretKind,
    injectKey: String,
    serverHint: String = "",
): String {
    val amapHint = serverHint.contains("amap", ignoreCase = true) ||
        serverHint.contains("高德")
    if (kind == McpSecretKind.UrlQuery && injectKey.equals("key", ignoreCase = true) && amapHint) {
        return stringResource(R.string.mcp_secret_slot_amap_key)
    }
    return when (kind) {
        McpSecretKind.UrlQuery -> stringResource(R.string.mcp_secret_slot_url_key, injectKey)
        McpSecretKind.Env -> stringResource(R.string.mcp_secret_slot_env, injectKey)
        McpSecretKind.Header -> stringResource(R.string.mcp_secret_slot_header, injectKey)
    }
}

internal fun mcpSecretHostHint(server: McpServerConfig): String = when (val transport = server.transport) {
    is McpTransportConfig.StreamableHttp -> transport.url
    is McpTransportConfig.StdIo -> listOf(server.displayName, transport.command).joinToString(" ")
    is McpTransportConfig.UpaManifest -> transport.pluginId
}

@Composable
internal fun McpSecretSlotsEditor(
    servers: List<McpServerConfig>,
    slots: List<McpSecretSlot>,
    values: Map<String, TextFieldValue>,
    configuredIds: Set<String>,
    onValueChange: (String, TextFieldValue) -> Unit,
    onSaveSlot: ((McpSecretSlot) -> Unit)? = null,
    onDeleteSlot: ((McpSecretSlot) -> Unit)? = null,
    title: String,
    body: String,
) {
    if (slots.isEmpty()) return
    val names = servers.associate { it.id to it.displayName.ifBlank { it.id } }
    val hints = servers.associate { it.id to mcpSecretHostHint(it) }
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = AetherOnSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = AetherOnSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    slots.forEach { slot ->
        SettingsCardGroup {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                ChatGptTextField(
                    label = mcpSecretSlotLabel(
                        kind = slot.kind,
                        injectKey = slot.injectKey,
                        serverHint = hints[slot.serverId].orEmpty(),
                    ),
                    value = values[slot.id] ?: TextFieldValue(""),
                    isSecret = true,
                    placeholder = names[slot.serverId].orEmpty().ifBlank { slot.injectKey },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { onValueChange(slot.id, it) },
                )
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = if (slot.id in configuredIds || slot.hasInlineValue) {
                            stringResource(R.string.upa_plugin_mcp_secret_configured)
                        } else {
                            stringResource(R.string.upa_plugin_mcp_secret_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                }
                if (onSaveSlot != null) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SettingsActionButton(
                            label = stringResource(R.string.upa_plugin_mcp_secret_save),
                            onClick = { onSaveSlot(slot) },
                        )
                        Spacer(Modifier.padding(6.dp))
                        if (onDeleteSlot != null && slot.id in configuredIds) {
                            SettingsSubtleActionButton(
                                label = stringResource(R.string.upa_plugin_mcp_secret_delete),
                                onClick = { onDeleteSlot(slot) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
