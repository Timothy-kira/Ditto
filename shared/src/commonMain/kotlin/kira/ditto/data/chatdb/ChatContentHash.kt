package kira.ditto.data.chatdb

/**
 * Cheap content fingerprint used to decide whether a stored row still matches what the
 * app holds in memory. It only has to be stable across runs and collision-resistant
 * enough that two different messages of the same length never share a value in practice,
 * so a 64-bit FNV-1a plus the length is plenty and costs a single pass.
 */
fun chatContentHash(content: String): String {
    if (content.isEmpty()) return "0:0"
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (index in content.indices) {
        val code = content[index].code
        hash = (hash xor (code and 0xFF).toLong()) * 0x100000001b3L
        hash = (hash xor ((code shr 8) and 0xFF).toLong()) * 0x100000001b3L
    }
    return "${content.length}:${hash.toULong().toString(16)}"
}
