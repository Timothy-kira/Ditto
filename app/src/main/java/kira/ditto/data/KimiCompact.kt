package kira.ditto.data

internal const val AetherCompactDoneSentinel = "AETHER_COMPACT_DONE"
internal const val AetherCompactBusySentinel = "AETHER_COMPACT_BUSY"
internal const val KimiCompactStartedAck =
    "Context compaction started — it runs in the background and the compacted context applies once it finishes."
internal const val KimiCompactBusyAck = "A context compaction is already running."

internal enum class KimiCompactReply {
    Done,
    Busy,
    Failed,
}

internal fun classifyKimiCompactReply(text: String): KimiCompactReply {
    val trimmed = text.trim()
    if (trimmed.equals(AetherCompactBusySentinel, ignoreCase = true) ||
        trimmed.equals(KimiCompactBusyAck, ignoreCase = true) ||
        trimmed.contains("already running", ignoreCase = true)
    ) {
        return KimiCompactReply.Busy
    }
    if (trimmed.contains("Cannot compact", ignoreCase = true) ||
        trimmed.contains("COMPACTION_UNABLE", ignoreCase = true) ||
        trimmed.contains("compact is disabled", ignoreCase = true)
    ) {
        return KimiCompactReply.Failed
    }
    return KimiCompactReply.Done
}

internal fun looksLikeKimiCompactAck(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.equals(AetherCompactDoneSentinel, ignoreCase = true) ||
        trimmed.equals(AetherCompactBusySentinel, ignoreCase = true) ||
        trimmed.equals(KimiCompactStartedAck) ||
        trimmed.equals(KimiCompactBusyAck) ||
        trimmed.startsWith("Context compaction started")
}
