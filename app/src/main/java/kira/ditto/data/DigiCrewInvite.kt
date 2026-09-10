package kira.ditto.data

import java.security.SecureRandom

object DigiCrewInvite {
    private const val Prefix = "DC1"

    fun newSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun encode(room: DigiCrewRoom): String {
        val secret = room.roomSecret.ifBlank { newSecret() }
        return "$Prefix/${room.id}/$secret"
    }

    fun parse(raw: String): Pair<String, String>? {
        val compact = raw.trim()
            .replace("\\s".toRegex(), "")
            .substringAfter("://", missingDelimiterValue = raw.trim())
        val match = Regex(
            """^(?:$Prefix[:/])([0-9a-fA-F-]{8,36})[:/]([0-9a-fA-F]{16,64})$""",
        ).find(compact) ?: Regex(
            """^$Prefix/([^/\s]+)/([^/\s]+)$""",
        ).find(compact) ?: return null
        val roomId = match.groupValues[1].trim()
        val secret = match.groupValues[2].trim().lowercase()
        if (roomId.isBlank() || secret.length < 16) return null
        return roomId to secret
    }
}
