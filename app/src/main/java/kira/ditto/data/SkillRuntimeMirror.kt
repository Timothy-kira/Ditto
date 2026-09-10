package kira.ditto.data

import kira.ditto.runtime.RuntimeRouter
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val RuntimeSkillsRoot = "/root/.aether/skills"

class SkillRuntimeMirror(
    private val runtimeRouter: RuntimeRouter,
) {
    private val mutex = Mutex()
    private val runtimeSignatures = mutableMapOf<LocalRuntimeId, String>()

    suspend fun sync(skills: List<InstalledSkill>): List<String> = mutex.withLock {
        val enabled = skills.filter(InstalledSkill::isEnabled).sortedBy(InstalledSkill::id)
        val signature = enabled.joinToString("|") { "${it.id}:${it.checksumSha256}:${it.updatedAtMillis}" }
        if (runtimeSignatures[LocalRuntimeId.Alpine] == signature) {
            return@withLock enabled.map(::runtimeSkillPath)
        }
        val directories = enabled.associate { skill ->
            safeSkillDirectoryName(skill.id) to File(skill.skillRootPath)
        }
        if (directories.size != enabled.size) return@withLock emptyList()

        val alpine = runtimeRouter.runtimeById(LocalRuntimeId.Alpine)
        val mirrored = runCatching {
            alpine.replaceHostDirectories(
                guestRootPath = RuntimeSkillsRoot,
                signature = signature,
                directories = directories,
            )
        }.getOrDefault(false)
        if (!mirrored) return@withLock emptyList()

        runtimeSignatures[LocalRuntimeId.Alpine] = signature
        enabled.map(::runtimeSkillPath)
    }
}

fun runtimeSkillPath(skill: InstalledSkill): String = "$RuntimeSkillsRoot/${safeSkillDirectoryName(skill.id)}"

private fun safeSkillDirectoryName(id: String): String {
    val normalized = id.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
    if (normalized.isNotBlank()) return normalized
    return MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
