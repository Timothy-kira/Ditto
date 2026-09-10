package kira.ditto.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.personaDataStore by preferencesDataStore(name = "aether_personas")

class PersonaRepository(
    private val context: Context,
) {
    val store: Flow<PersonaStore> = context.personaDataStore.data.map { preferences ->
        parseStore(preferences[STORE_JSON].orEmpty())
    }

    suspend fun upsertPersona(persona: PersonaProfile) {
        mutate { current ->
            current.copy(
                personas = (current.personas.filterNot { it.id == persona.id } + persona)
                    .sortedBy { it.name.lowercase() },
            )
        }
    }

    suspend fun removePersona(personaId: String) {
        withContext(Dispatchers.IO) {
            File(context.filesDir, "personas/$personaId").deleteRecursively()
        }
        mutate { current ->
            current.copy(
                personas = current.personas.filterNot { it.id == personaId },
                crews = current.crews.map { crew ->
                    crew.copy(
                        memberPersonaIds = crew.memberPersonaIds.filterNot { it == personaId },
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                },
            )
        }
    }

    suspend fun importKnowledgeFile(
        personaId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
    ): PersonaKnowledgeFile = withContext(Dispatchers.IO) {
        val current = store.first()
        val persona = current.personas.firstOrNull { it.id == personaId }
            ?: error("Persona was not found.")
        val knowledgeId = UUID.randomUUID().toString()
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "knowledge" }
        val relativePath = "personas/$personaId/knowledge/${knowledgeId}_$safeName"
        val destination = File(context.filesDir, relativePath)
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Couldn't read the selected file.")
        val file = PersonaKnowledgeFile(
            id = knowledgeId,
            displayName = displayName.ifBlank { safeName },
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = destination.length(),
        )
        upsertPersona(
            persona.copy(
                knowledgeFiles = persona.knowledgeFiles + file,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        file
    }

    suspend fun removeKnowledgeFile(personaId: String, fileId: String) {
        val persona = store.first().personas.firstOrNull { it.id == personaId } ?: return
        val file = persona.knowledgeFiles.firstOrNull { it.id == fileId }
        if (file != null) {
            withContext(Dispatchers.IO) {
                File(context.filesDir, file.relativePath).delete()
            }
        }
        upsertPersona(
            persona.copy(
                knowledgeFiles = persona.knowledgeFiles.filterNot { it.id == fileId },
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun ensureNodeId(): String {
        val current = store.first()
        val existing = current.nodeId.trim()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        mutate { it.copy(nodeId = created) }
        return created
    }

    suspend fun upsertCrew(crew: DigiCrewRoom) {
        mutate { current ->
            val secret = crew.roomSecret.ifBlank { DigiCrewInvite.newSecret() }
            val saved = crew.copy(roomSecret = secret)
            current.copy(
                crews = (current.crews.filterNot { it.id == saved.id } + saved)
                    .sortedBy { it.name.lowercase() },
            )
        }
    }

    suspend fun removeCrew(crewId: String) {
        mutate { current ->
            current.copy(crews = current.crews.filterNot { it.id == crewId })
        }
    }

    fun queryDisplayName(uri: Uri, fallback: String = "file"): String {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)?.ifBlank { fallback } ?: fallback
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { fallback } ?: fallback
    }

    private suspend fun mutate(transform: (PersonaStore) -> PersonaStore) {
        context.personaDataStore.edit { preferences ->
            val current = parseStore(preferences[STORE_JSON].orEmpty())
            preferences[STORE_JSON] = serializeStore(transform(current))
        }
    }

    private companion object {
        val STORE_JSON = stringPreferencesKey("persona_store_json")
    }
}

private fun parseStore(raw: String): PersonaStore {
    if (raw.isBlank()) return PersonaStore()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return PersonaStore()
    return PersonaStore(
        nodeId = json.optString("node_id"),
        personas = json.optJSONArray("personas").toObjectList(::parsePersona),
        crews = json.optJSONArray("crews").toObjectList(::parseCrew),
    )
}

private fun serializeStore(store: PersonaStore): String = JSONObject()
    .put("node_id", store.nodeId)
    .put("personas", JSONArray(store.personas.map(::serializePersona)))
    .put("crews", JSONArray(store.crews.map(::serializeCrew)))
    .toString()

private fun parsePersona(json: JSONObject): PersonaProfile = PersonaProfile(
    id = json.optString("id"),
    name = json.optString("name"),
    instructions = json.optString("instructions"),
    memoryNotes = json.optString("memory_notes"),
    knowledgeFiles = json.optJSONArray("knowledge_files").toObjectList(::parseKnowledgeFile),
    skillIds = json.optJSONArray("skill_ids").toStringList(),
    mcpServerIds = json.optJSONArray("mcp_server_ids").toStringList(),
    avatarSeed = json.optString("avatar_seed").ifBlank { json.optString("id") },
    avatarSelections = json.optJSONObject("avatar_selections").toStringMap(),
    avatarColors = json.optJSONObject("avatar_colors").toStringMap(),
    avatarBackground = json.optString("avatar_background"),
    createdAtMillis = json.optLong("created_at_millis"),
    updatedAtMillis = json.optLong("updated_at_millis"),
    activeSessionId = json.optString("active_session_id"),
)

private fun serializePersona(persona: PersonaProfile): JSONObject = JSONObject()
    .put("id", persona.id)
    .put("name", persona.name)
    .put("instructions", persona.instructions)
    .put("memory_notes", persona.memoryNotes)
    .put("knowledge_files", JSONArray(persona.knowledgeFiles.map(::serializeKnowledgeFile)))
    .put("skill_ids", JSONArray(persona.skillIds))
    .put("mcp_server_ids", JSONArray(persona.mcpServerIds))
    .put("avatar_seed", persona.avatarSeed)
    .put("avatar_selections", persona.avatarSelections.toJsonObject())
    .put("avatar_colors", persona.avatarColors.toJsonObject())
    .put("avatar_background", persona.avatarBackground)
    .put("created_at_millis", persona.createdAtMillis)
    .put("updated_at_millis", persona.updatedAtMillis)
    .put("active_session_id", persona.activeSessionId)

private fun parseKnowledgeFile(json: JSONObject): PersonaKnowledgeFile = PersonaKnowledgeFile(
    id = json.optString("id"),
    displayName = json.optString("display_name"),
    relativePath = json.optString("relative_path"),
    mimeType = json.optString("mime_type"),
    sizeBytes = json.optLong("size_bytes"),
    sliceCount = json.optInt("slice_count"),
    importedAtMillis = json.optLong("imported_at_millis"),
)

private fun serializeKnowledgeFile(file: PersonaKnowledgeFile): JSONObject = JSONObject()
    .put("id", file.id)
    .put("display_name", file.displayName)
    .put("relative_path", file.relativePath)
    .put("mime_type", file.mimeType)
    .put("size_bytes", file.sizeBytes)
    .put("slice_count", file.sliceCount)
    .put("imported_at_millis", file.importedAtMillis)

private fun parseCrew(json: JSONObject): DigiCrewRoom = DigiCrewRoom(
    id = json.optString("id"),
    name = json.optString("name"),
    description = json.optString("description"),
    memberPersonaIds = json.optJSONArray("member_persona_ids").toStringList(),
    roomSecret = json.optString("room_secret"),
    activeSessionId = json.optString("active_session_id"),
    remotePersonas = json.optJSONArray("remote_personas").toObjectList(::parseRemotePersona),
    createdAtMillis = json.optLong("created_at_millis"),
    updatedAtMillis = json.optLong("updated_at_millis"),
)

private fun serializeCrew(crew: DigiCrewRoom): JSONObject = JSONObject()
    .put("id", crew.id)
    .put("name", crew.name)
    .put("description", crew.description)
    .put("member_persona_ids", JSONArray(crew.memberPersonaIds))
    .put("room_secret", crew.roomSecret)
    .put("active_session_id", crew.activeSessionId)
    .put("remote_personas", JSONArray(crew.remotePersonas.map(::serializeRemotePersona)))
    .put("created_at_millis", crew.createdAtMillis)
    .put("updated_at_millis", crew.updatedAtMillis)

private fun parseRemotePersona(json: JSONObject): DigiCrewRemotePersona = DigiCrewRemotePersona(
    peerId = json.optString("peer_id"),
    personaId = json.optString("persona_id"),
    personaName = json.optString("persona_name"),
    ownerLabel = json.optString("owner_label"),
    lastSeenMillis = json.optLong("last_seen_millis"),
)

private fun serializeRemotePersona(persona: DigiCrewRemotePersona): JSONObject = JSONObject()
    .put("peer_id", persona.peerId)
    .put("persona_id", persona.personaId)
    .put("persona_name", persona.personaName)
    .put("owner_label", persona.ownerLabel)
    .put("last_seen_millis", persona.lastSeenMillis)

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key -> optString(key) }
        .filterValues { it.isNotBlank() }
}

private fun Map<String, String>.toJsonObject(): JSONObject = JSONObject().also { json ->
    forEach { (key, value) -> json.put(key, value) }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun <T> JSONArray?.toObjectList(parse: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(parse(item))
        }
    }
}
