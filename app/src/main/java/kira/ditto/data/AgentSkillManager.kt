package kira.ditto.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

private const val SkillStorageDirectoryName = "agent-skills"
private const val SkillTempDirectoryName = "agent-skills-tmp"
private const val SkillFileName = "SKILL.md"
private const val MaxSkillArchiveBytes = 32L * 1024L * 1024L
private const val MaxSkillExtractedBytes = 128L * 1024L * 1024L
private const val MaxSkillEntryBytes = 16L * 1024L * 1024L
private const val MaxSkillZipEntries = 4096
private const val DiscoveredSkillPreferencesName = "aether_discovered_skills"
private const val IgnoredDiscoveredSkillSourcesKey = "ignored_sources"

data class PiPackageSkillSource(
    val packageSource: String,
    val packageName: String,
    val guestPath: String,
    val hostPath: File,
)

data class PiDiscoveredSkillSource(
    val guestFilePath: String,
    val guestBaseDir: String,
    val hostFile: File,
    val hostRoot: File,
)

class AgentSkillManager(
    private val context: Context,
    private val extensionsRepository: AgentExtensionsRepository,
    private val httpClient: OkHttpClient = AetherHttp.derive {
        callTimeout(90, TimeUnit.SECONDS)
    },
) {
    suspend fun installSkillFromDirectory(
        treeUri: Uri,
        label: String = treeUri.toString(),
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDirectory = createTempDirectory()
            try {
                val root = copyDocumentTree(treeUri, workingDirectory)
                val skillRoot = locateSkillRoot(root = root)
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = SkillInstallKind.DocumentTree,
                        label = label,
                        uri = treeUri.toString(),
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun installSkillFromZipUri(
        zipUri: Uri,
        label: String = zipUri.toString(),
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDirectory = createTempDirectory()
            try {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    unzipIntoDirectory(input, workingDirectory)
                } ?: error("Couldn't open the selected zip file.")
                val skillRoot = locateSkillRoot(root = workingDirectory)
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = SkillInstallKind.ZipUri,
                        label = label,
                        uri = zipUri.toString(),
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun installSkillFromRemote(
        rawUrl: String,
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val plan = resolveRemoteDownloadPlan(rawUrl)
            val workingDirectory = createTempDirectory()
            try {
                downloadZipIntoDirectory(plan.downloadUrl, workingDirectory)
                val skillRoot = locateSkillRoot(
                    root = workingDirectory,
                    requestedSubpath = plan.subpath,
                )
                installParsedSkill(
                    sourceRoot = skillRoot,
                    source = SkillInstallSource(
                        kind = plan.kind,
                        label = rawUrl,
                        uri = rawUrl,
                        ref = plan.ref,
                        subpath = plan.subpath,
                    ),
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    suspend fun uninstallSkill(skillId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val skill = extensionsRepository.extensionState.firstValue()
                .installedSkills
                .firstOrNull { it.id == skillId }
                ?: return@runCatching
            if (skill.source.kind == SkillInstallKind.PiDiscovered) {
                ignoredDiscoveredSkillSources().edit()
                    .putStringSet(
                        IgnoredDiscoveredSkillSourcesKey,
                        ignoredDiscoveredSkillSources().getStringSet(
                            IgnoredDiscoveredSkillSourcesKey,
                            emptySet(),
                        ).orEmpty() + skill.source.uri,
                    )
                    .apply()
            }
            validatedInstalledSkillRoot(skill)?.deleteRecursively()
            extensionsRepository.removeInstalledSkill(skillId)
        }
    }

    suspend fun syncPiDiscoveredSkills(
        discoveredSkills: List<PiDiscoveredSkillSource>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ignored = ignoredDiscoveredSkillSources()
                .getStringSet(IgnoredDiscoveredSkillSourcesKey, emptySet())
                .orEmpty()
            val desired = discoveredSkills
                .filter { it.guestFilePath !in ignored }
                .distinctBy(PiDiscoveredSkillSource::guestFilePath)
            val desiredPaths = desired.map(PiDiscoveredSkillSource::guestFilePath).toSet()
            val initialSkills = extensionsRepository.extensionState.firstValue().installedSkills
            desired.forEach { discovered ->
                runCatching {
                    val id = piDiscoveredSkillId(discovered.guestFilePath)
                    val existing = initialSkills.firstOrNull { it.id == id }
                    val sourceRoot = if (
                        discovered.hostFile.name.equals(SkillFileName, ignoreCase = true) &&
                        discovered.hostRoot.isDirectory &&
                        File(discovered.hostRoot, SkillFileName).isFile
                    ) {
                        discovered.hostRoot
                    } else {
                        createTempDirectory().also { staging ->
                            discovered.hostFile.copyTo(File(staging, SkillFileName), overwrite = true)
                        }
                    }
                    try {
                        val checksum = sha256OfDirectory(sourceRoot)
                        if (
                            existing?.checksumSha256 == checksum &&
                            validatedInstalledSkillRoot(existing) != null
                        ) return@runCatching
                        installParsedSkill(
                            sourceRoot = sourceRoot,
                            source = SkillInstallSource(
                                kind = SkillInstallKind.PiDiscovered,
                                label = discovered.guestFilePath,
                                uri = discovered.guestFilePath,
                                subpath = discovered.guestBaseDir,
                            ),
                            skillIdOverride = id,
                            installedAtMillis = existing?.installedAtMillis,
                            isEnabled = existing?.isEnabled ?: true,
                        )
                    } finally {
                        if (sourceRoot != discovered.hostRoot) sourceRoot.deleteRecursively()
                    }
                }
            }
            val refreshed = extensionsRepository.extensionState.firstValue().installedSkills
            val stale = refreshed.filter {
                it.source.kind == SkillInstallKind.PiDiscovered && it.source.uri !in desiredPaths
            }
            stale.forEach { validatedInstalledSkillRoot(it)?.deleteRecursively() }
            if (stale.isNotEmpty()) {
                extensionsRepository.updateInstalledSkills(
                    refreshed.filterNot { skill -> stale.any { it.id == skill.id } },
                )
            }
        }
    }

    suspend fun syncPiPackageSkills(
        packageSkills: List<PiPackageSkillSource>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val desiredKeys = packageSkills
                .map { packageSkillKey(it.packageSource, it.guestPath) }
                .toSet()
            val initialSkills = extensionsRepository.extensionState.firstValue().installedSkills
            packageSkills.distinctBy { packageSkillKey(it.packageSource, it.guestPath) }
                .forEach { packageSkill ->
                    runCatching {
                        val sourceRoot = when {
                            packageSkill.hostPath.isFile -> packageSkill.hostPath.parentFile
                            packageSkill.hostPath.isDirectory -> locateSkillRoot(packageSkill.hostPath)
                            else -> null
                        } ?: return@runCatching
                        val key = packageSkillKey(packageSkill.packageSource, packageSkill.guestPath)
                        val skillId = "pi-package-${sha256OfText(key).take(20)}"
                        val existing = initialSkills.firstOrNull { it.id == skillId }
                        val checksum = sha256OfDirectory(sourceRoot)
                        if (
                            existing?.checksumSha256 == checksum &&
                            validatedInstalledSkillRoot(existing) != null
                        ) {
                            return@runCatching
                        }
                        installParsedSkill(
                            sourceRoot = sourceRoot,
                            source = SkillInstallSource(
                                kind = SkillInstallKind.PiPackage,
                                label = packageSkill.packageName,
                                uri = packageSkill.packageSource,
                                subpath = packageSkill.guestPath,
                            ),
                            skillIdOverride = skillId,
                            installedAtMillis = existing?.installedAtMillis,
                            isEnabled = existing?.isEnabled ?: true,
                        )
                    }
                }

            val refreshedSkills = extensionsRepository.extensionState.firstValue().installedSkills
            val stalePackageSkills = refreshedSkills.filter { skill ->
                skill.source.kind == SkillInstallKind.PiPackage &&
                    packageSkillKey(skill.source.uri, skill.source.subpath) !in desiredKeys
            }
            if (stalePackageSkills.isNotEmpty()) {
                stalePackageSkills.forEach { skill ->
                    validatedInstalledSkillRoot(skill)?.deleteRecursively()
                }
                extensionsRepository.updateInstalledSkills(
                    refreshedSkills.filterNot { candidate ->
                        stalePackageSkills.any { it.id == candidate.id }
                    }
                )
            }
        }
    }

    suspend fun buildActiveSkillContext(
        skill: InstalledSkill,
    ): Result<ActiveSkillContext> = withContext(Dispatchers.IO) {
        runCatching {
            val root = validatedInstalledSkillRoot(skill)
                ?: error("Installed skill path is invalid.")
            val skillFile = validatedSkillMarkdownFile(skill, root)
                ?: error("Installed skill metadata does not point at its bundled SKILL.md.")
            val parsed = parseSkillDocument(skillFile)
            ActiveSkillContext(
                skillId = skill.id,
                name = parsed.name,
                description = parsed.description,
                compatibility = parsed.compatibility,
                allowedTools = parsed.allowedTools,
                skillRootPath = root.absolutePath,
                bodyMarkdown = parsed.bodyMarkdown,
                resourceEntries = skill.resourceEntries,
            )
        }
    }

    fun installedSkillsDirectory(): File = File(context.filesDir, SkillStorageDirectoryName).apply {
        mkdirs()
    }

    fun exportSkillBundles(skills: List<InstalledSkill>): JSONArray =
        JSONArray().apply {
            skills.sortedBy { it.name.lowercase(Locale.US) }
                .mapNotNull { skill -> runCatching { exportSkillBundle(skill) }.getOrNull() }
                .forEach(::put)
        }

    suspend fun importSkillBundles(bundles: JSONArray?): List<InstalledSkill> = withContext(Dispatchers.IO) {
        if (bundles == null) return@withContext emptyList()
        buildList {
            for (index in 0 until bundles.length()) {
                val bundle = bundles.optJSONObject(index) ?: continue
                val installedSkill = runCatching { importSkillBundle(bundle) }.getOrNull() ?: continue
                add(installedSkill)
            }
        }.sortedBy { it.name.lowercase(Locale.US) }
    }

    suspend fun upsertAgentModeSkill(
        skillId: String,
        name: String,
        description: String,
        bodyMarkdown: String,
    ): Result<InstalledSkill> = withContext(Dispatchers.IO) {
        runCatching {
            val workingDirectory = createTempDirectory()
            try {
                File(workingDirectory, SkillFileName).writeText(
                    buildString {
                        appendLine("---")
                        append("name: ").appendLine(yamlDoubleQuoted(name))
                        append("description: ").appendLine(yamlDoubleQuoted(description))
                        appendLine("compatibility: agent-mode")
                        appendLine("---")
                        appendLine()
                        append(bodyMarkdown.trim())
                        appendLine()
                    },
                )
                installParsedSkill(
                    sourceRoot = workingDirectory,
                    source = SkillInstallSource(
                        kind = SkillInstallKind.AgentMode,
                        label = name,
                        uri = "agent-mode://$skillId",
                    ),
                    skillIdOverride = skillId,
                )
            } finally {
                workingDirectory.deleteRecursively()
            }
        }
    }

    private fun yamlDoubleQuoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim() + "\""

    private suspend fun installParsedSkill(
        sourceRoot: File,
        source: SkillInstallSource,
        skillIdOverride: String? = null,
        installedAtMillis: Long? = null,
        isEnabled: Boolean = true,
    ): InstalledSkill {
        val skillFile = File(sourceRoot, SkillFileName)
        require(skillFile.isFile) { "The selected directory does not contain SKILL.md." }
        val parsed = parseSkillDocument(skillFile)
        val skillId = skillIdOverride ?: buildSkillId(parsed.name)
        val previousSkill = extensionsRepository.extensionState.firstValue()
            .installedSkills
            .firstOrNull { it.id == skillId }
        val installRoot = createInstallRoot(skillId)
        val stagingRoot = File(context.filesDir, SkillTempDirectoryName)
            .apply { mkdirs() }
            .resolve("$skillId-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val checksum = try {
            sourceRoot.copyRecursively(stagingRoot, overwrite = true)
            sha256OfDirectory(stagingRoot)
        } catch (throwable: Throwable) {
            stagingRoot.deleteRecursively()
            throw throwable
        }
        moveStagingRootIntoPlace(stagingRoot, installRoot)
        val installedSkill = InstalledSkill(
            id = skillId,
            name = parsed.name,
            description = parsed.description,
            actionLabel = generateQuickActionLabel(parsed.name, parsed.description),
            license = parsed.license,
            compatibility = parsed.compatibility,
            metadataJson = parsed.metadataJson,
            allowedTools = parsed.allowedTools,
            skillRootPath = installRoot.absolutePath,
            skillMdPath = File(installRoot, SkillFileName).absolutePath,
            source = source,
            checksumSha256 = checksum,
            isEnabled = isEnabled,
            installedAtMillis = installedAtMillis ?: System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            diagnostics = parsed.diagnostics,
            resourceEntries = listSkillResources(installRoot),
        )
        extensionsRepository.upsertInstalledSkill(installedSkill)
        cleanupReplacedSkill(previousSkill, installRoot)
        return installedSkill
    }

    private fun createInstallRoot(skillId: String): File {
        val storageRoot = installedSkillsDirectory()
        while (true) {
            val candidate = storageRoot.resolve("$skillId-${UUID.randomUUID()}")
            if (!candidate.exists()) return candidate
        }
    }

    private fun moveStagingRootIntoPlace(
        stagingRoot: File,
        installRoot: File,
    ) {
        installRoot.parentFile?.mkdirs()
        try {
            if (!stagingRoot.renameTo(installRoot)) {
                stagingRoot.copyRecursively(installRoot, overwrite = false)
                stagingRoot.deleteRecursively()
            }
        } catch (throwable: Throwable) {
            installRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            throw throwable
        }
    }

    private fun cleanupReplacedSkill(
        previousSkill: InstalledSkill?,
        installRoot: File,
    ) {
        val previousRoot = previousSkill?.let(::validatedInstalledSkillRoot) ?: return
        val newRoot = runCatching { installRoot.canonicalFile }.getOrNull() ?: installRoot.absoluteFile
        if (previousRoot != newRoot) {
            previousRoot.deleteRecursively()
        }
    }

    private fun locateSkillRoot(
        root: File,
        requestedSubpath: String = "",
    ): File {
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.name.equals(SkillFileName, ignoreCase = true) }
            .mapNotNull { it.parentFile }
            .toList()
        if (candidates.isEmpty()) {
            error("No SKILL.md file was found in the selected source.")
        }
        if (requestedSubpath.isBlank()) {
            return candidates.sortedBy { it.absolutePath.length }.first()
        }

        val normalizedSubpath = requestedSubpath.trim('/').replace('\\', '/')
        return candidates.firstOrNull { candidate ->
            candidate.relativeTo(root).invariantSeparatorsPath.endsWith(normalizedSubpath)
        } ?: candidates.sortedBy { it.absolutePath.length }.first()
    }

    private fun parseSkillDocument(skillFile: File): ParsedSkillDocument {
        val text = skillFile.readText(Charsets.UTF_8)
        val (frontmatterText, bodyMarkdown) = splitFrontmatter(text)
        val diagnostics = mutableListOf<String>()
        val frontmatter = parseFrontmatter(frontmatterText, diagnostics)

        val name = readScalar(frontmatter, "name").ifBlank {
            extractFallbackScalar(frontmatterText, "name")
        }
        val description = readScalar(frontmatter, "description").ifBlank {
            extractFallbackScalar(frontmatterText, "description")
        }

        require(name.isNotBlank()) { "Skill frontmatter is missing 'name'." }
        require(description.isNotBlank()) { "Skill frontmatter is missing 'description'." }

        return ParsedSkillDocument(
            name = name,
            description = description,
            license = readScalar(frontmatter, "license"),
            compatibility = readFlexibleScalar(frontmatter, "compatibility"),
            metadataJson = readJsonValue(frontmatter["metadata"]),
            allowedTools = readStringList(frontmatter["allowed-tools"]),
            bodyMarkdown = bodyMarkdown.trim(),
            diagnostics = diagnostics,
        )
    }

    private fun splitFrontmatter(text: String): Pair<String, String> {
        if (!text.startsWith("---")) {
            return "" to text
        }
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return "" to text
        }
        val frontmatterLines = mutableListOf<String>()
        for (index in 1 until lines.size) {
            if (lines[index].trim() == "---") {
                val body = lines.drop(index + 1).joinToString("\n")
                return frontmatterLines.joinToString("\n") to body
            }
            frontmatterLines += lines[index]
        }
        return "" to text
    }

    private fun parseFrontmatter(
        rawFrontmatter: String,
        diagnostics: MutableList<String>,
    ): Map<String, Any?> {
        if (rawFrontmatter.isBlank()) return emptyMap()
        return runCatching {
            val loaderOptions = LoaderOptions().apply {
                isAllowDuplicateKeys = false
            }
            val yaml = Yaml(SafeConstructor(loaderOptions))
            @Suppress("UNCHECKED_CAST")
            yaml.load<Map<String, Any?>>(rawFrontmatter) ?: emptyMap()
        }.getOrElse { throwable ->
            diagnostics += throwable.message ?: "Couldn't parse SKILL.md frontmatter."
            emptyMap()
        }
    }

    private fun readScalar(
        frontmatter: Map<String, Any?>,
        key: String,
    ): String = frontmatter[key]?.toString()?.trim().orEmpty()

    private fun readFlexibleScalar(
        frontmatter: Map<String, Any?>,
        key: String,
    ): String {
        val value = frontmatter[key] ?: return ""
        return when (value) {
            is String -> value.trim()
            else -> readJsonValue(value)
        }
    }

    private fun readStringList(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim()).filter { it.isNotEmpty() }
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun extractFallbackScalar(
        frontmatterText: String,
        key: String,
    ): String {
        if (frontmatterText.isBlank()) return ""
        val prefix = "$key:"
        return frontmatterText.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.trim('"')
            ?.trim('\'')
            .orEmpty()
    }

    private fun readJsonValue(value: Any?): String = when (value) {
        null -> "{}"
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value).toString()
        is Iterable<*> -> JSONArray(value.toList()).toString()
        else -> JSONObject.wrap(value)?.toString() ?: value.toString()
    }

    private fun listSkillResources(skillRoot: File): List<SkillResourceEntry> =
        skillRoot.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(skillRoot).invariantSeparatorsPath
                SkillResourceEntry(
                    relativePath = relativePath,
                    kind = SkillResourceKind.fromRelativePath(relativePath),
                )
            }
            .sortedBy { it.relativePath }
            .toList()

    private fun createTempDirectory(): File =
        File(context.cacheDir, SkillTempDirectoryName)
            .apply { mkdirs() }
            .resolve(UUID.randomUUID().toString())
            .apply { mkdirs() }

    private fun copyDocumentTree(
        treeUri: Uri,
        destinationRoot: File,
    ): File {
        val rootDocument = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Couldn't open the selected folder.")
        val targetRoot = destinationRoot.resolve(rootDocument.name ?: "imported-skill")
        copyDocumentRecursively(rootDocument, targetRoot)
        return targetRoot
    }

    private fun copyDocumentRecursively(
        document: androidx.documentfile.provider.DocumentFile,
        destination: File,
    ) {
        if (document.isDirectory) {
            destination.mkdirs()
            document.listFiles().forEach { child ->
                copyDocumentRecursively(child, destination.resolve(child.name ?: child.uri.lastPathSegment ?: "file"))
            }
            return
        }

        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        } ?: error("Couldn't read ${document.uri}.")
    }

    private fun unzipIntoDirectory(
        input: InputStream,
        destinationRoot: File,
    ) {
        val canonicalRoot = destinationRoot.canonicalPath + File.separator
        var entryCount = 0
        var totalExtractedBytes = 0L
        ZipInputStream(LimitedInputStream(input, MaxSkillArchiveBytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MaxSkillZipEntries) {
                    "Skill archive contains too many files."
                }
                val output = destinationRoot.resolve(entry.name)
                val canonicalOutput = output.canonicalPath
                require(canonicalOutput.startsWith(canonicalRoot)) {
                    "Zip entry escaped the destination directory: ${entry.name}"
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    totalExtractedBytes += copyZipEntryWithLimits(
                        zipInput = zipInput,
                        outputFile = output,
                        entryName = entry.name,
                        extractedBeforeEntry = totalExtractedBytes,
                    )
                }
                zipInput.closeEntry()
            }
        }
    }

    private fun downloadZipIntoDirectory(
        url: String,
        destinationRoot: File,
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/zip, application/octet-stream")
            .addHeader("User-Agent", "Aether Android Agent")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Skill download failed with HTTP ${response.code}.")
            }
            val body = response.body ?: error("Skill download returned an empty body.")
            val contentLength = body.contentLength()
            if (contentLength > MaxSkillArchiveBytes) {
                error("Skill archive is too large.")
            }
            body.byteStream().use { input ->
                unzipIntoDirectory(input, destinationRoot)
            }
        }
    }

    private fun buildSkillId(name: String): String =
        name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "skill-${UUID.randomUUID()}" }

    private fun packageSkillKey(
        packageSource: String,
        guestPath: String,
    ): String = "${packageSource.trim()}|${guestPath.trim()}"

    private fun ignoredDiscoveredSkillSources() = context.getSharedPreferences(
        DiscoveredSkillPreferencesName,
        Context.MODE_PRIVATE,
    )

    private fun sha256OfText(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha256OfDirectory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray())
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun exportSkillBundle(skill: InstalledSkill): JSONObject? {
        val root = validatedInstalledSkillRoot(skill) ?: return null
        validatedSkillMarkdownFile(skill, root) ?: return null
        var entryCount = 0
        var totalBytes = 0L
        val files = JSONArray()
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                entryCount += 1
                require(entryCount <= MaxSkillZipEntries) {
                    "Skill contains too many bundled files."
                }
                val length = file.length()
                require(length <= MaxSkillEntryBytes) {
                    "Skill file is too large: ${file.name}"
                }
                totalBytes += length
                require(totalBytes <= MaxSkillExtractedBytes) {
                    "Skill bundle is too large."
                }
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                files.put(
                    JSONObject().apply {
                        put("path", relativePath)
                        put("dataBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                    }
                )
            }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("id", skill.id)
            put("name", skill.name)
            put("actionLabel", skill.actionLabel)
            put("isEnabled", skill.isEnabled)
            put("installedAtMillis", skill.installedAtMillis)
            put("source", skill.source.toJson())
            put("files", files)
        }
    }

    private suspend fun importSkillBundle(bundle: JSONObject): InstalledSkill {
        val workingDirectory = createTempDirectory()
        return try {
            val sourceRoot = workingDirectory.resolve("skill").apply { mkdirs() }
            val files = bundle.optJSONArray("files") ?: error("Skill bundle did not contain files.")
            var totalBytes = 0L
            for (index in 0 until files.length()) {
                require(index < MaxSkillZipEntries) {
                    "Skill bundle contains too many files."
                }
                val fileJson = files.optJSONObject(index) ?: continue
                val relativePath = normalizeBundleRelativePath(fileJson.optString("path"))
                val data = Base64.decode(fileJson.optString("dataBase64"), Base64.DEFAULT)
                require(data.size.toLong() <= MaxSkillEntryBytes) {
                    "Skill file is too large: $relativePath"
                }
                totalBytes += data.size.toLong()
                require(totalBytes <= MaxSkillExtractedBytes) {
                    "Skill bundle is too large."
                }
                val output = sourceRoot.resolve(relativePath)
                val canonicalRoot = sourceRoot.canonicalPath + File.separator
                val canonicalOutput = output.canonicalPath
                require(canonicalOutput.startsWith(canonicalRoot)) {
                    "Skill bundle entry escaped the destination directory: $relativePath"
                }
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { it.write(data) }
            }
            val installed = installParsedSkill(
                sourceRoot = sourceRoot,
                source = parseBundleSkillInstallSource(bundle.optJSONObject("source")),
            )
            val restored = installed.copy(
                actionLabel = bundle.optString("actionLabel").ifBlank { installed.actionLabel },
                isEnabled = bundle.optBoolean("isEnabled", installed.isEnabled),
                installedAtMillis = bundle.optLong("installedAtMillis", installed.installedAtMillis),
                updatedAtMillis = System.currentTimeMillis(),
            )
            extensionsRepository.upsertInstalledSkill(restored)
            restored
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun validatedInstalledSkillRoot(skill: InstalledSkill): File? {
        val storageRoot = installedSkillsDirectory().canonicalFile
        val root = runCatching { File(skill.skillRootPath).canonicalFile }.getOrNull() ?: return null
        val parent = runCatching { root.parentFile?.canonicalFile }.getOrNull() ?: return null
        if (parent != storageRoot || !root.isDirectory) return null
        return root
    }

    private fun validatedSkillMarkdownFile(
        skill: InstalledSkill,
        root: File,
    ): File? {
        val expected = File(root, SkillFileName).canonicalFile
        val actual = runCatching { File(skill.skillMdPath).canonicalFile }.getOrNull() ?: return null
        if (actual != expected || !actual.isFile) return null
        return actual
    }

    private fun normalizeBundleRelativePath(rawPath: String): String {
        val normalizedPath = rawPath.replace('\\', '/').trim('/')
        require(
            normalizedPath.isNotBlank() &&
                !normalizedPath.startsWith("../") &&
                !normalizedPath.contains("/../") &&
                !File(normalizedPath).isAbsolute
        ) {
            "Skill bundle path must stay inside the skill directory."
        }
        return normalizedPath
    }

    private fun copyZipEntryWithLimits(
        zipInput: ZipInputStream,
        outputFile: File,
        entryName: String,
        extractedBeforeEntry: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        FileOutputStream(outputFile).use { output ->
            while (true) {
                val read = zipInput.read(buffer)
                if (read < 0) break
                entryBytes += read.toLong()
                require(entryBytes <= MaxSkillEntryBytes) {
                    "Skill archive entry is too large: $entryName"
                }
                require(extractedBeforeEntry + entryBytes <= MaxSkillExtractedBytes) {
                    "Skill archive expands to too much data."
                }
                output.write(buffer, 0, read)
            }
        }
        return entryBytes
    }

    private fun parseBundleSkillInstallSource(json: JSONObject?): SkillInstallSource =
        SkillInstallSource(
            kind = SkillInstallKind.fromStorage(json?.optString("kind")),
            label = json?.optString("label").orEmpty(),
            uri = json?.optString("uri").orEmpty(),
            ref = json?.optString("ref").orEmpty(),
            subpath = json?.optString("subpath").orEmpty(),
        )

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
}

internal fun piDiscoveredSkillId(sourcePath: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(sourcePath.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "pi-discovered-${digest.take(20)}"
}

private class LimitedInputStream(
    input: InputStream,
    private val limitBytes: Long,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            countBytes(1)
        }
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            countBytes(read.toLong())
        }
        return read
    }

    private fun countBytes(count: Long) {
        bytesRead += count
        require(bytesRead <= limitBytes) {
            "Skill archive is too large."
        }
    }
}

private data class ParsedSkillDocument(
    val name: String,
    val description: String,
    val license: String,
    val compatibility: String,
    val metadataJson: String,
    val allowedTools: List<String>,
    val bodyMarkdown: String,
    val diagnostics: List<String>,
)

internal fun resolveRemoteDownloadPlan(rawUrl: String): RemoteDownloadPlan {
    val url = rawUrl.trim().ifBlank { error("A remote skill URL is required.") }
    val httpUrl = url.toHttpUrlOrNull() ?: error("Skill URL is not a valid absolute URL.")
    val isZipPath = httpUrl.encodedPath.lowercase(Locale.US).endsWith(".zip")
    if (httpUrl.host.equals("github.com", ignoreCase = true)) {
        val segments = httpUrl.pathSegments.filter { it.isNotBlank() }
        require(segments.size >= 2) { "GitHub URL must include owner and repository." }
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (segments.size >= 4 && segments[2] == "tree") {
            val ref = segments[3]
            val subpath = segments.drop(4).joinToString("/")
            return RemoteDownloadPlan(
                kind = SkillInstallKind.GitHub,
                downloadUrl = "https://api.github.com/repos/$owner/$repository/zipball/$ref",
                ref = ref,
                subpath = subpath,
            )
        }
        if (isZipPath) {
            return RemoteDownloadPlan(
                kind = SkillInstallKind.RemoteZip,
                downloadUrl = httpUrl.toString(),
            )
        }
        return RemoteDownloadPlan(
            kind = SkillInstallKind.GitHub,
            downloadUrl = "https://api.github.com/repos/$owner/$repository/zipball",
        )
    }
    require(isZipPath || isGitHubCodeloadZipUrl(httpUrl.host, httpUrl.pathSegments)) {
        "Remote skill URL must be a GitHub repository/tree URL or a direct .zip file."
    }
    return RemoteDownloadPlan(
        kind = SkillInstallKind.RemoteZip,
        downloadUrl = httpUrl.toString(),
    )
}

private fun isGitHubCodeloadZipUrl(
    host: String,
    pathSegments: List<String>,
): Boolean = host.equals("codeload.github.com", ignoreCase = true) &&
    pathSegments.getOrNull(2)?.equals("zip", ignoreCase = true) == true

internal data class RemoteDownloadPlan(
    val kind: SkillInstallKind,
    val downloadUrl: String,
    val ref: String = "",
    val subpath: String = "",
)
