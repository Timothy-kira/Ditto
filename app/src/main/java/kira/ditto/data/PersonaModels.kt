package kira.ditto.data

data class PersonaKnowledgeFile(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val sliceCount: Int = 0,
    val importedAtMillis: Long = System.currentTimeMillis(),
)

enum class PersonaKnowledgeImportPhase {
    Parsing,
    Embedding,
}

data class PersonaAvatarSpec(
    val seed: String,
    val selections: Map<String, String> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val background: String = "",
)

data class PersonaProfile(
    val id: String,
    val name: String,
    val instructions: String = "",
    val memoryNotes: String = "",
    val knowledgeFiles: List<PersonaKnowledgeFile> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val mcpServerIds: List<String> = emptyList(),
    val avatarSeed: String = id,
    val avatarSelections: Map<String, String> = emptyMap(),
    val avatarColors: Map<String, String> = emptyMap(),
    val avatarBackground: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val activeSessionId: String = "",
) {
    fun avatarSpec(): PersonaAvatarSpec = PersonaAvatarSpec(
        seed = avatarSeed.ifBlank { id },
        selections = avatarSelections,
        colors = avatarColors,
        background = avatarBackground,
    )
}

data class DigiCrewRemotePersona(
    val peerId: String,
    val personaId: String,
    val personaName: String,
    val ownerLabel: String,
    val lastSeenMillis: Long = 0L,
)

data class DigiCrewRoom(
    val id: String,
    val name: String,
    val description: String = "",
    val memberPersonaIds: List<String> = emptyList(),
    val roomSecret: String = "",
    val activeSessionId: String = "",
    val remotePersonas: List<DigiCrewRemotePersona> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
)

data class PersonaStore(
    val nodeId: String = "",
    val personas: List<PersonaProfile> = emptyList(),
    val crews: List<DigiCrewRoom> = emptyList(),
)

data class DigiCrewPeerPresence(
    val peerId: String,
    val ownerLabel: String,
    val roomId: String,
    val personas: List<DigiCrewOfferedPersona> = emptyList(),
    val via: String = "",
    val lastSeenMillis: Long = System.currentTimeMillis(),
)

data class DigiCrewOfferedPersona(
    val personaId: String,
    val personaName: String,
)

sealed interface DigiCrewMeshEvent {
    val roomId: String
    val peerId: String
    val eventId: String

    data class Presence(
        override val roomId: String,
        override val peerId: String,
        override val eventId: String,
        val ownerLabel: String,
        val personas: List<DigiCrewOfferedPersona>,
        val via: String,
    ) : DigiCrewMeshEvent

    data class Chat(
        override val roomId: String,
        override val peerId: String,
        override val eventId: String,
        val kind: String,
        val speakerName: String,
        val text: String,
    ) : DigiCrewMeshEvent
}
