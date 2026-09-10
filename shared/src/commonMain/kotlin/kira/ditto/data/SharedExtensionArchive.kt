package kira.ditto.data

import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeProcessSignal
import kira.ditto.runtime.RuntimeProcessSpec
import kira.ditto.runtime.SharedExtensionLoadOptions
import kira.ditto.runtime.SharedPiBridgeClient
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject

private const val MaxSharedExtensionArchiveBytes = 128L * 1024L * 1024L
private const val MaxSharedExtensionEntryBytes = 16L * 1024L * 1024L
private const val MaxSharedExtensionArchiveEntries = 8_192
private val SharedExtensionRoots = listOf(
    "/root/.aether/extensions",
    "/root/.pi/agent/extensions",
)

@Serializable
data class SharedExtensionArchive(
    val packageSources: List<String> = emptyList(),
    val importedBundles: List<SharedExtensionBundle> = emptyList(),
    val disabledExtensionPaths: Set<String> = emptySet(),
    val disabledPackageSources: Set<String> = emptySet(),
)

@Serializable
data class SharedExtensionBundle(
    val root: String,
    val name: String,
    val singleFile: Boolean = false,
    val files: List<SharedExtensionBundleFile>,
)

@Serializable
data class SharedExtensionBundleFile(
    val path: String,
    val dataBase64: String,
    val executable: Boolean = false,
)

internal class SharedExtensionArchiveManager(
    private val runtime: MultiplatformLocalRuntime,
    private val bridge: SharedPiBridgeClient,
    private val stateStore: SharedExtensionStateStore,
) {
    suspend fun export(): SharedExtensionArchive {
        val packages = bridge.listExtensionPackages()["packages"] as? JsonArray
        val packageSources = packages.orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.get("source")?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotBlank)
        }.distinct()
        val options = stateStore.load()
        var totalBytes = 0L
        var totalEntries = 0
        val bundles = buildList {
            SharedExtensionRoots.forEach { root ->
                if (!runtime.fileSystem.exists(root)) return@forEach
                val symlink = runSharedExtensionShell(
                    runtime,
                    "find ${root.shellQuote()} -mindepth 1 " +
                        "\\( -type d -name node_modules -prune \\) -o -type l -print -quit",
                )
                require(symlink.exitCode == 0 && symlink.stdout.isBlank()) {
                    "Extensions backup does not support symbolic links."
                }
                val executableFiles = runSharedExtensionShell(
                    runtime,
                    "find ${root.shellQuote()} -mindepth 1 " +
                        "\\( -type d -name node_modules -prune \\) -o " +
                        "\\( -type f -perm /111 -print0 \\)",
                )
                check(executableFiles.exitCode == 0) {
                    executableFiles.stderr.ifBlank { "Unable to inspect Extension permissions." }
                }
                val executablePaths = executableFiles.stdout
                    .split('\u0000')
                    .filter(String::isNotBlank)
                    .toSet()
                val listed = runSharedExtensionShell(
                    runtime,
                    "find ${root.shellQuote()} -mindepth 1 " +
                        "\\( -type d -name node_modules -prune \\) -o -type f -print0 | sort -z",
                )
                check(listed.exitCode == 0) { listed.stderr.ifBlank { "Unable to enumerate Extensions." } }
                listed.stdout.split('\u0000').filter(String::isNotBlank)
                    .groupBy { path -> path.removePrefix("${root.trimEnd('/')}/").substringBefore('/') }
                    .forEach { (name, paths) ->
                        validateSharedExtensionName(name)
                        val singleFile = paths.size == 1 &&
                            paths.single().removePrefix("${root.trimEnd('/')}/") == name
                        val files = paths.map { path ->
                            totalEntries += 1
                            require(totalEntries <= MaxSharedExtensionArchiveEntries) {
                                "Extensions backup contains too many files."
                            }
                            val relativePath = path.removePrefix("${root.trimEnd('/')}/$name/")
                                .takeIf { it != path }
                                ?: path.removePrefix("${root.trimEnd('/')}/")
                            validateSharedExtensionRelativePath(relativePath)
                            val bytes = runtime.fileSystem.read(path, MaxSharedExtensionEntryBytes + 1)
                            require(bytes.size.toLong() <= MaxSharedExtensionEntryBytes) {
                                "Extension file is too large: $relativePath"
                            }
                            totalBytes += bytes.size
                            require(totalBytes <= MaxSharedExtensionArchiveBytes) {
                                "Extensions backup is too large."
                            }
                            SharedExtensionBundleFile(
                                path = relativePath,
                                dataBase64 = Base64.encode(bytes),
                                executable = path in executablePaths,
                            )
                        }
                        add(SharedExtensionBundle(root, name, singleFile, files))
                    }
            }
        }
        return SharedExtensionArchive(
            packageSources = packageSources,
            importedBundles = bundles,
            disabledExtensionPaths = options.disabledExtensionPaths,
            disabledPackageSources = options.disabledPackageSources,
        )
    }

    suspend fun restore(archive: SharedExtensionArchive) {
        validateSharedExtensionArchive(archive)
        stateStore.replace(
            SharedExtensionLoadOptions(
                disabledExtensionPaths = archive.disabledExtensionPaths,
                disabledPackageSources = archive.disabledPackageSources,
            )
        )
        SharedExtensionRoots.forEach { root ->
            runtime.fileSystem.createDirectories(root)
            val cleared = runSharedExtensionShell(
                runtime,
                "find ${root.shellQuote()} -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +",
            )
            check(cleared.exitCode == 0) { cleared.stderr.ifBlank { "Unable to clear Extensions." } }
        }
        archive.importedBundles.forEach { bundle ->
            bundle.files.forEach { file ->
                val target = if (bundle.singleFile) {
                    "${bundle.root.trimEnd('/')}/${bundle.name}"
                } else {
                    "${bundle.root.trimEnd('/')}/${bundle.name}/${file.path}"
                }
                runtime.fileSystem.createDirectories(target.substringBeforeLast('/'))
                runtime.fileSystem.write(
                    target,
                    Base64.decode(file.dataBase64),
                    executable = file.executable,
                )
            }
        }
        archive.importedBundles.filterNot(SharedExtensionBundle::singleFile).forEach { bundle ->
            installRestoredSharedExtensionDependencies(
                runtime,
                "${bundle.root.trimEnd('/')}/${bundle.name}",
            )
        }

        val installedSources = (bridge.listExtensionPackages()["packages"] as? JsonArray)
            .orEmpty().mapNotNull { entry ->
                (entry as? JsonObject)?.get("source")?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf(String::isNotBlank)
            }.toSet()
        installedSources.filterNot(archive.packageSources::contains).forEach { source ->
            bridge.removeExtensionPackage(source)
        }
        archive.packageSources.filterNot(installedSources::contains).forEach { source ->
            bridge.installExtensionPackage(source)
        }
        bridge.reloadAllExtensions()
    }
}

private suspend fun installRestoredSharedExtensionDependencies(
    runtime: MultiplatformLocalRuntime,
    packageRoot: String,
) {
    val manifestPath = "$packageRoot/package.json"
    if (!runtime.fileSystem.exists(manifestPath)) return
    val manifest = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(
            runtime.fileSystem.read(manifestPath, MaxSharedExtensionEntryBytes).decodeToString(),
        ) as? JsonObject
    }.getOrNull() ?: return
    val hasDependencies = listOf("dependencies", "optionalDependencies", "peerDependencies")
        .any { (manifest[it] as? JsonObject)?.isNotEmpty() == true }
    if (!hasDependencies) return
    val installCommand = if (runtime.fileSystem.exists("$packageRoot/package-lock.json")) {
        "npm ci"
    } else {
        "npm install"
    }
    val result = runSharedExtensionShell(
        runtime,
        "cd ${packageRoot.shellQuote()} && $installCommand " +
            "--omit=dev --omit=optional --legacy-peer-deps --no-audit --no-fund --prefer-offline",
    )
    check(result.exitCode == 0) {
        result.stderr.ifBlank { result.stdout }.ifBlank { "Unable to install Extension dependencies." }
    }
    runtime.fileSystem.write("$packageRoot/node_modules/.aether-install-complete", ByteArray(0))
}

internal fun validateSharedExtensionArchive(archive: SharedExtensionArchive) {
    require(archive.packageSources.all { it.isNotBlank() }) { "Extension package source is blank." }
    require(archive.packageSources.size == archive.packageSources.distinct().size) {
        "Extensions backup contains duplicate package sources."
    }
    require(archive.importedBundles.size <= MaxSharedExtensionArchiveEntries) {
        "Extensions backup contains too many bundles."
    }
    var totalBytes = 0L
    var totalEntries = 0
    val bundleKeys = mutableSetOf<String>()
    archive.importedBundles.forEach { bundle ->
        require(bundle.root in SharedExtensionRoots) { "Unsupported Extension root: ${bundle.root}" }
        validateSharedExtensionName(bundle.name)
        require(bundleKeys.add("${bundle.root}/${bundle.name}")) {
            "Extensions backup contains duplicate bundles: ${bundle.name}"
        }
        require(bundle.files.isNotEmpty()) { "Extension bundle is empty: ${bundle.name}" }
        require(!bundle.singleFile || bundle.files.size == 1) {
            "Single-file Extension bundle contains multiple files: ${bundle.name}"
        }
        val paths = mutableSetOf<String>()
        bundle.files.forEach { file ->
            validateSharedExtensionRelativePath(file.path)
            require(paths.add(file.path.replace('\\', '/'))) {
                "Extension bundle contains duplicate files: ${file.path}"
            }
            val bytes = runCatching { Base64.decode(file.dataBase64) }
                .getOrElse { throw IllegalArgumentException("Extension file is not valid Base64: ${file.path}", it) }
            require(bytes.size.toLong() <= MaxSharedExtensionEntryBytes) {
                "Extension file is too large: ${file.path}"
            }
            totalEntries += 1
            require(totalEntries <= MaxSharedExtensionArchiveEntries) {
                "Extensions backup contains too many files."
            }
            totalBytes += bytes.size
            require(totalBytes <= MaxSharedExtensionArchiveBytes) {
                "Extensions backup is too large."
            }
        }
    }
}

private fun validateSharedExtensionName(value: String) {
    require(value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..") {
        "Invalid Extension name: $value"
    }
}

private fun validateSharedExtensionRelativePath(value: String) {
    val normalized = value.replace('\\', '/')
    require(
        normalized.isNotBlank() && !normalized.startsWith('/') &&
            normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) { "Invalid Extension path: $value" }
}

private data class SharedExtensionShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private suspend fun runSharedExtensionShell(
    runtime: MultiplatformLocalRuntime,
    command: String,
): SharedExtensionShellResult = coroutineScope {
    val process = runtime.startProcess(
        RuntimeProcessSpec(
            executable = "/bin/sh",
            arguments = listOf("-lc", command),
            environment = mapOf("HOME" to runtime.homeDirectory),
            workingDirectory = runtime.homeDirectory,
        )
    )
    var completed = false
    try {
        process.closeStdin()
        val stdout = async { process.stdout.toList().flattenBytes().decodeToString() }
        val stderr = async { process.stderr.toList().flattenBytes().decodeToString() }
        val exit = process.awaitExit()
        completed = true
        SharedExtensionShellResult(exit.exitCode, stdout.await(), stderr.await().trim())
    } finally {
        if (!completed) {
            withContext(NonCancellable) { runCatching { process.signal(RuntimeProcessSignal.Kill) } }
        }
    }
}

private fun List<ByteArray>.flattenBytes(): ByteArray {
    val result = ByteArray(sumOf(ByteArray::size))
    var offset = 0
    forEach { bytes ->
        bytes.copyInto(result, destinationOffset = offset)
        offset += bytes.size
    }
    return result
}

private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
