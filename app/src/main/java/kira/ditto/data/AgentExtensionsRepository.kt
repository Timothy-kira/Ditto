package kira.ditto.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.agentExtensionsDataStore by preferencesDataStore(name = "aether_agent_extensions")

class AgentExtensionsRepository(
    private val context: Context,
) {
    val extensionState: Flow<AgentExtensionsState> = context.agentExtensionsDataStore.data.map { preferences ->
        AgentExtensionsState(
            installedSkills = parseInstalledSkills(preferences[INSTALLED_SKILLS_JSON].orEmpty()),
            mcpServers = parseMcpServerConfigs(preferences[MCP_SERVERS_JSON].orEmpty())
                .filterNot { kira.ditto.upa.isUpaMcpServerId(it.id) },
        )
    }

    suspend fun updateInstalledSkills(skills: List<InstalledSkill>) {
        context.agentExtensionsDataStore.edit {
            it[INSTALLED_SKILLS_JSON] = serializeInstalledSkills(skills)
        }
    }

    suspend fun upsertInstalledSkill(skill: InstalledSkill) {
        val updatedSkills = extensionState.firstValue()
            .installedSkills
            .filterNot { it.id == skill.id } + skill
        updateInstalledSkills(updatedSkills.sortedBy { it.name.lowercase() })
    }

    suspend fun removeInstalledSkill(skillId: String) {
        val updatedSkills = extensionState.firstValue()
            .installedSkills
            .filterNot { it.id == skillId }
        updateInstalledSkills(updatedSkills)
    }

    suspend fun setSkillEnabled(
        skillId: String,
        enabled: Boolean,
    ) {
        mutateSkills { skill ->
            if (skill.id == skillId) {
                skill.copy(
                    isEnabled = enabled,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            } else {
                skill
            }
        }
    }

    suspend fun updateMcpServers(servers: List<McpServerConfig>) {
        context.agentExtensionsDataStore.edit {
            it[MCP_SERVERS_JSON] = serializeMcpServerConfigs(
                servers.filterNot { server -> kira.ditto.upa.isUpaMcpServerId(server.id) },
            )
        }
    }

    suspend fun upsertMcpServer(server: McpServerConfig) {
        val updatedServers = extensionState.firstValue()
            .mcpServers
            .filterNot { it.id == server.id } + server
        updateMcpServers(updatedServers.sortedBy { it.displayName.lowercase() })
    }

    suspend fun removeMcpServer(serverId: String) {
        val updatedServers = extensionState.firstValue()
            .mcpServers
            .filterNot { it.id == serverId }
        updateMcpServers(updatedServers)
    }

    suspend fun setMcpServerEnabled(
        serverId: String,
        enabled: Boolean,
    ) {
        mutateServers { server ->
            if (server.id == serverId) {
                server.copy(
                    isEnabled = enabled,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            } else {
                server
            }
        }
    }

    private suspend fun mutateSkills(transform: (InstalledSkill) -> InstalledSkill) {
        updateInstalledSkills(
            extensionState.firstValue().installedSkills.map(transform)
        )
    }

    private suspend fun mutateServers(transform: (McpServerConfig) -> McpServerConfig) {
        updateMcpServers(
            extensionState.firstValue().mcpServers.map(transform)
        )
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private companion object {
        val INSTALLED_SKILLS_JSON = stringPreferencesKey("installed_skills_json")
        val MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
    }
}
