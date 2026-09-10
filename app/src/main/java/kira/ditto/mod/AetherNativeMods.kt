package kira.ditto.mod

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.AetherModKernel
import kira.ditto.data.AetherModOperationDecision
import kira.ditto.data.AetherModOperationInterceptor
import kira.ditto.data.AetherModServiceHandler
import kira.ditto.data.AetherModServiceMethod
import kira.ditto.data.PiExtensionStateRepository
import kira.ditto.data.pi.PiKernelBridge
import kira.ditto.runtime.AlpineRuntime
import kira.ditto.ui.AetherUiState
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val AetherNativeExtensionGuestDirectory = "/root/.aether/extensions"
private const val NativeModPreferences = "aether_native_mods"
private const val PreferenceStartupInProgress = "startup_in_progress"
private const val PreferenceSafeMode = "safe_mode"
private const val PreferenceLastLoadingMod = "last_loading_mod"
private const val PreferenceFailedModIds = "failed_mod_ids"
private const val NativeModUiStableDelayMillis = 5_000L
private const val AetherNativeApiVersion = 1

enum class AetherNativeComponentMode {
    Before,
    After,
    Replace,
    Wrap,
    Hide,
}

fun interface AetherNativeHost {
    suspend fun invoke(
        method: String,
        args: JSONObject,
    ): JSONObject
}

data class AetherNativeComponentContext(
    val target: String,
    val uiState: AetherUiState,
    val publicState: JSONObject,
    val host: AetherNativeHost,
)

interface AetherNativeComponentRenderer {
    @SuppressLint("ComposableNaming")
    @Composable
    fun render(
        context: AetherNativeComponentContext,
        next: @Composable () -> Unit,
    )
}

data class AetherNativeComponentRegistration(
    val target: String,
    val id: String,
    val owner: String,
    val mode: AetherNativeComponentMode,
    val priority: Int,
    val sequence: Long,
    val renderer: AetherNativeComponentRenderer?,
)

data class AetherNativeToolTitleRegistration(
    val toolName: String,
    val runningTitle: String,
    val completedTitle: String,
    val owner: String,
    val priority: Int,
    val sequence: Long,
)

class AetherNativeToolTitleRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val _registrations = MutableStateFlow<List<AetherNativeToolTitleRegistration>>(emptyList())
    val registrations: StateFlow<List<AetherNativeToolTitleRegistration>> = _registrations.asStateFlow()

    fun register(
        toolName: String,
        runningTitle: String,
        completedTitle: String,
        owner: String,
        priority: Int = 100,
    ): () -> Unit {
        val registration = AetherNativeToolTitleRegistration(
            toolName = toolName.trim().also { require(it.isNotBlank()) { "Native tool title requires a tool name." } },
            runningTitle = runningTitle.trim().also { require(it.isNotBlank()) { "Native tool title requires a running title." } },
            completedTitle = completedTitle.trim().also { require(it.isNotBlank()) { "Native tool title requires a completed title." } },
            owner = owner.trim().ifBlank { "unknown" },
            priority = priority,
            sequence = sequence.incrementAndGet(),
        )
        synchronized(lock) { _registrations.value = _registrations.value + registration }
        return { synchronized(lock) { _registrations.value = _registrations.value - registration } }
    }

    fun titleFor(toolName: String, running: Boolean): String? = _registrations.value
        .filter { it.toolName.equals(toolName, ignoreCase = true) }
        .maxWithOrNull(compareBy<AetherNativeToolTitleRegistration> { it.priority }.thenBy { it.sequence })
        ?.let { if (running) it.runningTitle else it.completedTitle }

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            _registrations.value = _registrations.value.filterNot { it.owner == owner }
        }
    }
}

class AetherNativeComponentRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val _registrations =
        MutableStateFlow<List<AetherNativeComponentRegistration>>(emptyList())

    val registrations: StateFlow<List<AetherNativeComponentRegistration>> =
        _registrations.asStateFlow()

    fun register(
        target: String,
        id: String,
        owner: String,
        mode: AetherNativeComponentMode = AetherNativeComponentMode.After,
        priority: Int = 0,
        renderer: AetherNativeComponentRenderer? = null,
    ): () -> Unit {
        val normalizedTarget = target.trim()
        val normalizedId = id.trim()
        require(normalizedTarget.isNotBlank()) {
            "Aether native components require a target."
        }
        require(normalizedId.isNotBlank()) {
            "Aether native components require an id."
        }
        require(mode == AetherNativeComponentMode.Hide || renderer != null) {
            "Aether native component $normalizedId requires a renderer for mode $mode."
        }
        val registration = AetherNativeComponentRegistration(
            target = normalizedTarget,
            id = normalizedId,
            owner = owner.trim().ifBlank { "unknown" },
            mode = mode,
            priority = priority,
            sequence = sequence.incrementAndGet(),
            renderer = renderer,
        )
        synchronized(lock) {
            _registrations.value = _registrations.value + registration
        }
        return {
            synchronized(lock) {
                _registrations.value = _registrations.value - registration
            }
        }
    }

    fun componentsAt(target: String): List<AetherNativeComponentRegistration> =
        _registrations.value
            .filter { it.target == target }
            .sortedWith(
                compareBy<AetherNativeComponentRegistration> { it.priority }
                    .thenBy { it.sequence }
            )

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            _registrations.value = _registrations.value.filterNot { it.owner == owner }
        }
    }
}

interface AetherNativeMod {
    fun onLoad(context: AetherNativeModContext)

    fun onUnload() = Unit
}

class AetherNativeModContext internal constructor(
    val application: Application,
    val modId: String,
    val packageRoot: File,
    val classLoader: ClassLoader,
    val kernel: AetherModKernel,
    val diagnosticLogger: AetherDiagnosticLogger,
) {
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    fun registerService(
        id: String,
        description: String = "",
        priority: Int = 100,
        methods: List<AetherModServiceMethod>,
        handler: AetherModServiceHandler,
    ): () -> Unit = track(
        kernel.services.register(
            id = id,
            owner = modId,
            description = description,
            priority = priority,
            methods = methods,
            handler = handler,
        )
    )

    fun intercept(
        operation: String,
        priority: Int = 100,
        interceptor: AetherModOperationInterceptor,
    ): () -> Unit = track(
        kernel.operations.register(
            operation = operation,
            owner = modId,
            priority = priority,
            interceptor = interceptor,
        )
    )

    fun registerComponent(
        target: String,
        id: String,
        mode: AetherNativeComponentMode = AetherNativeComponentMode.After,
        priority: Int = 100,
        renderer: AetherNativeComponentRenderer? = null,
    ): () -> Unit = track(
        kernel.components.register(
            target = target,
            id = id,
            owner = modId,
            mode = mode,
            priority = priority,
            renderer = renderer,
        )
    )

    fun registerToolTitle(
        toolName: String,
        runningTitle: String,
        completedTitle: String,
        priority: Int = 100,
    ): () -> Unit = track(
        kernel.toolTitles.register(toolName, runningTitle, completedTitle, modId, priority)
    )

    fun packageFile(relativePath: String): File =
        File(packageRoot, relativePath).canonicalFile

    fun log(
        event: String,
        details: Map<String, Any?> = emptyMap(),
        level: String = "info",
    ) {
        diagnosticLogger.event(
            category = "native_mod",
            event = event,
            level = level,
            details = details + ("mod_id" to modId),
        )
    }

    internal fun rollback() {
        cleanups.asReversed().forEach { cleanup ->
            runCatching(cleanup)
        }
        cleanups.clear()
        kernel.services.unregisterOwner(modId)
        kernel.operations.unregisterOwner(modId)
        kernel.components.unregisterOwner(modId)
        kernel.toolTitles.unregisterOwner(modId)
    }

    private fun track(cleanup: () -> Unit): () -> Unit {
        cleanups += cleanup
        return {
            if (cleanups.remove(cleanup)) {
                cleanup()
            }
        }
    }
}

data class AetherNativeModDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val packagePath: String,
    val entrypoints: List<String>,
)

data class AetherLoadedNativeMod(
    val id: String,
    val entrypoint: String,
)

data class AetherNativeModFailure(
    val id: String,
    val entrypoint: String = "",
    val message: String,
)

data class AetherNativeModState(
    val isInitializing: Boolean = false,
    val safeModeActive: Boolean = false,
    val restartRequired: Boolean = false,
    val suspectedCrashModId: String = "",
    val discovered: List<AetherNativeModDescriptor> = emptyList(),
    val loaded: List<AetherLoadedNativeMod> = emptyList(),
    val failures: List<AetherNativeModFailure> = emptyList(),
)

internal data class AetherNativeModManifest(
    val descriptor: AetherNativeModDescriptor,
    val packageRoot: File,
    val classpath: List<File>,
    val libraryPaths: List<File>,
)

@SuppressLint("ApplySharedPref")
class AetherNativeModManager(
    context: Context,
    private val application: Application,
    private val alpineRuntime: AlpineRuntime,
    private val piKernelBridge: PiKernelBridge,
    private val kernel: AetherModKernel,
    private val piExtensionStateRepository: PiExtensionStateRepository,
    private val diagnosticLogger: AetherDiagnosticLogger,
) {
    private data class LoadedEntrypoint(
        val instance: AetherNativeMod,
        val context: AetherNativeModContext,
        val classLoader: ClassLoader,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        NativeModPreferences,
        Context.MODE_PRIVATE,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val loadedEntrypoints = CopyOnWriteArrayList<LoadedEntrypoint>()
    private val _state = MutableStateFlow(AetherNativeModState())
    private val lock = Any()
    private var didInitialize = false
    private var initializationCompleted = false
    private var uiStable = false

    val state: StateFlow<AetherNativeModState> = _state.asStateFlow()

    suspend fun initialize() {
        synchronized(lock) {
            if (didInitialize) return
            didInitialize = true
        }
        val importedDiscovery = discoverNativeMods(includeInstalledPackages = false)
        val previousStartupWasInterrupted =
            preferences.getBoolean(PreferenceStartupInProgress, false)
        val suspectedModId = preferences.getString(PreferenceLastLoadingMod, "").orEmpty()
        val previousFailedModIds = preferences
            .getStringSet(PreferenceFailedModIds, emptySet())
            .orEmpty()
        val safeModeActive = preferences.getBoolean(PreferenceSafeMode, false) ||
            previousStartupWasInterrupted
        if (previousStartupWasInterrupted) {
            preferences.edit()
                .putBoolean(PreferenceSafeMode, true)
                .putBoolean(PreferenceStartupInProgress, false)
                .apply()
        }
        if (safeModeActive) {
            _state.value = AetherNativeModState(
                safeModeActive = true,
                suspectedCrashModId = suspectedModId,
                discovered = importedDiscovery.manifests.map(AetherNativeModManifest::descriptor),
                failures = (
                    importedDiscovery.failures +
                        previousFailedModIds.map { id ->
                            AetherNativeModFailure(
                                id = id,
                                message = "Failed during the previous Native Mod startup.",
                            )
                        }
                    ).distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
            )
            diagnosticLogger.event(
                category = "native_mod",
                event = "safe_mode_active",
                level = "warn",
                details = mapOf(
                    "suspected_mod_id" to suspectedModId,
                    "discovered_count" to importedDiscovery.manifests.size,
                ),
            )
            val completeDiscovery = discoverNativeMods()
            _state.value = _state.value.copy(
                discovered = completeDiscovery.manifests.map(AetherNativeModManifest::descriptor),
                failures = (
                    completeDiscovery.failures +
                        previousFailedModIds.map { id ->
                            AetherNativeModFailure(
                                id = id,
                                message = "Failed during the previous Native Mod startup.",
                            )
                        }
                    ).distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
            )
            markInitializationCompleted()
            return
        }

        var startupGuardActive = false
        val loaded = mutableListOf<AetherLoadedNativeMod>()
        val failures = importedDiscovery.failures.toMutableList()
        fun startLoading(manifests: List<AetherNativeModManifest>) {
            if (manifests.isEmpty()) return
            if (!startupGuardActive) {
                synchronized(lock) {
                    initializationCompleted = false
                }
                preferences.edit()
                    .putBoolean(PreferenceStartupInProgress, true)
                    .putString(PreferenceLastLoadingMod, "")
                    .commit()
                startupGuardActive = true
            }
            _state.value = AetherNativeModState(
                isInitializing = true,
                discovered = manifests.map(AetherNativeModManifest::descriptor),
                loaded = loaded.toList(),
                failures = failures.toList(),
            )
        }
        fun finishLoadingPhase() {
            if (!startupGuardActive) return
            startupGuardActive = false
            markInitializationCompleted()
        }

        startLoading(importedDiscovery.manifests)
        importedDiscovery.manifests.forEach { manifest ->
            loadManifest(manifest, loaded, failures)
        }
        if (importedDiscovery.manifests.isNotEmpty()) {
            _state.value = _state.value.copy(
                loaded = loaded.toList(),
                failures = failures.toList(),
            )
            finishLoadingPhase()
        }

        val completeDiscovery = discoverNativeMods()
        val importedPaths = importedDiscovery.manifests
            .map { it.packageRoot.canonicalPath }
            .toSet()
        val additionalManifests = completeDiscovery.manifests.filter { manifest ->
            manifest.packageRoot.canonicalPath !in importedPaths
        }
        failures += completeDiscovery.failures
        if (additionalManifests.isNotEmpty()) {
            startLoading(completeDiscovery.manifests)
        }
        additionalManifests.forEach { manifest ->
            loadManifest(manifest, loaded, failures)
        }
        preferences.edit()
            .putStringSet(
                PreferenceFailedModIds,
                failures.map(AetherNativeModFailure::id).toSet(),
            )
            .apply()
        _state.value = AetherNativeModState(
            discovered = completeDiscovery.manifests.map(AetherNativeModManifest::descriptor),
            loaded = loaded,
            failures = failures.distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
        )
        finishLoadingPhase()
        markInitializationCompleted()
    }

    fun notifyUiStable() {
        handler.removeCallbacksAndMessages(this)
        handler.postAtTime(
            {
                synchronized(lock) {
                    uiStable = true
                }
                maybeClearStartupGuard()
            },
            this,
            android.os.SystemClock.uptimeMillis() + NativeModUiStableDelayMillis,
        )
    }

    fun allowNativeModsOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, false)
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
        _state.value = _state.value.copy(
            safeModeActive = false,
            restartRequired = true,
            suspectedCrashModId = "",
        )
    }

    suspend fun refreshDiscovery() {
        val discovery = discoverNativeMods()
        val descriptors = discovery.manifests.map(AetherNativeModManifest::descriptor)
        val current = _state.value
        val changed = current.discovered != descriptors
        _state.value = current.copy(
            discovered = descriptors,
            restartRequired = current.restartRequired || (didInitialize && changed),
            failures = (current.failures + discovery.failures)
                .distinctBy { failure ->
                    "${failure.id}:${failure.entrypoint}:${failure.message}"
                },
        )
    }

    fun requestDisableOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, true)
            .putBoolean(PreferenceStartupInProgress, false)
            .apply()
        _state.value = _state.value.copy(
            restartRequired = true,
        )
    }

    fun shutdown() {
        loadedEntrypoints.asReversed().forEach { loaded ->
            runCatching { loaded.instance.onUnload() }
                .onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "native_mod",
                        event = "unload_failed",
                        throwable = throwable,
                        details = mapOf(
                            "mod_id" to loaded.context.modId,
                        ),
                    )
                }
            loaded.context.rollback()
        }
        loadedEntrypoints.clear()
    }

    private fun loadManifest(
        manifest: AetherNativeModManifest,
        loaded: MutableList<AetherLoadedNativeMod>,
        failures: MutableList<AetherNativeModFailure>,
    ) {
        val classLoader = runCatching {
            val optimizedDirectory = File(
                appContext.codeCacheDir,
                "aether-native-mods/${sanitizeId(manifest.descriptor.id)}",
            ).apply {
                require(mkdirs() || isDirectory) {
                    "Unable to create native mod code cache."
                }
            }
            DexClassLoader(
                manifest.classpath.joinToString(File.pathSeparator) { it.path },
                optimizedDirectory.path,
                manifest.libraryPaths
                    .takeIf(List<File>::isNotEmpty)
                    ?.joinToString(File.pathSeparator) { it.path },
                appContext.classLoader,
            )
        }.getOrElse { throwable ->
            failures += AetherNativeModFailure(
                id = manifest.descriptor.id,
                message = throwable.message ?: throwable.javaClass.name,
            )
            diagnosticLogger.exception(
                category = "native_mod",
                event = "classloader_failed",
                throwable = throwable,
                details = mapOf("mod_id" to manifest.descriptor.id),
            )
            return
        }

        manifest.descriptor.entrypoints.forEach { entrypoint ->
            val ownerId = "${manifest.descriptor.id}:$entrypoint"
            preferences.edit()
                .putString(PreferenceLastLoadingMod, ownerId)
                .commit()
            val nativeContext = AetherNativeModContext(
                application = application,
                modId = ownerId,
                packageRoot = manifest.packageRoot,
                classLoader = classLoader,
                kernel = kernel,
                diagnosticLogger = diagnosticLogger,
            )
            runCatching {
                val entrypointClass = Class.forName(entrypoint, true, classLoader)
                val instance = instantiateNativeMod(entrypointClass)
                require(instance is AetherNativeMod) {
                    "$entrypoint does not implement ${AetherNativeMod::class.java.name}."
                }
                instance.onLoad(nativeContext)
                loadedEntrypoints += LoadedEntrypoint(
                    instance = instance,
                    context = nativeContext,
                    classLoader = classLoader,
                )
                loaded += AetherLoadedNativeMod(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                )
                diagnosticLogger.event(
                    category = "native_mod",
                    event = "loaded",
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }.onFailure { throwable ->
                nativeContext.rollback()
                failures += AetherNativeModFailure(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                    message = throwable.message ?: throwable.javaClass.name,
                )
                diagnosticLogger.exception(
                    category = "native_mod",
                    event = "load_failed",
                    throwable = throwable,
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }
        }
    }

    private fun markInitializationCompleted() {
        synchronized(lock) {
            initializationCompleted = true
        }
        maybeClearStartupGuard()
    }

    private fun maybeClearStartupGuard() {
        val shouldClear = synchronized(lock) {
            initializationCompleted && uiStable
        }
        if (!shouldClear) return
        preferences.edit()
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
    }

    private suspend fun discoverNativeMods(
        includeInstalledPackages: Boolean = true,
    ): NativeModDiscovery {
        val loadOptions = piExtensionStateRepository.loadOptions()
        val disabledPaths = loadOptions.disabledExtensionPaths
        val root = alpineRuntime.resolveManagedGuestPath(
            AetherNativeExtensionGuestDirectory
        )
        val manifests = mutableListOf<AetherNativeModManifest>()
        val failures = mutableListOf<AetherNativeModFailure>()
        val importedPackageRoots = root
            .takeIf(File::isDirectory)
            ?.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
        val installedPackageRoots = if (includeInstalledPackages) runCatching {
            val packages = piKernelBridge.listExtensionPackages().optJSONArray("packages")
                ?: return@runCatching emptyList<File>()
            buildList {
                for (index in 0 until packages.length()) {
                    val item = packages.optJSONObject(index) ?: continue
                    val source = item.optString("source").trim()
                    if (source in loadOptions.disabledPackageSources) continue
                    if (item.optInt("native_entrypoint_count") <= 0) continue
                    val installedPath = item.optString("installed_path").trim()
                    if (installedPath.isBlank()) continue
                    val packageRoot = alpineRuntime.resolveGuestPath(installedPath)
                    if (packageRoot.isDirectory) add(packageRoot)
                }
            }
        }.onFailure { throwable ->
            diagnosticLogger.exception(
                category = "native_mod",
                event = "package_discovery_failed",
                throwable = throwable,
                level = "warn",
            )
        }.getOrDefault(emptyList()) else emptyList()
        (importedPackageRoots + installedPackageRoots)
            .distinctBy { it.canonicalPath }
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { packageRoot ->
                val guestPath = "$AetherNativeExtensionGuestDirectory/${packageRoot.name}"
                if (guestPath in disabledPaths) return@forEach
                val manifestFile = File(packageRoot, "package.json")
                if (!manifestFile.isFile) return@forEach
                runCatching {
                    parseAetherNativeModManifest(
                        packageRoot = packageRoot,
                        manifest = JSONObject(manifestFile.readText(Charsets.UTF_8)),
                    )
                }.onSuccess { manifest ->
                    if (manifest != null) manifests += manifest
                }.onFailure { throwable ->
                    failures += AetherNativeModFailure(
                        id = packageRoot.name,
                        message = throwable.message ?: throwable.javaClass.name,
                    )
                    diagnosticLogger.exception(
                        category = "native_mod",
                        event = "manifest_failed",
                        throwable = throwable,
                        details = mapOf("package_path" to packageRoot.path),
                    )
                }
            }
        return NativeModDiscovery(
            manifests = manifests,
            failures = failures,
        )
    }
}

private data class NativeModDiscovery(
    val manifests: List<AetherNativeModManifest> = emptyList(),
    val failures: List<AetherNativeModFailure> = emptyList(),
)

internal fun parseAetherNativeModManifest(
    packageRoot: File,
    manifest: JSONObject,
): AetherNativeModManifest? {
    val native = manifest
        .optJSONObject("aether")
        ?.optJSONObject("native")
        ?: return null
    if (!native.optBoolean("enabled", true)) return null

    val packageCanonical = packageRoot.canonicalFile
    val id = manifest.optString("name").trim().ifBlank { packageCanonical.name }
    native.opt("api")
        .takeUnless { it == null || it == JSONObject.NULL }
        ?.let { configured ->
            requireAetherApiCompatibility(
                configured = configured,
                currentVersion = AetherNativeApiVersion,
                label = "Native mod $id",
            )
        }
    val entrypoints = native.stringList("entrypoints")
        .ifEmpty { native.stringList("entrypoint") }
    require(entrypoints.isNotEmpty()) {
        "Native mod $id must declare aether.native.entrypoints."
    }
    val classpath = native.stringList("classpath").map { path ->
        resolvePackagePath(packageCanonical, path).also { file ->
            require(file.isFile) {
                "Native mod classpath does not exist: $path"
            }
        }
    }
    require(classpath.isNotEmpty()) {
        "Native mod $id must declare aether.native.classpath."
    }
    val libraryPaths = (
        native.stringList("libraryPath") +
            native.stringList("library_path")
        )
        .distinct()
        .map { path ->
            resolvePackagePath(packageCanonical, path).also { file ->
                require(file.isDirectory) {
                    "Native mod library path does not exist: $path"
                }
            }
        }
    return AetherNativeModManifest(
        descriptor = AetherNativeModDescriptor(
            id = id,
            name = manifest.optString("displayName").trim().ifBlank { id },
            version = manifest.optString("version"),
            packagePath = packageCanonical.path,
            entrypoints = entrypoints,
        ),
        packageRoot = packageCanonical,
        classpath = classpath,
        libraryPaths = libraryPaths,
    )
}

internal fun requireAetherApiCompatibility(
    configured: Any,
    currentVersion: Int,
    label: String,
) {
    val exactVersion = (configured as? Number)?.toInt()
    if (exactVersion != null) {
        require(exactVersion > 0 && exactVersion.toDouble() == configured.toDouble()) {
            "$label api must be a positive integer or an API range object."
        }
        require(exactVersion == currentVersion) {
            "$label requires API $exactVersion, but this runtime provides $currentVersion."
        }
        return
    }
    require(configured is JSONObject) {
        "$label api must be a positive integer or an API range object."
    }
    val minimum = configured.optPositiveApiVersion("min", label)
    val maximum = configured.optPositiveApiVersion("max", label)
    require(minimum != null || maximum != null) {
        "$label api must declare min, max, or both."
    }
    require(minimum == null || maximum == null || minimum <= maximum) {
        "$label api.min cannot be greater than api.max."
    }
    require(minimum == null || currentVersion >= minimum) {
        "$label requires API $minimum or newer, but this runtime provides $currentVersion."
    }
    require(
        maximum == null ||
            currentVersion <= maximum ||
            configured.optBoolean("allowNewer", false)
    ) {
        "$label supports API through $maximum, but this runtime provides $currentVersion."
    }
}

private fun JSONObject.optPositiveApiVersion(
    key: String,
    label: String,
): Int? {
    if (!has(key)) return null
    val value = opt(key)
    val number = value as? Number
    require(
        number != null &&
            number.toInt() > 0 &&
            number.toInt().toDouble() == number.toDouble()
    ) {
        "$label api.$key must be a positive integer."
    }
    return number.toInt()
}

private fun JSONObject.stringList(key: String): List<String> = when (
    val value = opt(key)
) {
    is JSONArray -> buildList {
        for (index in 0 until value.length()) {
            value.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    is String -> value.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}

private fun resolvePackagePath(
    packageRoot: File,
    path: String,
): File {
    val file = File(packageRoot, path).canonicalFile
    require(
        file.path == packageRoot.path ||
            file.path.startsWith(packageRoot.path + File.separator)
    ) {
        "Native mod path escaped its package: $path"
    }
    return file
}

private fun instantiateNativeMod(entrypointClass: Class<*>): Any {
    runCatching {
        entrypointClass.getField("INSTANCE").get(null)
    }.getOrNull()?.let { return it }
    return entrypointClass.getDeclaredConstructor().apply {
        isAccessible = true
    }.newInstance()
}

private fun sanitizeId(value: String): String =
    value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "native-mod" }

fun AetherNativeModContext.cancelOperation(
    payload: JSONObject,
    reason: String = "",
): AetherModOperationDecision = AetherModOperationDecision(
    payload = payload,
    cancelled = true,
    reason = reason,
)
