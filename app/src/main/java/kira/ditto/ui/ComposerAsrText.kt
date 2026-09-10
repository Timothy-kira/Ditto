package kira.ditto.ui

import kira.ditto.data.AsrChunkAssembler
import kira.ditto.data.composerAsrCollapseRepeats

/** Append a live ASR partial after the draft that existed when listening started. */
internal fun joinComposerAsrText(prefix: String, partial: String): String {
    val spoken = composerAsrCollapseRepeats(partial.trim())
    if (spoken.isEmpty()) return prefix
    if (prefix.isEmpty()) return spoken
    return AsrChunkAssembler.mergeOverlappingTranscripts(prefix.trimEnd(), spoken)
}
