package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kira.ditto.BuildConfig

import kira.ditto.R
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kira.ditto.data.AetherAppExtensionError
import kira.ditto.data.AetherGitHubUrl
import kira.ditto.data.AetherPersonalGitHubUrl
import kira.ditto.data.AetherUpaPluginGitHubUrl
import kira.ditto.data.AgentModeAuthorizationIssue
import kira.ditto.data.AgentModeAuthorizationMethod
import kira.ditto.data.AgentModeAuthorizationState
import kira.ditto.data.AgentModeDisplayState
import kira.ditto.data.LlmUsageTotals
import kira.ditto.data.AutomaticModelPurpose
import kira.ditto.data.ChatUsageStatisticsSnapshot
import kira.ditto.data.LlmUsageSources
import kira.ditto.data.AppLanguage
import kira.ditto.data.AppThemeMode
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.PiProviderCatalog
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.McpServerTestOperation
import kira.ditto.data.HostSecretStore
import kira.ditto.data.GmailAuth
import kira.ditto.data.AmapAuth
import kira.ditto.data.GithubAuth
import kira.ditto.data.HuggingFaceAuth
import kira.ditto.data.SpotifyAuth
import kira.ditto.data.foldMcpSecretDrafts
import kira.ditto.data.inferMcpSecretSlots
import kira.ditto.data.mcpSecretDraftKey
import kira.ditto.data.parseMcpKeyValueLines
import kira.ditto.data.InstalledPiExtension
import kira.ditto.data.PiExtensionCatalogEntry
import kira.ditto.data.PiExtensionInstallKind
import kira.ditto.data.PiPackageCompatibilityIssue
import kira.ditto.data.PiPackageDetails
import kira.ditto.data.PackageProfileState
import kira.ditto.data.ProviderModelOption
import kira.ditto.data.VoiceProfileCatalog
import kira.ditto.data.looksLikeTtsModel
import kira.ditto.data.looksLikeRealtimeModel
import kira.ditto.data.RootSetupIssue
import kira.ditto.data.RootSetupState
import kira.ditto.data.AppStorageClearKind
import kira.ditto.data.AppStorageUsage
import kira.ditto.data.DefaultStaleCacheRetentionDays
import kira.ditto.data.formatStorageBytes
import kira.ditto.data.ScheduledTask
import kira.ditto.data.ScheduledTaskCreator
import kira.ditto.data.ScheduledTaskSchedule
import kira.ditto.data.isGuiWatchTask
import kira.ditto.data.TermuxEnvironmentVariable
import kira.ditto.data.formatScheduledTaskTime
import kira.ditto.data.summary
import kira.ditto.data.tokenCacheHitPercent
import kira.ditto.data.availableModelOptions
import kira.ditto.data.availableModels
import kira.ditto.data.enabledModels
import kira.ditto.data.looksLikeEmbeddingModel
import kira.ditto.data.looksLikeImageModel
import kira.ditto.data.looksLikeAsrModel
import kira.ditto.data.AsrAvailability
import kira.ditto.data.AsrCapabilityProbe
import kira.ditto.data.AsrCapabilitySnapshot
import kira.ditto.data.AsrDeviceModelKeys
import kira.ditto.data.ProviderAuthMethod
import kira.ditto.data.pi.PiProviderAuthState
import kira.ditto.data.findModelOption
import kira.ditto.data.kimi.mergeTokensByDisplayModelId
import kira.ditto.data.normalizeLlmInactivityReconnectTimeoutSeconds
import kira.ditto.data.normalizeOldCommandHistoryRetentionHours
import kira.ditto.data.normalizeTavilyBaseUrl
import kira.ditto.data.quickActionLabel
import kira.ditto.data.resolveAutomaticModelKey
import kira.ditto.data.sortedForAutomaticModelPurpose
import kira.ditto.mod.AetherNativeModState
import kira.ditto.runtime.LocalRuntimeIssue
import org.json.JSONArray
import org.json.JSONObject
import kira.ditto.runtime.LocalRuntimeSetupState
import kira.ditto.runtime.AlpineSetupActivity
import kira.ditto.runtime.AlpineSetupProgress
import kira.ditto.runtime.AlpineTerminalLaunchSpec
import kira.ditto.runtime.AndroidAlpineFileManagerRuntime
import kira.ditto.runtime.BundledKimiCodeCliVersion
import kira.ditto.termux.TermuxSetupState
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnPrimary
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AlpineIconBlue
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSettingsBackground
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Page enum - drives the local in-composable navigation
// -----------------------------------------------------------------------------

private enum class SettingsPage {
    Hub,
    General,
    Providers,
    DefaultModels,
    DefaultChatModel,
    DefaultTitleModel,
    DefaultNamingModel,
    DefaultCompactingModel,
    DefaultVectorModel,
    DefaultImageModel,
    DefaultAsrModel,
    DefaultTtsModel,
    AddProvider,
    EditProvider,
    Personalization,
    WebTools,
    Reliability,
    ExtensionSettings,
    ExtensionSettingsCategory,
    Skills,
    AddSkill,
    Extensions,
    PackageDetail,
    McpServers,
    AddMcpServer,
    EditMcpServer,
    ScheduledTasks,
    AddScheduledTask,
    EditScheduledTask,
    Termux,
    Alpine,
    AlpineTerminal,
    AlpineFiles,
    RuntimeDefaults,
    AgentMode,
    Browser,
    Statistics,
    Memory,
    CacheAndData,
    RootSetupProgress,
    Developer,
    About,
    GitHub,
    PrivacyPolicy,
}

private val SettingsAutoRefreshBackoffMillis = longArrayOf(5_000L, 15_000L, 30_000L)

/**
 * Polls [refresh] only while the host is RESUMED, stretching the interval so a page left
 * open in the background stops burning a wake-up every five seconds.
 */
@Composable
private fun SettingsAutoRefreshEffect(key: Any? = Unit, refresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentRefresh()
            var step = 0
            while (true) {
                delay(SettingsAutoRefreshBackoffMillis[step])
                if (step < SettingsAutoRefreshBackoffMillis.lastIndex) step++
                currentRefresh()
            }
        }
    }
}

private fun SettingsPage.depth(): Int = when (this) {
    SettingsPage.Hub -> 0
    SettingsPage.General,
    SettingsPage.Providers,
    SettingsPage.Personalization,
    SettingsPage.WebTools,
    SettingsPage.Reliability,
    SettingsPage.ExtensionSettings,
    SettingsPage.Skills,
    SettingsPage.Extensions,
    SettingsPage.McpServers,
    SettingsPage.ScheduledTasks,
    SettingsPage.Termux,
    SettingsPage.Alpine,
    SettingsPage.RuntimeDefaults,
    SettingsPage.AgentMode,
    SettingsPage.Browser,
    SettingsPage.Statistics,
    SettingsPage.Memory,
    SettingsPage.CacheAndData,
    SettingsPage.Developer,
    SettingsPage.About -> 1
    SettingsPage.GitHub,
    SettingsPage.PrivacyPolicy,
    SettingsPage.DefaultModels,
    SettingsPage.AddProvider,
    SettingsPage.EditProvider,
    SettingsPage.AddSkill,
    SettingsPage.PackageDetail,
    SettingsPage.AddMcpServer,
    SettingsPage.EditMcpServer,
    SettingsPage.AddScheduledTask,
    SettingsPage.EditScheduledTask,
    SettingsPage.AlpineTerminal,
    SettingsPage.AlpineFiles,
    SettingsPage.RootSetupProgress -> 2
    SettingsPage.ExtensionSettingsCategory -> 2
    SettingsPage.DefaultChatModel,
    SettingsPage.DefaultTitleModel,
    SettingsPage.DefaultNamingModel,
    SettingsPage.DefaultCompactingModel,
    SettingsPage.DefaultVectorModel,
    SettingsPage.DefaultImageModel,
    SettingsPage.DefaultAsrModel,
    SettingsPage.DefaultTtsModel -> 3
}

private fun SettingsPage.toRootSetupProgressReturnPage(): RootSetupProgressReturnPage =
    when (this) {
        SettingsPage.AgentMode -> RootSetupProgressReturnPage.AgentMode
        else -> RootSetupProgressReturnPage.Termux
    }

private fun RootSetupProgressReturnPage.toSettingsPage(): SettingsPage =
    when (this) {
        RootSetupProgressReturnPage.Termux -> SettingsPage.Termux
        RootSetupProgressReturnPage.AgentMode -> SettingsPage.AgentMode
    }

private fun formatTaskMinute(minuteOfDay: Int): String {
    val normalized = minuteOfDay.coerceIn(0, 1_439)
    return "%02d:%02d".format(Locale.US, normalized / 60, normalized % 60)
}

private fun parseTaskMinute(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun parseTaskTimes(value: String): List<Int> =
    value.split(',', ';', '\n')
        .mapNotNull(::parseTaskMinute)
        .distinct()
        .sorted()

private fun parseTaskDays(value: String): List<Int> =
    value.split(',', ';', ' ')
        .mapNotNull { raw ->
            val normalized = raw.trim().lowercase(Locale.US)
            when (normalized.take(3)) {
                "mon" -> 1
                "tue" -> 2
                "wed" -> 3
                "thu" -> 4
                "fri" -> 5
                "sat" -> 6
                "sun" -> 7
                else -> normalized.toIntOrNull()
            }?.takeIf { it in 1..7 }
        }
        .distinct()
        .sorted()

// -----------------------------------------------------------------------------
// Animation constants
// -----------------------------------------------------------------------------

private const val PageTransitionDuration = 320
private val PageTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private val SettingsTopFadeHeight = 40.dp
private val StatisticsInputColor = Color(0xFF5D7CFF)
private val StatisticsOutputColor = Color(0xFF7B68EE)
private val StatisticsReasoningColor = Color(0xFFA9B8FF)
private val StatisticsNeutralChartColor = Color(0xFFDCE4FF)



@Composable
private fun settingsLanguageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.English -> stringResource(R.string.language_english)
    AppLanguage.SimplifiedChinese -> stringResource(R.string.language_simplified_chinese)
    AppLanguage.Persian -> stringResource(R.string.language_persian)
}

@Composable
private fun settingsLanguageSubtitle(language: AppLanguage): String = when (language) {
    AppLanguage.English -> stringResource(R.string.settings_language_english_interface)
    AppLanguage.SimplifiedChinese -> stringResource(R.string.settings_language_simplified_chinese_interface)
    AppLanguage.Persian -> stringResource(R.string.settings_language_persian_interface)
}

@Composable
private fun settingsThemeDisplayName(themeMode: AppThemeMode): String = when (themeMode) {
    AppThemeMode.System -> stringResource(R.string.theme_system)
    AppThemeMode.Light -> stringResource(R.string.theme_light)
    AppThemeMode.Dark -> stringResource(R.string.theme_dark)
}

@Composable
private fun settingsThemeSubtitle(themeMode: AppThemeMode): String = when (themeMode) {
    AppThemeMode.System -> stringResource(R.string.settings_system_theme_subtitle)
    AppThemeMode.Light -> stringResource(R.string.settings_light_theme_subtitle)
    AppThemeMode.Dark -> stringResource(R.string.settings_dark_theme_subtitle)
}

@Composable
private fun settingsGeneralSummary(language: AppLanguage, themeMode: AppThemeMode): String =
    stringResource(
        R.string.settings_general_summary,
        settingsLanguageDisplayName(language),
        settingsThemeDisplayName(themeMode),
    )

@Composable
private fun settingsEnabledProvidersSummary(enabledCount: Int): String =
    stringResource(R.string.settings_enabled_providers_count, enabledCount)

@Composable
private fun settingsReleaseSummary(versionName: String): String =
    stringResource(R.string.settings_release_summary, versionName)

private fun settingsTopOverlayBodyGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherSettingsBackground.copy(alpha = 0.96f),
        0.18f to AetherSettingsBackground.copy(alpha = 0.86f),
        0.42f to AetherSettingsBackground.copy(alpha = 0.48f),
        0.72f to AetherSettingsBackground.copy(alpha = 0.22f),
        1.0f to AetherSettingsBackground.copy(alpha = 0.12f),
    )
)

private fun settingsTopOverlayTailGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherSettingsBackground.copy(alpha = 0.12f),
        0.42f to AetherSettingsBackground.copy(alpha = 0.05f),
        1.0f to Color.Transparent,
    )
)

// -----------------------------------------------------------------------------
// Root composable - drop-in replacement for the old SettingsScreen in AetherApp
// -----------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    systemPrompt: String,
    tavilyApiKey: String,
    tavilyBaseUrl: String,
    llmInactivityReconnectTimeoutSeconds: Int,
    keepTasksRunningInBackground: Boolean,
    notifyOnTaskCompletion: Boolean,
    autoCleanOldCommandHistory: Boolean,
    oldCommandHistoryRetentionHours: Int,
    termuxEnvironmentVariables: List<TermuxEnvironmentVariable>,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    rootSetupState: RootSetupState,
    rootSetupProgressReturnPage: RootSetupProgressReturnPage?,
    language: AppLanguage,
    themeMode: AppThemeMode,
    defaultChatModelKey: String,
    defaultTitleModelKey: String,
    defaultNamingModelKey: String,
    defaultCompactingModelKey: String,
    defaultVectorModelKey: String,
    defaultImageModelKey: String,
    defaultAsrModelKey: String,
    defaultTtsModelKey: String,
    ttsEnabled: Boolean,
    ttsVoiceId: String,
    agentModeDisplayState: AgentModeDisplayState,
    providerConfigs: List<LlmProviderConfig>,
    usageStatisticsSnapshots: List<ChatUsageStatisticsSnapshot>,
    usageStatisticsTotals: LlmUsageTotals = LlmUsageTotals(),
    usageStatisticsRefreshing: Boolean = false,
    onRefreshUsageStatistics: () -> Unit = {},
    storageUsage: AppStorageUsage = AppStorageUsage(),
    storageBusy: Boolean = false,
    memoryPages: List<kira.ditto.data.MemoryPage> = emptyList(),
    onRefreshMemoryPages: () -> Unit = {},
    onDeleteMemoryPage: (String) -> Unit = {},
    onClearMemoryPages: () -> Unit = {},
    onRefreshStorageUsage: () -> Unit = {},
    onClearAppStorage: (AppStorageClearKind) -> Unit = {},
    onUpdateCacheCleanupPolicy: (Boolean, Int) -> Unit = { _, _ -> },
    scheduledTasks: List<ScheduledTask>,
    termuxSetupState: TermuxSetupState,
    alpineSetupState: LocalRuntimeSetupState,
    enabledRuntimeIds: Set<LocalRuntimeId>,
    defaultRuntimeId: LocalRuntimeId?,
    alpinePackageProfiles: Map<String, PackageProfileState>,
    alpinePackageInstallProgress: Map<String, AlpineSetupProgress>,
    alpineFileManagerRuntime: AndroidAlpineFileManagerRuntime,
    developerTermuxReadyOverride: Boolean?,
    installedSkills: List<kira.ditto.data.InstalledSkill>,
    installedPiExtensions: List<InstalledPiExtension>,
    hasLoadedInstalledPiExtensions: Boolean,
    nativeModState: AetherNativeModState,
    piExtensionCatalog: List<PiExtensionCatalogEntry>,
    isLoadingPiExtensions: Boolean,
    piExtensionCatalogError: String,
    piExtensionOperationSource: String,
    selectedPiPackageDetails: PiPackageDetails?,
    selectedPiPackageSource: String,
    isLoadingPiPackageDetails: Boolean,
    piPackageDetailsError: String,
    mcpServers: List<kira.ditto.data.McpServerConfig>,
    isFetchingModels: Boolean,
    providerAuthState: PiProviderAuthState,
    appUpdate: AppUpdateUiState,
    onSave: (
        String,
        String,
        String,
        Int,
        Boolean,
        Boolean,
        Boolean,
        Int,
        List<TermuxEnvironmentVariable>,
        Boolean,
        AgentModeAuthorizationMethod,
        AppLanguage,
        AppThemeMode,
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        Boolean,
        String,
    ) -> Unit,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onUpdateThemeMode: (AppThemeMode) -> Unit,
    onUpsertProviderConfig: (LlmProviderConfig) -> Unit,
    onRemoveProviderConfig: (String) -> Unit,
    onSetProviderEnabled: (String, Boolean) -> Unit,
    onFetchModels: (LlmProviderConfig, (List<String>) -> Unit) -> Unit,
    onStartProviderLogin: (String, String, ProviderAuthMethod, String) -> Unit,
    onSubmitProviderAuthPrompt: (String, String, Boolean) -> Unit,
    onClearProviderAuthState: () -> Unit,
    onImportSkillFolder: () -> Unit,
    onImportSkillZip: ((Boolean) -> Unit) -> Unit,
    onInstallSkillUrl: (String, (Boolean) -> Unit) -> Unit,
    onToggleSkillEnabled: (String, Boolean) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onRefreshPiExtensions: () -> Unit,
    onInstallPiExtensionPackage: (String) -> Unit,
    onLoadPiPackageDetails: (PiExtensionCatalogEntry) -> Unit,
    onUpdatePiExtensionPackage: (String) -> Unit,
    onRemovePiExtension: (InstalledPiExtension) -> Unit,
    onSetPiExtensionEnabled: (InstalledPiExtension, Boolean) -> Unit,
    onImportPiExtension: () -> Unit,
    onAllowNativeModsOnNextStart: () -> Unit,
    onDisableNativeModsOnNextStart: () -> Unit,
    onSaveHttpMcpServer: (String?, String, String, String) -> Unit,
    onSaveStdIoMcpServer: (String?, String, String, String, String, String, LocalRuntimeId?) -> Unit,
    onToggleMcpServerEnabled: (String, Boolean) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onTestMcpServer: (String, McpServerTestOperation, (String) -> Unit) -> Unit,
    onSaveScheduledTask: (String?, String, String, ScheduledTaskSchedule, Boolean) -> Unit,
    onToggleScheduledTaskEnabled: (String, Boolean) -> Unit,
    onRemoveScheduledTask: (String) -> Unit,
    onRefreshScheduledTasks: () -> Unit = {},
    onRequestTermuxPermission: () -> Unit,
    onImportAppData: () -> Unit,
    onExportAppData: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onInitializeAlpineRuntime: () -> Unit,
    onResetAlpineRuntime: () -> Unit,
    onRefreshAlpineSetup: () -> Unit,
    onInstallAlpinePackageProfile: (String) -> Unit,
    onCreateAlpineTerminalLaunchSpec: suspend () -> Result<AlpineTerminalLaunchSpec>,
    browserPreferences: kira.ditto.data.BrowserPreferences,
    onUpdateBrowserPreferences: (kira.ditto.data.BrowserPreferences) -> Unit,
    onOpenBuiltInBrowser: () -> Unit,
    onClearBrowserData: () -> Unit,
    onDeleteBrowserLogin: (String) -> Unit,
    onListBrowserLogins: () -> List<kira.ditto.browser.SavedBrowserLogin>,
    onSetDefaultRuntime: (LocalRuntimeId) -> Unit,
    onRefreshRootSetup: () -> Unit,
    onStartRootSetupFromSettings: (RootSetupProgressReturnPage) -> Unit,
    onDismissRootSetupProgress: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshAgentModeAuthorization: (Boolean, AgentModeAuthorizationMethod) -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onReplayFollowUpOnboarding: () -> Unit,
    onReplayAlpineSetupPreview: () -> Unit,
    onStopAgentModeDisplay: () -> Unit,
    onRefreshAgentModeDisplays: (AgentModeAuthorizationMethod) -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onForceUpdateCheckForTesting: () -> Unit,
    onSetDeveloperTermuxReadyOverride: (Boolean) -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    // Mutable field values - survive recomposition & config changes
    var systemPromptValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(systemPrompt))
    }
    var tavilyApiKeyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(tavilyApiKey))
    }
    var tavilyBaseUrlValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(tavilyBaseUrl))
    }
    var llmInactivityReconnectTimeoutValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(llmInactivityReconnectTimeoutSeconds.toString()))
    }
    var keepTasksRunningInBackgroundValue by rememberSaveable {
        mutableStateOf(keepTasksRunningInBackground)
    }
    var notifyOnTaskCompletionValue by rememberSaveable {
        mutableStateOf(notifyOnTaskCompletion)
    }
    var autoCleanOldCommandHistoryValue by rememberSaveable {
        mutableStateOf(autoCleanOldCommandHistory)
    }
    var oldCommandHistoryRetentionHoursValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(oldCommandHistoryRetentionHours.toString()))
    }
    // TermuxEnvironmentVariable is not Parcelable; saving this list crashes during state save.
    var termuxEnvironmentVariablesValue by remember {
        mutableStateOf(termuxEnvironmentVariables)
    }
    var agentModeAuthorizationEnabledValue by rememberSaveable {
        mutableStateOf(agentModeAuthorizationEnabled)
    }
    var agentModeAuthorizationMethodValue by rememberSaveable {
        mutableStateOf(agentModeAuthorizationMethod)
    }
    var suppressAgentModePersist by rememberSaveable { mutableStateOf(false) }
    var languageValue by rememberSaveable {
        mutableStateOf(language)
    }
    var themeModeValue by rememberSaveable {
        mutableStateOf(themeMode)
    }
    LaunchedEffect(
        autoCleanOldCommandHistory,
        oldCommandHistoryRetentionHours,
    ) {
        autoCleanOldCommandHistoryValue = autoCleanOldCommandHistory
        oldCommandHistoryRetentionHoursValue = TextFieldValue(oldCommandHistoryRetentionHours.toString())
    }
    LaunchedEffect(language) {
        languageValue = language
    }
    LaunchedEffect(agentModeAuthorizationEnabled) {
        agentModeAuthorizationEnabledValue = agentModeAuthorizationEnabled
        if (agentModeAuthorizationEnabled) {
            suppressAgentModePersist = false
        }
    }
    var defaultChatModelKeyValue by rememberSaveable { mutableStateOf(defaultChatModelKey) }
    var defaultTitleModelKeyValue by rememberSaveable { mutableStateOf(defaultTitleModelKey) }
    var defaultNamingModelKeyValue by rememberSaveable { mutableStateOf(defaultNamingModelKey) }
    var defaultCompactingModelKeyValue by rememberSaveable { mutableStateOf(defaultCompactingModelKey) }
    var defaultVectorModelKeyValue by rememberSaveable { mutableStateOf(defaultVectorModelKey) }
    var defaultImageModelKeyValue by rememberSaveable { mutableStateOf(defaultImageModelKey) }
    var defaultAsrModelKeyValue by rememberSaveable { mutableStateOf(defaultAsrModelKey) }
    var defaultTtsModelKeyValue by rememberSaveable { mutableStateOf(defaultTtsModelKey) }
    var ttsEnabledValue by rememberSaveable { mutableStateOf(ttsEnabled) }
    var ttsVoiceIdValue by rememberSaveable { mutableStateOf(ttsVoiceId) }

    val enabledModelOptions = remember(providerConfigs) { providerConfigs.availableModelOptions() }
    val catalogModelOptions = remember(providerConfigs) {
        providerConfigs.availableModelOptions(includeDisabledModels = true)
    }
    val vectorModelOptions = remember(catalogModelOptions) {
        val embeddings = catalogModelOptions.filter { looksLikeEmbeddingModel(it.modelId) }
        embeddings.ifEmpty { catalogModelOptions }
    }
        val imageModelOptions = remember(catalogModelOptions) {
        val images = catalogModelOptions.filter { looksLikeImageModel(it.modelId) }
        images.ifEmpty { catalogModelOptions }
    }
    val asrModelOptions = remember(catalogModelOptions) {
        catalogModelOptions.filter { looksLikeAsrModel(it.modelId) }
    }
    val ttsModelOptions = remember(catalogModelOptions) {
        catalogModelOptions.filter {
            looksLikeTtsModel(it.modelId) || looksLikeRealtimeModel(it.modelId)
        }
    }

    // Track which provider is being edited
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingMcpServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingScheduledTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleScheduledTasks = remember(scheduledTasks) {
        scheduledTasks.filterNot { it.isGuiWatchTask() }
    }
    LaunchedEffect(Unit) { onRefreshScheduledTasks() }

    fun persistSettings() {
        onSave(
            systemPromptValue.text,
            tavilyApiKeyValue.text,
            normalizeTavilyBaseUrl(tavilyBaseUrlValue.text),
            normalizeLlmInactivityReconnectTimeoutSeconds(
                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
            ),
            keepTasksRunningInBackgroundValue,
            notifyOnTaskCompletionValue,
            autoCleanOldCommandHistoryValue,
            normalizeOldCommandHistoryRetentionHours(
                oldCommandHistoryRetentionHoursValue.text.trim().toIntOrNull()
            ),
            termuxEnvironmentVariablesValue,
            if (suppressAgentModePersist) agentModeAuthorizationEnabled else agentModeAuthorizationEnabledValue,
            AgentModeAuthorizationMethod.Shizuku,
            languageValue,
            themeModeValue,
            defaultChatModelKeyValue,
            defaultTitleModelKeyValue,
            defaultNamingModelKeyValue,
            defaultCompactingModelKeyValue,
            defaultVectorModelKeyValue,
            defaultImageModelKeyValue,
            defaultAsrModelKeyValue,
            defaultTtsModelKeyValue,
            ttsEnabledValue,
            ttsVoiceIdValue,
        )
    }

    fun persistAndExit() {
        persistSettings()
        onBack()
    }

    fun persistAndReplayOnboarding() {
        persistSettings()
        onReplayOnboarding()
    }

    fun persistAndReplayFollowUpOnboarding() {
        persistSettings()
        onReplayFollowUpOnboarding()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        persistSettings()
    }

    // Local page navigation
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Hub.name) }
    val page = SettingsPage.valueOf(currentPage)
    var rootSetupReturnPage by rememberSaveable { mutableStateOf(SettingsPage.Termux.name) }
    var selectedPiPackageSourceValue by rememberSaveable { mutableStateOf("") }
    var selectedExtensionSettingsId by rememberSaveable { mutableStateOf("") }
    var selectedExtensionSettingsCategoryId by rememberSaveable { mutableStateOf("") }
    val extensionSettings = LocalAetherExtensionUiController.current
        ?.snapshot
        ?.settings
        .orEmpty()

    fun rootSetupReturnPageValue(): SettingsPage =
        runCatching { SettingsPage.valueOf(rootSetupReturnPage) }
            .getOrDefault(SettingsPage.Termux)

    fun openRootSetupProgress(returnPage: SettingsPage) {
        rootSetupReturnPage = returnPage.name
        currentPage = SettingsPage.RootSetupProgress.name
        onStartRootSetupFromSettings(returnPage.toRootSetupProgressReturnPage())
    }

    fun closeRootSetupProgress() {
        onDismissRootSetupProgress()
        currentPage = rootSetupReturnPageValue().name
    }

    fun runRootSetupAgain() {
        onStartRootSetupFromSettings(rootSetupReturnPageValue().toRootSetupProgressReturnPage())
    }

    LaunchedEffect(rootSetupProgressReturnPage) {
        val returnPage = rootSetupProgressReturnPage ?: return@LaunchedEffect
        rootSetupReturnPage = returnPage.toSettingsPage().name
        currentPage = SettingsPage.RootSetupProgress.name
    }

    // Determine parent page for back navigation
    fun settingsParentOf(target: SettingsPage): SettingsPage = when (target) {
        SettingsPage.DefaultModels -> SettingsPage.Providers
        SettingsPage.DefaultChatModel,
        SettingsPage.DefaultTitleModel,
        SettingsPage.DefaultNamingModel,
        SettingsPage.DefaultCompactingModel,
        SettingsPage.DefaultVectorModel,
        SettingsPage.DefaultImageModel,
        SettingsPage.DefaultAsrModel -> SettingsPage.DefaultModels
        SettingsPage.DefaultTtsModel -> SettingsPage.DefaultModels
        SettingsPage.AddProvider, SettingsPage.EditProvider -> SettingsPage.Providers
        SettingsPage.AddSkill -> SettingsPage.Skills
        SettingsPage.PackageDetail -> SettingsPage.Extensions
        SettingsPage.ExtensionSettings -> SettingsPage.Hub
        SettingsPage.ExtensionSettingsCategory -> SettingsPage.ExtensionSettings
        SettingsPage.AddMcpServer, SettingsPage.EditMcpServer -> SettingsPage.McpServers
        SettingsPage.AddScheduledTask, SettingsPage.EditScheduledTask -> SettingsPage.ScheduledTasks
        SettingsPage.AlpineTerminal,
        SettingsPage.AlpineFiles -> SettingsPage.Alpine
        SettingsPage.GitHub,
        SettingsPage.PrivacyPolicy -> SettingsPage.About
        SettingsPage.RootSetupProgress -> rootSetupReturnPageValue()
        else -> SettingsPage.Hub
    }

    fun parentPage(): SettingsPage = settingsParentOf(page)

    val pageStateHolder = rememberSaveableStateHolder()
    val renderSettingsPage: @Composable (SettingsPage) -> Unit = { targetPage ->
        pageStateHolder.SaveableStateProvider(targetPage.name) {
            when (targetPage) {
            SettingsPage.Hub -> SettingsHub(
                generalSettingsSummary = settingsGeneralSummary(
                    language = languageValue,
                    themeMode = themeModeValue,
                ),
                activeProviderName = providerConfigs.count { it.isEnabled }.let { enabledCount ->
                    when {
                        enabledCount > 1 -> settingsEnabledProvidersSummary(enabledCount)
                        enabledCount == 1 -> providerConfigs.firstOrNull { it.isEnabled }?.name.orEmpty()
                        enabledModelOptions.isNotEmpty() -> enabledModelOptions.first().fullLabel
                        else -> stringResource(R.string.settings_no_providers_configured)
                    }
                },
                systemPromptSnippet = systemPromptValue.text.take(60),
                tavilyConfigured = tavilyApiKeyValue.text.isNotBlank(),
                reliabilitySummary = buildString {
                    append(
                        stringResource(
                            R.string.settings_reconnect_after_seconds,
                            normalizeLlmInactivityReconnectTimeoutSeconds(
                                llmInactivityReconnectTimeoutValue.text.trim().toIntOrNull()
                            ),
                        )
                    )
                    append(" · ")
                    append(
                        stringResource(
                            if (keepTasksRunningInBackgroundValue) {
                                R.string.settings_background_runs_on
                            } else {
                                R.string.settings_background_runs_off
                            },
                        )
                    )
                },
                termuxReady = termuxSetupState.isReady,
                alpineReady = alpineSetupState.isReady,
                defaultRuntimeId = defaultRuntimeId,
                showRuntimeDefaults = false,
                skillCount = installedSkills.size,
                piExtensionCount = installedPiExtensions.size,
                piExtensionsLoaded = hasLoadedInstalledPiExtensions,
                extensionSettings = extensionSettings,
                onOpenExtensionSettings = { id ->
                    selectedExtensionSettingsId = id
                    selectedExtensionSettingsCategoryId = ""
                    currentPage = SettingsPage.ExtensionSettings.name
                },
                mcpServerCount = mcpServers.size,
                scheduledTaskCount = visibleScheduledTasks.size,
                statisticsSummary = buildSettingsStatisticsSummary(usageStatisticsTotals),
                onReplayOnboarding = ::persistAndReplayOnboarding,
                onNavigate = { page ->
                    currentPage = page.name
                },
                onBack = ::persistAndExit,
            )

            SettingsPage.General -> GeneralSettingsPageV2(
                selectedLanguage = languageValue,
                onLanguageSelected = {
                    languageValue = it
                    onUpdateLanguage(it)
                },
                selectedThemeMode = themeModeValue,
                onThemeModeSelected = {
                    themeModeValue = it
                    onUpdateThemeMode(it)
                },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Providers -> ProvidersListPage(
                providerConfigs = providerConfigs,
                onSetProviderEnabled = onSetProviderEnabled,
                onOpenDefaultModels = { currentPage = SettingsPage.DefaultModels.name },
                onEdit = { id ->
                    editingProviderId = id
                    currentPage = SettingsPage.EditProvider.name
                },
                onRemove = onRemoveProviderConfig,
                onAddNew = { currentPage = SettingsPage.AddProvider.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.DefaultModels -> DefaultModelsPage(
                modelOptions = enabledModelOptions,
                catalogModelOptions = catalogModelOptions,
                defaultChatModelKey = defaultChatModelKeyValue,
                defaultTitleModelKey = defaultTitleModelKeyValue,
                defaultNamingModelKey = defaultNamingModelKeyValue,
                defaultCompactingModelKey = defaultCompactingModelKeyValue,
                defaultVectorModelKey = defaultVectorModelKeyValue,
            defaultImageModelKey = defaultImageModelKeyValue,
            defaultAsrModelKey = defaultAsrModelKeyValue,
            defaultTtsModelKey = defaultTtsModelKeyValue,
            ttsEnabled = ttsEnabledValue,
            ttsVoiceId = ttsVoiceIdValue,
                asrModelOptions = asrModelOptions,
                ttsModelOptions = ttsModelOptions,
                onOpenDefaultChatModel = { currentPage = SettingsPage.DefaultChatModel.name },
                onOpenDefaultTitleModel = { currentPage = SettingsPage.DefaultTitleModel.name },
                onOpenDefaultNamingModel = { currentPage = SettingsPage.DefaultNamingModel.name },
                onOpenDefaultCompactingModel = { currentPage = SettingsPage.DefaultCompactingModel.name },
                onOpenDefaultVectorModel = { currentPage = SettingsPage.DefaultVectorModel.name },
                onOpenDefaultImageModel = { currentPage = SettingsPage.DefaultImageModel.name },
                onOpenDefaultAsrModel = { currentPage = SettingsPage.DefaultAsrModel.name },
                onOpenDefaultTtsModel = { currentPage = SettingsPage.DefaultTtsModel.name },
                onBack = { currentPage = SettingsPage.Providers.name },
            )

            SettingsPage.DefaultChatModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_chat_model),
                subtitle = stringResource(R.string.settings_default_chat_model_subtitle),
                selectedKey = defaultChatModelKeyValue,
                options = enabledModelOptions,
                purpose = AutomaticModelPurpose.Chat,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
                )?.fullLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                    ?: stringResource(R.string.settings_automatic_model),
                automaticSubtitle = stringResource(R.string.settings_prioritize_sota_models),
                onSelected = {
                    defaultChatModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultTitleModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_title_model),
                subtitle = stringResource(R.string.settings_default_title_model_subtitle),
                selectedKey = defaultTitleModelKeyValue,
                options = enabledModelOptions,
                purpose = AutomaticModelPurpose.Title,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Title)
                        .ifBlank { enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
                )?.fullLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                    ?: stringResource(R.string.settings_automatic_model),
                automaticSubtitle = stringResource(R.string.settings_prioritize_sota_models),
                onSelected = {
                    defaultTitleModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultNamingModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_naming_model),
                subtitle = stringResource(R.string.settings_default_naming_model_subtitle),
                selectedKey = defaultNamingModelKeyValue,
                options = enabledModelOptions,
                purpose = AutomaticModelPurpose.Naming,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Naming)
                        .ifBlank { enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
                )?.fullLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                    ?: stringResource(R.string.settings_automatic_model),
                automaticSubtitle = stringResource(R.string.settings_prioritize_sota_models),
                onSelected = {
                    defaultNamingModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultCompactingModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_compacting_model),
                subtitle = stringResource(R.string.settings_default_compacting_model_subtitle),
                selectedKey = defaultCompactingModelKeyValue,
                options = enabledModelOptions,
                purpose = AutomaticModelPurpose.Compacting,
                automaticLabel = enabledModelOptions.findModelOption(
                    enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Compacting)
                        .ifBlank { enabledModelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
                )?.fullLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                    ?: stringResource(R.string.settings_automatic_model),
                automaticSubtitle = stringResource(R.string.settings_prioritize_efficient_summary_models),
                onSelected = {
                    defaultCompactingModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultVectorModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_vector_model),
                subtitle = stringResource(R.string.settings_default_vector_model_subtitle),
                selectedKey = defaultVectorModelKeyValue,
                options = vectorModelOptions,
                purpose = AutomaticModelPurpose.Vector,
                allowAutomatic = false,
                automaticLabel = stringResource(R.string.settings_unselected_model),
                automaticSubtitle = stringResource(R.string.settings_manual_model_required),
                onSelected = {
                    defaultVectorModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultImageModel -> ModelSelectionListPage(
                title = stringResource(R.string.settings_default_image_model),
                subtitle = stringResource(R.string.settings_default_image_model_subtitle),
                selectedKey = defaultImageModelKeyValue,
                options = imageModelOptions,
                purpose = AutomaticModelPurpose.Image,
                allowAutomatic = false,
                automaticLabel = stringResource(R.string.settings_unselected_model),
                automaticSubtitle = stringResource(R.string.settings_manual_model_required),
                onSelected = {
                    defaultImageModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultAsrModel -> AsrModelSelectionListPage(
                selectedKey = defaultAsrModelKeyValue,
                catalogOptions = asrModelOptions,
                onSelected = {
                    defaultAsrModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.DefaultTtsModel -> TtsModelSelectionListPage(
                selectedKey = defaultTtsModelKeyValue,
                catalogOptions = ttsModelOptions,
                ttsEnabled = ttsEnabledValue,
                ttsVoiceId = ttsVoiceIdValue,
                onTtsEnabledChanged = {
                    ttsEnabledValue = it
                    persistSettings()
                },
                onVoiceSelected = {
                    ttsVoiceIdValue = it
                    persistSettings()
                },
                onSelected = {
                    defaultTtsModelKeyValue = it
                    persistSettings()
                },
                onBack = { currentPage = SettingsPage.DefaultModels.name },
            )

            SettingsPage.AddProvider -> ProviderEditPage(
                existingConfig = null,
                existingProviderIds = providerConfigs.map { it.providerId }.toSet(),
                isFetchingModels = isFetchingModels,
                onSave = { config ->
                    onUpsertProviderConfig(config)
                    currentPage = SettingsPage.Providers.name
                },
                onModelEnabledChange = {},
                onFetchModels = onFetchModels,
                authState = providerAuthState,
                onStartProviderLogin = onStartProviderLogin,
                onSubmitAuthPrompt = onSubmitProviderAuthPrompt,
                onClearAuthState = onClearProviderAuthState,
                onBack = { currentPage = SettingsPage.Providers.name },
            )

            SettingsPage.EditProvider -> {
                val configToEdit = providerConfigs.firstOrNull { it.id == editingProviderId }
                ProviderEditPage(
                    existingConfig = configToEdit,
                    existingProviderIds = providerConfigs.map { it.providerId }.toSet(),
                    isFetchingModels = isFetchingModels,
                    onSave = { config ->
                        onUpsertProviderConfig(config)
                        currentPage = SettingsPage.Providers.name
                    },
                    onModelEnabledChange = onUpsertProviderConfig,
                    onFetchModels = onFetchModels,
                    authState = providerAuthState,
                    onStartProviderLogin = onStartProviderLogin,
                    onSubmitAuthPrompt = onSubmitProviderAuthPrompt,
                    onClearAuthState = onClearProviderAuthState,
                    onBack = { currentPage = SettingsPage.Providers.name },
                )
            }

            SettingsPage.Personalization -> PersonalizationPage(
                title = stringResource(R.string.settings_personalization),
                systemPromptValue = systemPromptValue,
                onSystemPromptChanged = { systemPromptValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.WebTools -> WebToolsPage(
                title = stringResource(R.string.settings_web_tools),
                tavilyApiKeyValue = tavilyApiKeyValue,
                onTavilyApiKeyChanged = { tavilyApiKeyValue = it },
                tavilyBaseUrlValue = tavilyBaseUrlValue,
                onTavilyBaseUrlChanged = { tavilyBaseUrlValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Reliability -> ReliabilityPage(
                title = stringResource(R.string.settings_reliability),
                llmInactivityReconnectTimeoutValue = llmInactivityReconnectTimeoutValue,
                onLlmInactivityReconnectTimeoutChanged = { llmInactivityReconnectTimeoutValue = it },
                keepTasksRunningInBackground = keepTasksRunningInBackgroundValue,
                onKeepTasksRunningInBackgroundChanged = { keepTasksRunningInBackgroundValue = it },
                notifyOnTaskCompletion = notifyOnTaskCompletionValue,
                onNotifyOnTaskCompletionChanged = { notifyOnTaskCompletionValue = it },
                onBack = { currentPage = SettingsPage.Hub.name },
            )
            SettingsPage.ExtensionSettings -> {
                val selected = extensionSettings.firstOrNull { it.id == selectedExtensionSettingsId }
                if (selected == null) {
                    currentPage = SettingsPage.Hub.name
                } else if (selected.categories.isNotEmpty()) {
                    AetherExtensionSettingsCategoriesPage(
                        page = selected,
                        onCategorySelected = { categoryId ->
                            selectedExtensionSettingsCategoryId = categoryId
                            currentPage = SettingsPage.ExtensionSettingsCategory.name
                        },
                        onBack = { currentPage = SettingsPage.Hub.name },
                    )
                } else {
                    AetherExtensionSettingsPage(
                        page = selected,
                        category = null,
                        onBack = { currentPage = SettingsPage.Hub.name },
                    )
                }
            }

            SettingsPage.ExtensionSettingsCategory -> {
                val selectedPage = extensionSettings.firstOrNull { it.id == selectedExtensionSettingsId }
                val selectedCategory = selectedPage?.categories?.firstOrNull { it.id == selectedExtensionSettingsCategoryId }
                if (selectedPage == null || selectedCategory == null) {
                    currentPage = SettingsPage.ExtensionSettings.name
                } else {
                    AetherExtensionSettingsPage(
                        page = selectedPage,
                        category = selectedCategory,
                        onCategorySelected = { categoryId ->
                            selectedExtensionSettingsCategoryId = categoryId
                            currentPage = SettingsPage.ExtensionSettingsCategory.name
                        },
                        onBack = { currentPage = SettingsPage.ExtensionSettings.name },
                    )
                }
            }

            SettingsPage.Skills -> SkillsListPage(
                title = stringResource(R.string.settings_agent_skills),
                installedSkills = installedSkills,
                onToggleSkillEnabled = onToggleSkillEnabled,
                onRemoveSkill = onRemoveSkill,
                onAddNew = { currentPage = SettingsPage.AddSkill.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AddSkill -> AddSkillPage(
                title = stringResource(R.string.settings_agent_skills),
                onImportSkillFolder = onImportSkillFolder,
                onImportSkillZip = { callback ->
                    onImportSkillZip { success ->
                        callback(success)
                        if (success) currentPage = SettingsPage.Skills.name
                    }
                },
                onInstallSkillUrl = { url, callback ->
                    onInstallSkillUrl(url) { success ->
                        callback(success)
                        if (success) currentPage = SettingsPage.Skills.name
                    }
                },
                onBack = { currentPage = SettingsPage.Skills.name },
            )

            SettingsPage.Extensions -> PiExtensionsPage(
                installedExtensions = installedPiExtensions,
                hasLoadedInstalledExtensions = hasLoadedInstalledPiExtensions,
                nativeModState = nativeModState,
                catalog = piExtensionCatalog,
                isLoading = isLoadingPiExtensions,
                catalogError = piExtensionCatalogError,
                operationSource = piExtensionOperationSource,
                onRefresh = onRefreshPiExtensions,
                onUpdate = onUpdatePiExtensionPackage,
                onRemove = onRemovePiExtension,
                onSetEnabled = onSetPiExtensionEnabled,
                onImport = onImportPiExtension,
                onAllowNativeModsOnNextStart = onAllowNativeModsOnNextStart,
                onDisableNativeModsOnNextStart = onDisableNativeModsOnNextStart,
                onSelectPackage = { entry ->
                    selectedPiPackageSourceValue = entry.source
                    onLoadPiPackageDetails(entry)
                    currentPage = SettingsPage.PackageDetail.name
                },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.PackageDetail -> {
                val entry = piExtensionCatalog.firstOrNull {
                    it.source == selectedPiPackageSourceValue
                }
                if (entry == null) {
                    LaunchedEffect(Unit) {
                        currentPage = SettingsPage.Extensions.name
                    }
                } else {
                    LaunchedEffect(entry.source) {
                        if (
                            selectedPiPackageSource != entry.source ||
                            (
                                selectedPiPackageDetails == null &&
                                    !isLoadingPiPackageDetails &&
                                    piPackageDetailsError.isBlank()
                                )
                        ) {
                            onLoadPiPackageDetails(entry)
                        }
                    }
                    PiPackageDetailPage(
                        entry = entry,
                        details = selectedPiPackageDetails
                            ?.takeIf { selectedPiPackageSource == entry.source },
                        installed = installedPiExtensions.any {
                            it.kind == PiExtensionInstallKind.Package && it.source == entry.source
                        },
                        isLoading = isLoadingPiPackageDetails,
                        error = piPackageDetailsError,
                        isOperating = piExtensionOperationSource == entry.source,
                        onRetry = { onLoadPiPackageDetails(entry) },
                        onInstall = { onInstallPiExtensionPackage(entry.source) },
                        onBack = { currentPage = SettingsPage.Extensions.name },
                    )
                }
            }

            SettingsPage.McpServers -> McpServersListPage(
                title = stringResource(R.string.settings_mcp_servers),
                mcpServers = mcpServers,
                onToggleMcpServerEnabled = onToggleMcpServerEnabled,
                onRemoveMcpServer = onRemoveMcpServer,
                onTestMcpServer = onTestMcpServer,
                onEdit = { serverId ->
                    editingMcpServerId = serverId
                    currentPage = SettingsPage.EditMcpServer.name
                },
                onAddNew = { currentPage = SettingsPage.AddMcpServer.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AddMcpServer -> AddMcpServerPage(
                title = stringResource(R.string.settings_mcp_servers),
                existingServer = null,
                onSaveHttpMcpServer = { serverId, name, url, headers ->
                    onSaveHttpMcpServer(serverId, name, url, headers)
                    currentPage = SettingsPage.McpServers.name
                },
                onSaveStdIoMcpServer = { serverId, name, cmd, args, wd, env, runtimeEnvironment ->
                    onSaveStdIoMcpServer(serverId, name, cmd, args, wd, env, runtimeEnvironment)
                    currentPage = SettingsPage.McpServers.name
                },
                onBack = { currentPage = SettingsPage.McpServers.name },
            )

            SettingsPage.EditMcpServer -> AddMcpServerPage(
                title = stringResource(R.string.settings_mcp_servers),
                existingServer = mcpServers.firstOrNull { it.id == editingMcpServerId },
                onSaveHttpMcpServer = { serverId, name, url, headers ->
                    onSaveHttpMcpServer(serverId, name, url, headers)
                    currentPage = SettingsPage.McpServers.name
                },
                onSaveStdIoMcpServer = { serverId, name, cmd, args, wd, env, runtimeEnvironment ->
                    onSaveStdIoMcpServer(serverId, name, cmd, args, wd, env, runtimeEnvironment)
                    currentPage = SettingsPage.McpServers.name
                },
                onBack = { currentPage = SettingsPage.McpServers.name },
            )

            SettingsPage.ScheduledTasks -> ScheduledTasksPage(
                tasks = visibleScheduledTasks,
                onToggleEnabled = onToggleScheduledTaskEnabled,
                onRemove = onRemoveScheduledTask,
                onRefresh = onRefreshScheduledTasks,
                onEdit = { taskId ->
                    editingScheduledTaskId = taskId
                    currentPage = SettingsPage.EditScheduledTask.name
                },
                onAddNew = { currentPage = SettingsPage.AddScheduledTask.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AddScheduledTask -> ScheduledTaskEditPage(
                existingTask = null,
                onSave = { name, prompt, schedule, enabled ->
                    onSaveScheduledTask(null, name, prompt, schedule, enabled)
                    currentPage = SettingsPage.ScheduledTasks.name
                },
                onBack = { currentPage = SettingsPage.ScheduledTasks.name },
            )

            SettingsPage.EditScheduledTask -> ScheduledTaskEditPage(
                existingTask = visibleScheduledTasks.firstOrNull { it.id == editingScheduledTaskId },
                onSave = { name, prompt, schedule, enabled ->
                    onSaveScheduledTask(editingScheduledTaskId, name, prompt, schedule, enabled)
                    currentPage = SettingsPage.ScheduledTasks.name
                },
                onBack = { currentPage = SettingsPage.ScheduledTasks.name },
            )

            SettingsPage.Termux -> TermuxSettingsPage(
                title = stringResource(R.string.settings_termux),
                termuxSetupState = termuxSetupState,
                rootSetupState = rootSetupState,
                environmentVariables = termuxEnvironmentVariablesValue,
                onEnvironmentVariablesChanged = { termuxEnvironmentVariablesValue = it },
                onRequestTermuxPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefreshTermuxSetup = onRefreshTermuxSetup,
                onRefreshRootSetup = onRefreshRootSetup,
                onConfigureWithRoot = { openRootSetupProgress(SettingsPage.Termux) },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Alpine -> AlpineSettingsPage(
                title = "Alpine",
                setupState = alpineSetupState,
                packageProfiles = alpinePackageProfiles,
                installProgress = alpinePackageInstallProgress,
                isDefaultRuntime = defaultRuntimeId == LocalRuntimeId.Alpine,
                onInitialize = onInitializeAlpineRuntime,
                onReset = onResetAlpineRuntime,
                onRefresh = onRefreshAlpineSetup,
                onInstallPackageProfile = onInstallAlpinePackageProfile,
                onSetDefault = { onSetDefaultRuntime(LocalRuntimeId.Alpine) },
                onOpenTerminal = { currentPage = SettingsPage.AlpineTerminal.name },
                onOpenFiles = { currentPage = SettingsPage.AlpineFiles.name },
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AlpineTerminal -> AlpineTerminalScreen(
                createLaunchSpec = onCreateAlpineTerminalLaunchSpec,
                onBack = { currentPage = SettingsPage.Alpine.name },
            )

            SettingsPage.AlpineFiles -> {
                AndroidAlpineFileManagerScreen(
                    runtime = alpineFileManagerRuntime,
                    onBack = { currentPage = SettingsPage.Alpine.name },
                )
            }

            SettingsPage.Browser -> BrowserSettingsPage(
                title = stringResource(R.string.settings_browser),
                preferences = browserPreferences,
                onUpdate = onUpdateBrowserPreferences,
                onOpenBrowser = onOpenBuiltInBrowser,
                onClearData = onClearBrowserData,
                onDeleteLogin = onDeleteBrowserLogin,
                onListLogins = onListBrowserLogins,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.RuntimeDefaults -> RuntimeDefaultsPage(
                title = stringResource(R.string.settings_runtime_defaults),
                termuxReady = termuxSetupState.isReady,
                alpineReady = alpineSetupState.isReady,
                enabledRuntimeIds = enabledRuntimeIds,
                defaultRuntimeId = defaultRuntimeId,
                onSetDefaultRuntime = onSetDefaultRuntime,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.AgentMode -> AgentModeSettingsPage(
                title = stringResource(R.string.settings_agent_mode),
                termuxSetupState = termuxSetupState,
                agentModeAuthorizationEnabled = agentModeAuthorizationEnabledValue,
                agentModeAuthorizationMethod = agentModeAuthorizationMethodValue,
                agentModeAuthorizationState = agentModeAuthorizationState,
                rootSetupState = rootSetupState,
                onAgentModeAuthorizationEnabledChanged = { enabled ->
                    if (enabled) {
                        suppressAgentModePersist = true
                        agentModeAuthorizationMethodValue = AgentModeAuthorizationMethod.Shizuku
                        onInstallShizuku()
                    } else {
                        suppressAgentModePersist = false
                        agentModeAuthorizationEnabledValue = enabled
                        persistSettings()
                    }
                },
                onAgentModeAuthorizationMethodChanged = { agentModeAuthorizationMethodValue = it },
                agentModeDisplayState = agentModeDisplayState,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onRefreshAgentModeAuthorization = onRefreshAgentModeAuthorization,
                onOpenShizuku = onOpenShizuku,
                onInstallShizuku = onInstallShizuku,
                onRequestTermuxPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefreshTermuxSetup = onRefreshTermuxSetup,
                onRefreshRootSetup = onRefreshRootSetup,
                onConfigureWithRoot = { openRootSetupProgress(SettingsPage.AgentMode) },
                onStopAgentModeDisplay = onStopAgentModeDisplay,
                onRefreshAgentModeDisplays = onRefreshAgentModeDisplays,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Statistics -> StatisticsSettingsPage(
                title = stringResource(R.string.settings_statistics),
                usageStatisticsSnapshots = usageStatisticsSnapshots,
                usageStatisticsTotals = usageStatisticsTotals,
                isRefreshing = usageStatisticsRefreshing,
                onRefresh = onRefreshUsageStatistics,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.Memory -> MemorySettingsPage(
                pages = memoryPages,
                onRefresh = onRefreshMemoryPages,
                onDelete = onDeleteMemoryPage,
                onClearAll = onClearMemoryPages,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.CacheAndData -> CacheAndDataSettingsPage(
                usage = storageUsage,
                busy = storageBusy,
                autoCleanOldCommandHistory = autoCleanOldCommandHistoryValue,
                oldCommandHistoryRetentionHours = oldCommandHistoryRetentionHoursValue,
                onAutoCleanOldCommandHistoryChanged = { autoCleanOldCommandHistoryValue = it },
                onOldCommandHistoryRetentionHoursChanged = { oldCommandHistoryRetentionHoursValue = it },
                onRefresh = onRefreshStorageUsage,
                onClear = onClearAppStorage,
                onUpdateCacheCleanupPolicy = onUpdateCacheCleanupPolicy,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.RootSetupProgress -> RootSetupProgressPage(
                rootSetupState = rootSetupState,
                termuxSetupState = termuxSetupState,
                agentModeAuthorizationEnabled = agentModeAuthorizationEnabledValue,
                agentModeAuthorizationMethod = agentModeAuthorizationMethodValue,
                agentModeAuthorizationState = agentModeAuthorizationState,
                onRunRootSetup = ::runRootSetupAgain,
                onBack = ::closeRootSetupProgress,
            )

            SettingsPage.Developer -> DeveloperSettingsPage(
                title = stringResource(R.string.settings_developer),
                onReplayFollowUpOnboarding = ::persistAndReplayFollowUpOnboarding,
                onReplayAlpineSetupPreview = onReplayAlpineSetupPreview,
                onImportAppData = onImportAppData,
                onExportAppData = onExportAppData,
                onExportLogs = onExportLogs,
                onForceUpdateCheckForTesting = onForceUpdateCheckForTesting,
                autoCleanOldCommandHistory = autoCleanOldCommandHistoryValue,
                oldCommandHistoryRetentionHours = oldCommandHistoryRetentionHoursValue,
                onAutoCleanOldCommandHistoryChanged = { autoCleanOldCommandHistoryValue = it },
                onOldCommandHistoryRetentionHoursChanged = { oldCommandHistoryRetentionHoursValue = it },
                termuxReadyForTesting = developerTermuxReadyOverride ?: termuxSetupState.isReady,
                onTermuxReadyForTestingChanged = onSetDeveloperTermuxReadyOverride,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.About -> AboutPage(
                title = stringResource(R.string.settings_about),
                appUpdate = appUpdate,
                onOpenGitHub = { currentPage = SettingsPage.GitHub.name },
                onOpenPrivacyPolicy = { currentPage = SettingsPage.PrivacyPolicy.name },
                onCheckForUpdates = onCheckForUpdates,
                onDownloadAndInstallUpdate = onDownloadAndInstallUpdate,
                onBack = { currentPage = SettingsPage.Hub.name },
            )

            SettingsPage.GitHub -> GitHubLinksPage(
                title = stringResource(R.string.settings_github),
                onBack = { currentPage = SettingsPage.About.name },
            )

            SettingsPage.PrivacyPolicy -> PrivacyPolicyPlaceholderPage(
                title = stringResource(R.string.settings_privacy_policy),
                onBack = { currentPage = SettingsPage.About.name },
            )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        PredictiveBackHost(
            onBack = {
                when (page) {
                    SettingsPage.Hub -> persistAndExit()
                    SettingsPage.RootSetupProgress -> closeRootSetupProgress()
                    else -> currentPage = parentPage().name
                }
            },
        ) {
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    pageStackContentTransform(isForward = settingsParentOf(targetState) == initialState)
                },
                label = "settings_page_stack",
            ) { targetPage ->
                Box(pageStackGpuLayer()) {
                    renderSettingsPage(targetPage)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Hub
// -----------------------------------------------------------------------------

@Composable
private fun SettingsHub(
    generalSettingsSummary: String,
    activeProviderName: String,
    systemPromptSnippet: String,
    tavilyConfigured: Boolean,
    reliabilitySummary: String,
    termuxReady: Boolean,
    alpineReady: Boolean,
    defaultRuntimeId: LocalRuntimeId?,
    showRuntimeDefaults: Boolean,
    skillCount: Int,
    piExtensionCount: Int,
    piExtensionsLoaded: Boolean,
    extensionSettings: List<kira.ditto.data.AetherAppExtensionSettingsPage>,
    onOpenExtensionSettings: (String) -> Unit,
    mcpServerCount: Int,
    scheduledTaskCount: Int,
    statisticsSummary: String,
    onReplayOnboarding: () -> Unit,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherSettingsBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarBodyHeight)
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Spacer(Modifier.height(6.dp))
                AetherExtensionSlot(AetherExtensionSlotSettingsHub)
                if (
                    LocalAetherExtensionUiController.current
                        ?.snapshot
                        ?.surfacesAt(AetherExtensionSlotSettingsHub)
                        .orEmpty()
                        .isNotEmpty()
                ) {
                    Spacer(Modifier.height(16.dp))
                }
                SettingsCardGroup {
                    SettingsNavRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.settings_general),
                        subtitle = generalSettingsSummary.ifBlank { stringResource(R.string.settings_general_hint) },
                        onClick = { onNavigate(SettingsPage.General) },
                    )
                }

                Spacer(Modifier.height(16.dp))

            // Configuration card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(R.string.settings_model_providers),
                    subtitle = activeProviderName,
                    onClick = { onNavigate(SettingsPage.Providers) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.settings_personalization),
                    subtitle = systemPromptSnippet.ifBlank { stringResource(R.string.settings_custom_instructions) },
                    onClick = { onNavigate(SettingsPage.Personalization) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.settings_reliability),
                    subtitle = reliabilitySummary,
                    onClick = { onNavigate(SettingsPage.Reliability) },
                )
            }

            if (extensionSettings.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SettingsCardGroup {
                    extensionSettings.forEachIndexed { index, settingPage ->
                        if (index > 0) CardDivider()
                        SettingsNavRow(
                            icon = extensionIcon(settingPage.icon),
                            title = settingPage.title,
                            subtitle = settingPage.subtitle.ifBlank { settingPage.extensionName },
                            onClick = { onOpenExtensionSettings(settingPage.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Extensions card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Extension,
                    title = stringResource(R.string.settings_agent_skills),
                    subtitle = stringResource(R.string.settings_skills_count_configured, skillCount),
                    onClick = { onNavigate(SettingsPage.Skills) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = LucideIcons.Plug,
                    title = stringResource(R.string.settings_mcp_servers),
                    subtitle = stringResource(R.string.settings_mcp_server_count_summary, mcpServerCount),
                    onClick = { onNavigate(SettingsPage.McpServers) },
                )
                CardDivider()
                SettingsNavRow(
                    iconPainter = painterResource(R.drawable.pi_logo_on_light),
                    title = stringResource(R.string.settings_pi_extensions),
                    subtitle = if (piExtensionsLoaded) {
                        stringResource(
                            R.string.settings_pi_extensions_count_configured,
                            piExtensionCount,
                        )
                    } else {
                        stringResource(R.string.settings_loading_installed_extensions)
                    },
                    onClick = { onNavigate(SettingsPage.Extensions) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.settings_scheduled_tasks),
                    subtitle = stringResource(R.string.settings_scheduled_tasks_count_configured, scheduledTaskCount),
                    onClick = { onNavigate(SettingsPage.ScheduledTasks) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Code,
                    title = "Alpine",
                    subtitle = if (alpineReady) { stringResource(R.string.settings_alpine_subtitle_ready) } else { stringResource(R.string.settings_alpine_subtitle_setup) },
                    onClick = { onNavigate(SettingsPage.Alpine) },
                )
                if (showRuntimeDefaults) {
                    CardDivider()
                    SettingsNavRow(
                        icon = Icons.Rounded.Check,
                        title = stringResource(R.string.settings_runtime_defaults),
                        subtitle = defaultRuntimeId?.displayName ?: stringResource(R.string.settings_runtime_defaults_hint),
                        onClick = { onNavigate(SettingsPage.RuntimeDefaults) },
                    )
                }
                CardDivider()
                SettingsNavRow(
                    icon = LucideIcons.MousePointer2,
                    title = stringResource(R.string.settings_agent_mode),
                    subtitle = stringResource(R.string.settings_agent_mode_subtitle),
                    onClick = { onNavigate(SettingsPage.AgentMode) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.settings_browser),
                    subtitle = stringResource(R.string.settings_browser_subtitle),
                    onClick = { onNavigate(SettingsPage.Browser) },
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsCardGroup {
                SettingsNavRow(
                    icon = LucideIcons.ChartNoAxesColumn,
                    title = stringResource(R.string.settings_statistics),
                    subtitle = statisticsSummary.ifBlank { stringResource(R.string.settings_statistics_empty) },
                    onClick = { onNavigate(SettingsPage.Statistics) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.settings_memory),
                    subtitle = stringResource(R.string.settings_memory_subtitle),
                    onClick = { onNavigate(SettingsPage.Memory) },
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.settings_cache_and_data),
                    subtitle = stringResource(R.string.settings_cache_and_data_subtitle),
                    onClick = { onNavigate(SettingsPage.CacheAndData) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // About card
            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.settings_get_started_tour),
                    subtitle = stringResource(R.string.settings_get_started_tour_subtitle),
                    onClick = onReplayOnboarding,
                )
                CardDivider()
                SettingsNavRow(
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.settings_developer),
                    subtitle = stringResource(R.string.settings_developer_subtitle),
                    onClick = { onNavigate(SettingsPage.Developer) },
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsCardGroup {
                SettingsNavRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_about),
                    subtitle = settingsReleaseSummary(BuildConfig.VERSION_NAME),
                    onClick = { onNavigate(SettingsPage.About) },
                )
            }

            Spacer(Modifier.height(32.dp))
            }

            SettingsTopBarOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                onBodyHeightChanged = { topBarBodyHeightPx = it },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Cache and data
// -----------------------------------------------------------------------------

@Composable
private fun CacheAndDataSettingsPage(
    usage: AppStorageUsage,
    busy: Boolean,
    autoCleanOldCommandHistory: Boolean,
    oldCommandHistoryRetentionHours: TextFieldValue,
    onAutoCleanOldCommandHistoryChanged: (Boolean) -> Unit,
    onOldCommandHistoryRetentionHoursChanged: (TextFieldValue) -> Unit,
    onRefresh: () -> Unit,
    onClear: (AppStorageClearKind) -> Unit,
    onUpdateCacheCleanupPolicy: (Boolean, Int) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var autoClean by rememberSaveable(usage.autoCleanEnabled) { mutableStateOf(usage.autoCleanEnabled) }
    var retentionDays by rememberSaveable(usage.retentionDays, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(usage.retentionDays.toString()))
    }
    var pendingClear by remember { mutableStateOf<AppStorageClearKind?>(null) }

    SubPageScaffold(title = stringResource(R.string.settings_cache_and_data), onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_cache_and_data_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_cache_usage_total, formatStorageBytes(usage.managedBytes)),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                CacheUsageRow(stringResource(R.string.settings_cache_usage_evidence), usage.evidenceBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_images), usage.markdownImageBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_browser), usage.geckoCacheBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_history), usage.browserHistoryBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_app), usage.appCacheBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_commands), usage.commandHistoryBytes)
                CacheUsageRow(stringResource(R.string.settings_cache_usage_chat), usage.chatDataBytes)
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_cache_auto_clean),
                    subtitle = stringResource(R.string.settings_cache_auto_clean_subtitle),
                    checked = autoClean,
                    onCheckedChange = { checked ->
                        autoClean = checked
                        onUpdateCacheCleanupPolicy(
                            checked,
                            retentionDays.text.toIntOrNull() ?: DefaultStaleCacheRetentionDays,
                        )
                    },
                )
                AnimatedVisibility(visible = autoClean) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        ChatGptTextField(
                            value = retentionDays,
                            onValueChange = { value ->
                                val filtered = value.copy(text = value.text.filter(Char::isDigit).take(3))
                                retentionDays = filtered
                                filtered.text.toIntOrNull()?.let { days ->
                                    onUpdateCacheCleanupPolicy(autoClean, days)
                                }
                            },
                            label = stringResource(R.string.settings_cache_retention_days),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        RuntimeCleanupDeveloperSettingsSection(
            autoCleanOldCommandHistory = autoCleanOldCommandHistory,
            oldCommandHistoryRetentionHours = oldCommandHistoryRetentionHours,
            onAutoCleanOldCommandHistoryChanged = onAutoCleanOldCommandHistoryChanged,
            onOldCommandHistoryRetentionHoursChanged = onOldCommandHistoryRetentionHoursChanged,
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_evidence),
                    onClick = { pendingClear = AppStorageClearKind.Evidence },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_images),
                    onClick = { pendingClear = AppStorageClearKind.MarkdownImages },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_browser),
                    onClick = { pendingClear = AppStorageClearKind.BrowserCache },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_history),
                    onClick = { pendingClear = AppStorageClearKind.BrowserHistory },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_app),
                    onClick = { pendingClear = AppStorageClearKind.AppCache },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_cache_clear_commands),
                    onClick = { pendingClear = AppStorageClearKind.CommandHistory },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    pendingClear?.let { kind ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(stringResource(R.string.settings_cache_confirm_title)) },
            text = { Text(stringResource(R.string.settings_cache_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear(kind)
                        pendingClear = null
                    },
                ) { Text(stringResource(R.string.settings_cache_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CacheUsageRow(label: String, bytes: Long) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
        Text(formatStorageBytes(bytes), style = MaterialTheme.typography.bodySmall, color = AetherOnSurface)
    }
}

// -----------------------------------------------------------------------------
// Statistics
// -----------------------------------------------------------------------------

@Composable
private fun StatisticsSettingsPage(
    title: String,
    usageStatisticsSnapshots: List<ChatUsageStatisticsSnapshot>,
    usageStatisticsTotals: LlmUsageTotals,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    val report = remember(usageStatisticsSnapshots, usageStatisticsTotals) {
        buildUsageStatisticsReport(usageStatisticsSnapshots, usageStatisticsTotals)
    }
    SubPageScaffold(title = title, onBack = onBack) {
        StatisticsRefreshProgress(visible = isRefreshing)
        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.statistics_overview),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatisticsMetricTile(
                        label = stringResource(R.string.statistics_total_tokens),
                        value = formatSettingsTokenCount(report.totalTokens),
                        modifier = Modifier.weight(1f),
                    )
                    StatisticsMetricTile(
                        label = stringResource(R.string.statistics_cache_hit),
                        value = report.cacheHitPercent?.let { "$it%" }
                            ?: stringResource(R.string.statistics_unavailable),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatisticsMetricTile(
                        label = stringResource(R.string.statistics_average_speed),
                        value = report.averageOutputTokensPerSecond?.let(::formatSettingsTokenRate)
                            ?: stringResource(R.string.statistics_unavailable),
                        modifier = Modifier.weight(1f),
                    )
                    StatisticsMetricTile(
                        label = stringResource(R.string.statistics_average_latency),
                        value = report.averageFirstTokenLatencyMillis?.let(::formatSettingsDuration)
                            ?: stringResource(R.string.statistics_unavailable),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            StatisticsChartSection(
                title = stringResource(R.string.statistics_daily_token_usage),
                subtitle = stringResource(R.string.statistics_recent_7_days),
            ) {
                TokenBarChart(points = report.dailyTokenUsage.takeLast(7))
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            StatisticsChartSection(
                title = stringResource(R.string.statistics_recent_token_usage),
                subtitle = stringResource(R.string.statistics_recent_14_days),
            ) {
                TokenLineChart(points = report.dailyTokenUsage.takeLast(14))
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.statistics_historical_token_usage),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                HistoryPeakRow(
                    label = stringResource(R.string.statistics_peak_day),
                    value = report.peakDay?.let { "${it.label} · ${formatSettingsTokenCount(it.tokens)}" }
                        ?: stringResource(R.string.statistics_unavailable),
                )
                HistoryPeakRow(
                    label = stringResource(R.string.statistics_largest_turn),
                    value = report.largestTurnTokens?.let(::formatSettingsTokenCount)
                        ?: stringResource(R.string.statistics_unavailable),
                )
                HistoryPeakRow(
                    label = stringResource(R.string.statistics_recorded_turns),
                    value = report.turnCount.toString(),
                )
            }
        }

        if (report.tokensBySource.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.statistics_by_source),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    report.tokensBySource.forEach { item ->
                        HistoryPeakRow(
                            label = usageSourceLabel(item.key),
                            value = formatSettingsTokenCount(item.tokens),
                        )
                    }
                }
            }
        }

        if (report.tokensByModel.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.statistics_by_model),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    report.tokensByModel.forEach { item ->
                        HistoryPeakRow(
                            label = item.key.ifBlank { stringResource(R.string.statistics_unknown_model) },
                            value = formatSettingsTokenCount(item.tokens),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.statistics_token_mix),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    TokenMixLegend(
                        stringResource(R.string.statistics_input),
                        StatisticsInputColor,
                        report.inputTokens + report.cachedInputTokens,
                    )
                    TokenMixLegend(stringResource(R.string.statistics_output), StatisticsOutputColor, report.outputTokens)
                    TokenMixLegend(stringResource(R.string.statistics_reasoning), StatisticsReasoningColor, report.reasoningTokens)
                }
                TokenMixPieChart(
                    inputTokens = report.inputTokens + report.cachedInputTokens,
                    outputTokens = report.outputTokens,
                    reasoningTokens = report.reasoningTokens,
                    modifier = Modifier.size(72.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            StatisticsChartSection(
                title = stringResource(R.string.statistics_speed),
                subtitle = stringResource(R.string.statistics_speed_subtitle),
            ) {
                SpeedBarChart(points = report.recentSpeedSamples.takeLast(12))
            }
        }
    }
}

@Composable
private fun StatisticsRefreshProgress(visible: Boolean) {
    var organicProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(visible) {
        if (!visible) {
            organicProgress = 0f
            return@LaunchedEffect
        }
        organicProgress = 0.16f
        while (true) {
            delay(90L)
            val remaining = 0.92f - organicProgress
            if (remaining > 0f) {
                organicProgress = (organicProgress + remaining * 0.08f).coerceAtMost(0.92f)
            }
        }
    }
    val animatedProgress by animateFloatAsState(
        targetValue = organicProgress,
        animationSpec = tween(durationMillis = 700, easing = PageTransitionEasing),
        label = "statistics_refresh_progress",
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 200, easing = PageTransitionEasing),
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 200, easing = PageTransitionEasing),
        ),
    ) {
        Column {
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                color = AlpineIconBlue,
                trackColor = AetherOutlineSoft,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatisticsMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatisticsChartSection(
    title: String,
    subtitle: String,
    chart: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        chart()
    }
}

@Composable
private fun TokenBarChart(points: List<DailyTokenUsage>) {
    val maxTokens = points.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { point ->
            val fraction = point.tokens.toFloat() / maxTokens
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = formatSettingsTokenCount(point.tokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((18 + 96 * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(AetherPrimary.copy(alpha = 0.18f + 0.44f * fraction)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = point.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TokenLineChart(points: List<DailyTokenUsage>) {
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selectedPoint = selectedIndex?.let(points::getOrNull)
    val lineColor = StatisticsInputColor
    val fillColor = StatisticsInputColor.copy(alpha = 0.12f)
    val labelColor = AetherOnSurfaceVariant
    val maxTokens = points.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurfaceHigh)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        if (points.isEmpty()) return@detectTapGestures
                        val horizontalPadding = 24f
                        val chartWidth = (size.width - horizontalPadding * 2f).coerceAtLeast(1f)
                        val progress = ((offset.x - horizontalPadding) / chartWidth).coerceIn(0f, 1f)
                        selectedIndex = (progress * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            val chartPadding = 12.dp
            val chartHeight = 126.dp
            val chartWidth = maxWidth - chartPadding * 2
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                if (points.isEmpty()) return@Canvas
                val step = if (points.size <= 1) 0f else size.width / (points.size - 1)
                val coordinates = points.mapIndexed { index, point ->
                    val x = if (points.size <= 1) size.width / 2f else step * index
                    val y = size.height - (point.tokens.toFloat() / maxTokens) * size.height
                    androidx.compose.ui.geometry.Offset(x, y)
                }
                for (index in 0 until coordinates.lastIndex) {
                    drawLine(
                        color = lineColor,
                        start = coordinates[index],
                        end = coordinates[index + 1],
                        strokeWidth = 5f,
                        cap = StrokeCap.Round,
                    )
                }
                coordinates.forEachIndexed { index, point ->
                    val selected = selectedIndex == index
                    drawCircle(color = fillColor, radius = if (selected) 17f else 12f, center = point)
                    drawCircle(color = if (selected) StatisticsOutputColor else lineColor, radius = if (selected) 7f else 5f, center = point)
                }
            }
            selectedPoint?.let { point ->
                val selectedX = if (points.size <= 1) {
                    maxWidth / 2
                } else {
                    chartPadding + chartWidth * (selectedIndex!!.toFloat() / points.lastIndex)
                }
                val selectedY = chartPadding + chartHeight * (1f - point.tokens.toFloat() / maxTokens)
                val tooltipWidth = 116.dp
                val tooltipX = (selectedX - tooltipWidth / 2)
                    .coerceIn(4.dp, (maxWidth - tooltipWidth - 4.dp).coerceAtLeast(4.dp))
                val tooltipY = (selectedY - 34.dp).coerceAtLeast(4.dp)
                Box(
                    modifier = Modifier
                        .offset(x = tooltipX, y = tooltipY)
                        .width(tooltipWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AetherSurface.copy(alpha = 0.96f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            R.string.statistics_selected_day_tokens,
                            point.label,
                            formatSettingsTokenCount(point.tokens),
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = AetherOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            points.firstOrNull()?.let {
                Text(it.shortLabel, style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
            points.lastOrNull()?.let {
                Text(it.shortLabel, style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}

@Composable
private fun TokenMixPieChart(
    inputTokens: Long,
    outputTokens: Long,
    reasoningTokens: Long,
    modifier: Modifier = Modifier,
) {
    val values = listOf(inputTokens, outputTokens, reasoningTokens)
    val colors = listOf(
        StatisticsInputColor,
        StatisticsOutputColor,
        StatisticsReasoningColor,
    )
    val total = values.sum()
    Canvas(modifier = modifier) {
        if (total <= 0L) {
            drawArc(
                color = StatisticsNeutralChartColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = size.minDimension * 0.20f, cap = StrokeCap.Butt),
            )
            return@Canvas
        }
        var startAngle = -90f
        values.forEachIndexed { index, value ->
            if (value <= 0L) return@forEachIndexed
            val sweep = 360f * value / total
            drawArc(
                color = colors[index],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = size.minDimension * 0.20f, cap = StrokeCap.Butt),
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun SpeedBarChart(points: List<SpeedSample>) {
    val visiblePoints = points.takeLast(7)
    if (visiblePoints.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.statistics_no_speed_samples),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }
        return
    }
    val maxSpeed = visiblePoints.maxOfOrNull { it.tokensPerSecond }?.coerceAtLeast(1.0) ?: 1.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        visiblePoints.forEach { sample ->
            val speed = sample.tokensPerSecond
            val fraction = (speed / maxSpeed).toFloat()
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = formatSettingsTokenRate(speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((18 + 96 * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(AetherPrimary.copy(alpha = 0.18f + 0.44f * fraction)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = sample.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun TokenMixLegend(
    label: String,
    color: Color,
    tokens: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        Text(
            text = formatSettingsTokenCount(tokens),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun usageSourceLabel(source: String): String {
    val knownId = when (source) {
        LlmUsageSources.Turn -> R.string.statistics_source_turn
        LlmUsageSources.Title -> R.string.statistics_source_title
        LlmUsageSources.Compact -> R.string.statistics_source_compact
        LlmUsageSources.Embedding -> R.string.statistics_source_embedding
        LlmUsageSources.Phone -> R.string.statistics_source_phone
        LlmUsageSources.Subagent -> R.string.statistics_source_subagent
        else -> 0
    }
    if (knownId != 0) return stringResource(knownId)
    val context = LocalContext.current
    val name = when (source) {
        LlmUsageSources.Browser -> "statistics_source_browser"
        LlmUsageSources.Image -> "statistics_source_image"
        else -> null
    }
    if (name != null) {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        if (id != 0) return context.getString(id)
    }
    return stringResource(R.string.statistics_source_subagent)
}

@Composable
private fun HistoryPeakRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun buildSettingsStatisticsSummary(
    usageStatisticsTotals: LlmUsageTotals,
): String = if (usageStatisticsTotals.totalTokens == 0L && usageStatisticsTotals.recordCount == 0) {
    ""
} else {
    stringResource(
        R.string.settings_statistics_summary,
        formatSettingsTokenCount(usageStatisticsTotals.totalTokens),
        usageStatisticsTotals.recordCount,
    )
}

private fun buildUsageStatisticsReport(
    usageStatisticsSnapshots: List<ChatUsageStatisticsSnapshot>,
    totals: LlmUsageTotals,
): UsageStatisticsReport {
    val turnStats = usageStatisticsSnapshots.filter { it.source == LlmUsageSources.Turn }.map { it.statistics }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val daily = (13 downTo 0).map { daysAgo ->
        val date = today.minusDays(daysAgo.toLong())
        DailyTokenUsage(
            date = date,
            label = "${date.monthValue}/${date.dayOfMonth}",
            shortLabel = date.dayOfMonth.toString(),
            tokens = usageStatisticsSnapshots
                .filter { snapshot ->
                    val stat = snapshot.statistics
                    val millis = stat.completedAtMillis.takeIf { it > 0L } ?: stat.startedAtMillis
                    millis > 0L && Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == date
                }
                .sumOf { it.statistics.effectiveTokenTotal() },
        )
    }
    val speedSamples = turnStats.mapNotNull { stat ->
        val speed = stat.outputTokensPerSecond ?: return@mapNotNull null
        val millis = stat.completedAtMillis.takeIf { it > 0L } ?: stat.startedAtMillis
        if (millis <= 0L) return@mapNotNull null
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        SpeedSample(
            date = date,
            label = "${date.monthValue}/${date.dayOfMonth}",
            shortLabel = "${date.monthValue}/${date.dayOfMonth}",
            tokensPerSecond = speed,
            timestampMillis = millis,
        )
    }.sortedBy { it.timestampMillis }
    val speeds = speedSamples.map { it.tokensPerSecond }
    val latencies = turnStats.mapNotNull { it.firstTokenLatencyMillis }
    val sourceOrder = listOf(
        LlmUsageSources.Turn,
        LlmUsageSources.Phone,
        LlmUsageSources.Browser,
        LlmUsageSources.Image,
        LlmUsageSources.Subagent,
        LlmUsageSources.Title,
        LlmUsageSources.Compact,
        LlmUsageSources.Embedding,
    )
    val tokensBySource = totals.tokensBySource
        .map { total -> NamedTokenTotal(total.key.ifBlank { LlmUsageSources.Turn }, total.tokens) }
        .sortedWith(
            compareBy<NamedTokenTotal> { sourceOrder.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenByDescending { it.tokens },
        )
    val tokensByModel = mergeTokensByDisplayModelId(
        items = totals.tokensByModel.map { it.key to it.tokens },
    ).map { (key, tokens) -> NamedTokenTotal(key, tokens) }
    return UsageStatisticsReport(
        totalTokens = totals.totalTokens,
        inputTokens = totals.inputTokens,
        outputTokens = totals.outputTokens,
        reasoningTokens = totals.reasoningTokens,
        sessionCount = totals.sessionCount,
        turnCount = totals.turnCount,
        recordCount = totals.recordCount,
        dailyTokenUsage = daily,
        peakDay = daily.maxByOrNull { it.tokens }?.takeIf { it.tokens > 0L },
        largestTurnTokens = totals.largestTurnTokens,
        averageOutputTokensPerSecond = speeds.takeIf { it.isNotEmpty() }?.average(),
        averageFirstTokenLatencyMillis = latencies.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toLong(),
        recentSpeedSamples = speedSamples,
        tokensBySource = tokensBySource,
        tokensByModel = tokensByModel,
        cachedInputTokens = totals.cachedInputTokens,
        cacheHitPercent = tokenCacheHitPercent(
            cachedInputTokens = totals.cachedInputTokens,
            inputTokens = totals.inputTokens,
        ),
    )
}

private fun formatSettingsTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatSettingsTokenRate(tokensPerSecond: Double): String =
    String.format(Locale.US, "%.1f tok/s", tokensPerSecond)

private fun formatSettingsDuration(millis: Long): String =
    if (millis >= 1_000L) {
        String.format(Locale.US, "%.2fs", millis / 1000.0)
    } else {
        "${millis}ms"
    }

private fun ChatUsageStatistics.effectiveTokenTotal(): Long {
    val total = totalTokens ?: 0L
    if (total > 0L) return total
    return (inputTokens ?: 0L) + (outputTokens ?: 0L) + (reasoningTokens ?: 0L)
}

private data class UsageStatisticsReport(
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val sessionCount: Int,
    val turnCount: Int,
    val recordCount: Int,
    val dailyTokenUsage: List<DailyTokenUsage>,
    val peakDay: DailyTokenUsage?,
    val largestTurnTokens: Long?,
    val averageOutputTokensPerSecond: Double?,
    val averageFirstTokenLatencyMillis: Long?,
    val recentSpeedSamples: List<SpeedSample>,
    val tokensBySource: List<NamedTokenTotal> = emptyList(),
    val tokensByModel: List<NamedTokenTotal> = emptyList(),
    val cachedInputTokens: Long = 0,
    val cacheHitPercent: Int? = null,
)

private data class NamedTokenTotal(
    val key: String,
    val tokens: Long,
)

private data class DailyTokenUsage(
    val date: LocalDate,
    val label: String,
    val shortLabel: String,
    val tokens: Long,
)

private data class SpeedSample(
    val date: LocalDate,
    val label: String,
    val shortLabel: String,
    val tokensPerSecond: Double,
    val timestampMillis: Long,
)

// -----------------------------------------------------------------------------
// Providers List Page (Multi-Provider)
// -----------------------------------------------------------------------------

@Composable
private fun GeneralSettingsPage(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    selectedThemeMode: AppThemeMode,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = stringResource(R.string.settings_general), onBack = onBack) {
        SettingsCardGroup {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_language_description),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppLanguage.entries.forEach { option ->
                    SettingsChoiceRow(
                        title = settingsLanguageDisplayName(option),
                        subtitle = settingsLanguageSubtitle(option),
                        selected = option == selectedLanguage,
                        onClick = { onLanguageSelected(option) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_theme_description),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeMode.entries.forEach { option ->
                    SettingsChoiceRow(
                        title = settingsThemeDisplayName(option),
                        subtitle = if (option == AppThemeMode.Light) {
                            stringResource(R.string.settings_light_theme_subtitle)
                        } else {
                            stringResource(R.string.settings_dark_theme_subtitle)
                        },
                        selected = option == selectedThemeMode,
                        onClick = { onThemeModeSelected(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsPageV2(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    selectedThemeMode: AppThemeMode,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = stringResource(R.string.settings_general), onBack = onBack) {
        SettingsCardGroup {
            SelectionDropdownField(
                label = stringResource(R.string.settings_language),
                supportingText = stringResource(R.string.settings_language_description),
                selectedLabel = settingsLanguageDisplayName(selectedLanguage),
                options = AppLanguage.entries.map { option ->
                    SelectionOption(
                        key = option.storageValue,
                        title = settingsLanguageDisplayName(option),
                        subtitle = settingsLanguageSubtitle(option),
                        selected = option == selectedLanguage,
                        onClick = { onLanguageSelected(option) },
                    )
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            SelectionDropdownField(
                label = stringResource(R.string.settings_theme),
                supportingText = stringResource(R.string.settings_theme_description),
                selectedLabel = settingsThemeDisplayName(selectedThemeMode),
                options = AppThemeMode.entries.map { option ->
                    SelectionOption(
                        key = option.storageValue,
                        title = settingsThemeDisplayName(option),
                        subtitle = settingsThemeSubtitle(option),
                        selected = option == selectedThemeMode,
                        onClick = { onThemeModeSelected(option) },
                    )
                },
            )
        }
    }
}

@Composable
private fun TermuxEnvironmentVariablesSection(
    variables: List<TermuxEnvironmentVariable>,
    onVariablesChanged: (List<TermuxEnvironmentVariable>) -> Unit,
) {
    SettingsCardGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_environment_variables),
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_environment_variables_description),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            val rows = if (variables.isEmpty()) {
                listOf(TermuxEnvironmentVariable("", ""))
            } else {
                variables
            }
            fun commitRows(updatedRows: List<TermuxEnvironmentVariable>) {
                onVariablesChanged(
                    updatedRows.filter { it.name.isNotBlank() || it.value.isNotBlank() }
                )
            }
            rows.forEachIndexed { index, variable ->
                var nameValue by rememberSaveable(index, stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue(variable.name))
                }
                var valueValue by rememberSaveable(index, stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue(variable.value))
                }
                LaunchedEffect(variable.name) {
                    if (variable.name != nameValue.text) {
                        nameValue = TextFieldValue(
                            text = variable.name,
                            selection = androidx.compose.ui.text.TextRange(variable.name.length),
                        )
                    }
                }
                LaunchedEffect(variable.value) {
                    if (variable.value != valueValue.text) {
                        valueValue = TextFieldValue(
                            text = variable.value,
                            selection = androidx.compose.ui.text.TextRange(variable.value.length),
                        )
                    }
                }
                fun commitRow(name: String = nameValue.text, value: String = valueValue.text) {
                    val updated = rows.toMutableList()
                    updated[index] = TermuxEnvironmentVariable(name, value)
                    commitRows(updated)
                }

                ChatGptTextField(
                    label = stringResource(R.string.settings_variable_name),
                    value = nameValue,
                    onValueChange = {
                        nameValue = it
                        commitRow(name = it.text)
                    },
                )
                ChatGptTextField(
                    label = stringResource(R.string.settings_variable_value),
                    value = valueValue,
                    onValueChange = {
                        valueValue = it
                        commitRow(value = it.text)
                    },
                )
                if (variable.name.isNotBlank() || variable.value.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_remove_variable),
                        onClick = {
                            commitRows(rows.filterIndexed { rowIndex, _ -> rowIndex != index })
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            SettingsSubtleActionButton(
                label = stringResource(R.string.settings_add_variable),
                onClick = { onVariablesChanged(variables + TermuxEnvironmentVariable("", "")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProvidersListPage(
    providerConfigs: List<LlmProviderConfig>,
    onSetProviderEnabled: (String, Boolean) -> Unit,
    onOpenDefaultModels: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.settings_model_providers),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        if (providerConfigs.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.settings_no_providers_configured),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_add_provider_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.settings_add_provider),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            providerConfigs.forEach { config ->
                ProviderCard(
                    config = config,
                    onEnabledChange = { enabled -> onSetProviderEnabled(config.id, enabled) },
                    onEdit = { onEdit(config.id) },
                    onRemove = { onRemove(config.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        SettingsCardGroup {
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_default_models),
                subtitle = stringResource(R.string.settings_default_models_subtitle),
                onClick = onOpenDefaultModels,
            )
        }
    }
}

@Composable
private fun DefaultModelsPage(
    modelOptions: List<ProviderModelOption>,
    catalogModelOptions: List<ProviderModelOption> = modelOptions,
    defaultChatModelKey: String,
    defaultTitleModelKey: String,
    defaultNamingModelKey: String,
    defaultCompactingModelKey: String,
    defaultVectorModelKey: String,
    defaultImageModelKey: String,
    defaultAsrModelKey: String,
    defaultTtsModelKey: String,
    ttsEnabled: Boolean,
    ttsVoiceId: String,
    asrModelOptions: List<ProviderModelOption>,
    ttsModelOptions: List<ProviderModelOption>,
    onOpenDefaultChatModel: () -> Unit,
    onOpenDefaultTitleModel: () -> Unit,
    onOpenDefaultNamingModel: () -> Unit,
    onOpenDefaultCompactingModel: () -> Unit,
    onOpenDefaultVectorModel: () -> Unit,
    onOpenDefaultImageModel: () -> Unit,
    onOpenDefaultAsrModel: () -> Unit,
    onOpenDefaultTtsModel: () -> Unit,
    onBack: () -> Unit,
) {
    val automaticChatLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
    )?.fullLabel
    val automaticTitleLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Title)
            .ifBlank { modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
    )?.fullLabel
    val automaticNamingLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Naming)
            .ifBlank { modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
    )?.fullLabel
    val automaticCompactingLabel = modelOptions.findModelOption(
        modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Compacting)
            .ifBlank { modelOptions.resolveAutomaticModelKey(AutomaticModelPurpose.Chat) }
    )?.fullLabel
    val unselectedLabel = stringResource(R.string.settings_unselected_model)
    SubPageScaffold(
        title = stringResource(R.string.settings_default_models),
        onBack = onBack,
    ) {
        SettingsCardGroup {
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_default_chat_model),
                subtitle = if (defaultChatModelKey.isBlank()) {
                    automaticChatLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                        ?: stringResource(R.string.settings_automatic_model)
                } else {
                    modelOptions.findModelOption(defaultChatModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultChatModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.settings_default_title_model),
                subtitle = if (defaultTitleModelKey.isBlank()) {
                    automaticTitleLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                        ?: stringResource(R.string.settings_automatic_model)
                } else {
                    modelOptions.findModelOption(defaultTitleModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultTitleModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.settings_default_naming_model),
                subtitle = if (defaultNamingModelKey.isBlank()) {
                    automaticNamingLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                        ?: stringResource(R.string.settings_automatic_model)
                } else {
                    modelOptions.findModelOption(defaultNamingModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultNamingModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_default_compacting_model),
                subtitle = if (defaultCompactingModelKey.isBlank()) {
                    automaticCompactingLabel?.let { stringResource(R.string.settings_automatic_model_with_name, it) }
                        ?: stringResource(R.string.settings_automatic_model)
                } else {
                    modelOptions.findModelOption(defaultCompactingModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultCompactingModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.settings_default_vector_model),
                subtitle = if (defaultVectorModelKey.isBlank()) {
                    unselectedLabel
                } else {
                    catalogModelOptions.findModelOption(defaultVectorModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultVectorModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.settings_default_image_model),
                subtitle = if (defaultImageModelKey.isBlank()) {
                    unselectedLabel
                } else {
                    catalogModelOptions.findModelOption(defaultImageModelKey)?.fullLabel.orEmpty()
                },
                onClick = onOpenDefaultImageModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.settings_default_asr_model),
                subtitle = asrModelSubtitle(
                    defaultAsrModelKey = defaultAsrModelKey,
                    catalogModelOptions = asrModelOptions,
                    unselectedLabel = unselectedLabel,
                ),
                onClick = onOpenDefaultAsrModel,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.VolumeUp,
                title = stringResource(R.string.settings_default_tts_model),
                subtitle = ttsModelSubtitle(
                    defaultTtsModelKey = defaultTtsModelKey,
                    catalogModelOptions = ttsModelOptions,
                    ttsEnabled = ttsEnabled,
                    ttsVoiceId = ttsVoiceId,
                    unselectedLabel = unselectedLabel,
                ),
                onClick = onOpenDefaultTtsModel,
            )
        }
    }
}

@Composable
private fun ProviderCard(
    config: LlmProviderConfig,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val availableModels = config.availableModels()
    val enabledModels = config.enabledModels()
    val provider = PiProviderCatalog.resolve(config.piProviderId)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = config.isEnabled,
            onCheckedChange = { enabled -> onEnabledChange(enabled) },
        )

        Spacer(Modifier.width(10.dp))
        ProviderBrandIconBadge(
            provider = provider,
            badgeSize = 40.dp,
            iconSize = 25.dp,
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${provider.displayName} · ${config.providerId}",
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_provider_models_enabled_count, enabledModels.size, availableModels.size),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onEdit) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.action_edit),
                tint = AetherOnSurfaceVariant,
            )
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.action_remove),
                tint = Color(0xFFD25757),
            )
        }
    }
}

@Composable
private fun ModelSelectionListPage(
    title: String,
    subtitle: String,
    selectedKey: String,
    options: List<ProviderModelOption>,
    purpose: AutomaticModelPurpose,
    allowAutomatic: Boolean = true,
    automaticLabel: String,
    automaticSubtitle: String,
    onSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val selectedOption = options.findModelOption(selectedKey)
    val sortedOptions = remember(options, purpose, allowAutomatic) {
        options.sortedForAutomaticModelPurpose(purpose)
    }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val trimmedSearchQuery = searchQuery.text.trim()
    val filteredOptions = remember(sortedOptions, trimmedSearchQuery) {
        if (trimmedSearchQuery.isBlank()) {
            sortedOptions
        } else {
            val needle = trimmedSearchQuery.lowercase()
            sortedOptions.filter { option ->
                option.fullLabel.contains(needle, ignoreCase = true) ||
                    option.modelId.contains(needle, ignoreCase = true) ||
                    option.providerName.contains(needle, ignoreCase = true) ||
                    option.providerId.contains(needle, ignoreCase = true) ||
                    PiProviderCatalog.resolve(option.piProviderId).displayName
                        .contains(needle, ignoreCase = true)
            }
        }
    }

    SubPageScaffold(
        title = title,
        onBack = onBack,
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (options.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_no_enabled_models_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_enable_provider_model_first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            return@SubPageScaffold
        }

        SettingsCardGroup {
            ModelSelectionSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
            )
        }
        Spacer(Modifier.height(12.dp))

        SettingsCardGroup {
            ModelSelectionListRow(
                title = automaticLabel,
                subtitle = automaticSubtitle,
                selected = selectedOption == null,
                onClick = { onSelected("") },
            )
            filteredOptions.forEach { option ->
                CardDivider()
                ModelSelectionListRow(
                    title = option.fullLabel,
                    subtitle = option.providerName,
                    selected = option.key == selectedOption?.key,
                    onClick = { onSelected(option.key) },
                )
            }
            if (filteredOptions.isEmpty()) {
                CardDivider()
                Text(
                    text = stringResource(R.string.settings_no_models_match_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun asrModelSubtitle(
    defaultAsrModelKey: String,
    catalogModelOptions: List<ProviderModelOption>,
    unselectedLabel: String,
): String {
    if (defaultAsrModelKey.isBlank()) return unselectedLabel
    return when (defaultAsrModelKey) {
        AsrDeviceModelKeys.Auto -> stringResource(R.string.settings_asr_device_auto)
        AsrDeviceModelKeys.Hms -> stringResource(R.string.settings_asr_device_hms)
        AsrDeviceModelKeys.MlKit -> stringResource(R.string.settings_asr_device_mlkit)
        AsrDeviceModelKeys.System -> stringResource(R.string.settings_asr_device_system)
        else -> catalogModelOptions.findModelOption(defaultAsrModelKey)?.fullLabel
            ?: defaultAsrModelKey
    }
}

@Composable
private fun asrAvailabilityLabel(status: AsrAvailability): String = stringResource(
    when (status) {
        AsrAvailability.Available -> R.string.settings_asr_status_available
        AsrAvailability.Downloadable -> R.string.settings_asr_status_downloadable
        AsrAvailability.Unavailable -> R.string.settings_asr_status_unavailable
    },
)

@Composable
private fun AsrModelSelectionListPage(
    selectedKey: String,
    catalogOptions: List<ProviderModelOption>,
    onSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AsrCapabilitySnapshot()) }
    LaunchedEffect(Unit) {
        snapshot = AsrCapabilityProbe.probe(context)
    }
    val unselectedLabel = stringResource(R.string.settings_unselected_model)
    SubPageScaffold(
        title = stringResource(R.string.settings_default_asr_model),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.settings_default_asr_model_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        SettingsCardGroup {
            ModelSelectionListRow(
                title = unselectedLabel,
                subtitle = stringResource(R.string.settings_manual_model_required),
                selected = selectedKey.isBlank(),
                onClick = { onSelected("") },
            )
            CardDivider()
            ModelSelectionListRow(
                title = stringResource(R.string.settings_asr_device_auto),
                subtitle = asrAvailabilityLabel(
                    listOf(snapshot.hms, snapshot.mlKit, snapshot.system)
                        .firstOrNull { it != AsrAvailability.Unavailable }
                        ?: AsrAvailability.Unavailable,
                ),
                selected = selectedKey == AsrDeviceModelKeys.Auto,
                onClick = { onSelected(AsrDeviceModelKeys.Auto) },
            )
            CardDivider()
            ModelSelectionListRow(
                title = stringResource(R.string.settings_asr_device_hms),
                subtitle = asrAvailabilityLabel(snapshot.hms),
                selected = selectedKey == AsrDeviceModelKeys.Hms,
                onClick = { onSelected(AsrDeviceModelKeys.Hms) },
            )
            CardDivider()
            ModelSelectionListRow(
                title = stringResource(R.string.settings_asr_device_mlkit),
                subtitle = asrAvailabilityLabel(snapshot.mlKit),
                selected = selectedKey == AsrDeviceModelKeys.MlKit,
                onClick = { onSelected(AsrDeviceModelKeys.MlKit) },
            )
            CardDivider()
            ModelSelectionListRow(
                title = stringResource(R.string.settings_asr_device_system),
                subtitle = asrAvailabilityLabel(snapshot.system),
                selected = selectedKey == AsrDeviceModelKeys.System,
                onClick = { onSelected(AsrDeviceModelKeys.System) },
            )
            catalogOptions.sortedForAutomaticModelPurpose(AutomaticModelPurpose.Asr).forEach { option ->
                CardDivider()
                ModelSelectionListRow(
                    title = option.fullLabel,
                    subtitle = option.providerName,
                    selected = option.key == selectedKey,
                    onClick = { onSelected(option.key) },
                )
            }
        }
    }
}

@Composable
private fun ttsModelSubtitle(
    defaultTtsModelKey: String,
    catalogModelOptions: List<ProviderModelOption>,
    ttsEnabled: Boolean,
    ttsVoiceId: String,
    unselectedLabel: String,
): String {
    if (defaultTtsModelKey.isBlank()) return unselectedLabel
    val modelLabel = catalogModelOptions.findModelOption(defaultTtsModelKey)?.fullLabel
        ?: defaultTtsModelKey
    val voiceLabel = VoiceProfileCatalog.findVoice(ttsVoiceId)?.name ?: ttsVoiceId.ifBlank { "" }
    val status = if (ttsEnabled) "ON" else "OFF"
    return buildString {
        append(modelLabel)
        if (voiceLabel.isNotBlank()) append(" · ").append(voiceLabel)
        append(" · ").append(status)
    }
}

@Composable
private fun TtsModelSelectionListPage(
    selectedKey: String,
    catalogOptions: List<ProviderModelOption>,
    ttsEnabled: Boolean,
    ttsVoiceId: String,
    onTtsEnabledChanged: (Boolean) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val unselectedLabel = stringResource(R.string.settings_unselected_model)
    SubPageScaffold(
        title = stringResource(R.string.settings_default_tts_model),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.settings_default_tts_model_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        // TTS enable/disable toggle
        SettingsCardGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_tts_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AetherOnSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_tts_enabled_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = onTtsEnabledChanged,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Voice selection
        if (selectedKey.isNotBlank()) {
            val selectedOption = catalogOptions.findModelOption(selectedKey)
            val voices = remember(selectedOption) {
                selectedOption?.let { option ->
                    VoiceProfileCatalog.voicesForProvider(option.piProviderId, option.baseUrl)
                } ?: emptyList()
            }
            if (voices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_tts_voice),
                    style = MaterialTheme.typography.titleSmall,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(8.dp))
                SettingsCardGroup {
                    voices.forEachIndexed { index, voice ->
                        if (index > 0) CardDivider()
                        ModelSelectionListRow(
                            title = voice.name,
                            subtitle = voice.description,
                            selected = voice.id == ttsVoiceId,
                            onClick = { onVoiceSelected(voice.id) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // Model selection
        SettingsCardGroup {
            ModelSelectionListRow(
                title = unselectedLabel,
                subtitle = stringResource(R.string.settings_manual_model_required),
                selected = selectedKey.isBlank(),
                onClick = { onSelected("") },
            )
            catalogOptions.sortedForAutomaticModelPurpose(AutomaticModelPurpose.Asr).forEach { option ->
                CardDivider()
                ModelSelectionListRow(
                    title = option.fullLabel,
                    subtitle = option.providerName,
                    selected = option.key == selectedKey,
                    onClick = { onSelected(option.key) },
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .settingsBringIntoViewOnFocus(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AetherOnSurface),
            cursorBrush = SolidColor(AetherPrimary),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_search_models),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AetherOnSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun ModelSelectionListRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherSettingsBackground.copy(alpha = 0.9f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(14.dp))
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = if (selected) AetherPrimary else Color.Transparent,
            modifier = Modifier.size(22.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Provider Edit Page (Add/Edit)
// -----------------------------------------------------------------------------

@Composable
private fun ProviderEditPage(
    existingConfig: LlmProviderConfig?,
    existingProviderIds: Set<String>,
    isFetchingModels: Boolean,
    onSave: (LlmProviderConfig) -> Unit,
    onModelEnabledChange: (LlmProviderConfig) -> Unit,
    onFetchModels: (LlmProviderConfig, (List<String>) -> Unit) -> Unit,
    authState: PiProviderAuthState,
    onStartProviderLogin: (String, String, ProviderAuthMethod, String) -> Unit,
    onSubmitAuthPrompt: (String, String, Boolean) -> Unit,
    onClearAuthState: () -> Unit,
    onBack: () -> Unit,
) {
    val isNew = existingConfig == null
    val formState = rememberProviderFormState(existingConfig)

    if (isNew) {
        SubPageScaffold(
            title = stringResource(R.string.settings_add_provider),
            onBack = onBack,
        ) {
            AddProviderWizard(
                state = formState,
                existingProviderIds = existingProviderIds,
                isFetchingModels = isFetchingModels,
                onFetchModels = onFetchModels,
                authState = authState,
                onStartProviderLogin = onStartProviderLogin,
                onSubmitAuthPrompt = onSubmitAuthPrompt,
                onClearAuthState = onClearAuthState,
                onSave = onSave,
            )
        }
        return
    }

    SubPageScaffold(
        title = stringResource(R.string.settings_edit_provider),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        trailingEnabled = formState.isValid(existingProviderIds),
        onTrailingAction = {
            onSave(formState.buildConfig())
            onBack()
        },
    ) {
        ProviderConfigurationForm(
            state = formState,
            existingProviderIds = existingProviderIds,
            isFetchingModels = isFetchingModels,
            onFetchModels = onFetchModels,
            onModelEnabledChange = onModelEnabledChange,
            authState = authState,
            onStartProviderLogin = onStartProviderLogin,
            onSubmitAuthPrompt = onSubmitAuthPrompt,
            onClearAuthState = onClearAuthState,
        )
    }
}
// -----------------------------------------------------------------------------
// Personalization sub-page
// -----------------------------------------------------------------------------

@Composable
private fun PersonalizationPage(
    title: String,
    systemPromptValue: TextFieldValue,
    onSystemPromptChanged: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.settings_custom_instructions),
                value = systemPromptValue,
                onValueChange = onSystemPromptChanged,
                minLines = 8,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_custom_instructions_variables_hint),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Skills List Page (Refactored)
// -----------------------------------------------------------------------------

@Composable
private fun ReliabilityPage(
    title: String,
    llmInactivityReconnectTimeoutValue: TextFieldValue,
    onLlmInactivityReconnectTimeoutChanged: (TextFieldValue) -> Unit,
    keepTasksRunningInBackground: Boolean,
    onKeepTasksRunningInBackgroundChanged: (Boolean) -> Unit,
    notifyOnTaskCompletion: Boolean,
    onNotifyOnTaskCompletionChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        Text(
            text = stringResource(R.string.settings_multitasking),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_keep_tasks_running_background),
                    subtitle = stringResource(R.string.settings_keep_tasks_running_background_subtitle),
                    checked = keepTasksRunningInBackground,
                    onCheckedChange = onKeepTasksRunningInBackgroundChanged,
                )
                Spacer(Modifier.height(4.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_notify_background_tasks_finish),
                    subtitle = stringResource(R.string.settings_notify_background_tasks_finish_subtitle),
                    checked = notifyOnTaskCompletion,
                    onCheckedChange = onNotifyOnTaskCompletionChanged,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_reconnect),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.settings_reconnect_after_idle_seconds),
                value = llmInactivityReconnectTimeoutValue,
                onValueChange = {
                    val digitsOnly = it.text.filter(Char::isDigit)
                    onLlmInactivityReconnectTimeoutChanged(
                        it.copy(
                            text = digitsOnly,
                            selection = androidx.compose.ui.text.TextRange(digitsOnly.length),
                        )
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_reconnect_after_idle_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun AetherExtensionSettingsCategoriesPage(
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    onCategorySelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val controller = LocalAetherExtensionUiController.current
    val trailingIcon = settingsTrailingIcon(page.trailingIcon)
    SubPageScaffold(
        title = page.title,
        onBack = onBack,
        trailingIcon = trailingIcon,
        onTrailingAction = {
            if (page.trailingCategory.isNotBlank()) {
                onCategorySelected(page.trailingCategory)
            } else if (page.trailingAction.isNotBlank()) {
                controller?.onAction?.invoke(page.extensionId, page.trailingAction, page.trailingArgs)
            } else {
                onBack()
            }
        },
    ) {
        if (page.subtitle.isNotBlank()) {
            Text(page.subtitle, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (page.sections.isNotEmpty()) {
            AetherExtensionSettingsSections(page.sections, page, controller, onCategorySelected)
            Spacer(Modifier.height(16.dp))
        }
        val visibleCategories = page.categories.filterNot(kira.ditto.data.AetherAppExtensionSettingsCategory::hidden)
        SettingsCardGroup {
            visibleCategories.forEachIndexed { index, category ->
                SettingsNavRow(
                    icon = extensionIcon(category.icon),
                    title = category.title,
                    subtitle = category.subtitle,
                ) { onCategorySelected(category.id) }
                if (index < visibleCategories.lastIndex) CardDivider()
            }
        }
    }
}

@Composable
private fun AetherExtensionSettingsSections(
    sections: List<JSONObject>,
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    controller: AetherExtensionUiController?,
    onCategorySelected: (String) -> Unit = {},
) {
    sections.forEachIndexed { sectionIndex, section ->
        val sectionTitle = section.optString("title")
        val sectionDescription = section.optString("description")
        if (sectionIndex > 0) Spacer(Modifier.height(16.dp))
        if (sectionTitle.isNotBlank() || sectionDescription.isNotBlank()) {
            if (sectionTitle.isNotBlank()) {
                Text(
                    sectionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (sectionDescription.isNotBlank()) {
                if (sectionTitle.isNotBlank()) Spacer(Modifier.height(4.dp))
                Text(
                    sectionDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        val settings = section.optJSONArray("settings") ?: JSONArray()
        var index = 0
        while (index < settings.length()) {
            val setting = settings.optJSONObject(index) ?: run { index += 1; continue }
            val type = setting.optString("type").ifBlank { "text" }
            when (type) {
                "item-card", "card" -> {
                    AetherExtensionItemCard(
                        setting = setting,
                        page = page,
                        controller = controller,
                        onCategorySelected = onCategorySelected,
                    )
                    Spacer(Modifier.height(12.dp))
                    index += 1
                }
                "empty-state" -> {
                    AetherExtensionEmptyState(
                        setting = setting,
                        page = page,
                        controller = controller,
                        onCategorySelected = onCategorySelected,
                    )
                    Spacer(Modifier.height(12.dp))
                    index += 1
                }
                else -> {
                    val group = mutableListOf<JSONObject>()
                    while (index < settings.length()) {
                        val next = settings.optJSONObject(index) ?: break
                        val nextType = next.optString("type").ifBlank { "text" }
                        if (nextType == "item-card" || nextType == "card" || nextType == "empty-state") break
                        group.add(next)
                        index += 1
                    }
                    SettingsCardGroup {
                        Column {
                            group.forEachIndexed { groupIndex, itemSetting ->
                                AetherExtensionControlRow(
                                    setting = itemSetting,
                                    page = page,
                                    controller = controller,
                                    onCategorySelected = onCategorySelected,
                                )
                                val itemType = itemSetting.optString("type").ifBlank { "text" }
                                if (groupIndex < group.size - 1 && itemType !in setOf("divider", "spacer")) CardDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AetherExtensionControlRow(
    setting: JSONObject,
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    controller: AetherExtensionUiController?,
    onCategorySelected: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val id = "${page.id}:${setting.optString("id")}"
    val type = setting.optString("type").ifBlank { "text" }
    val label = setting.optString("label").ifBlank { setting.optString("title") }
    val description = setting.optString("description").ifBlank { setting.optString("subtitle") }
    val action = setting.optString("action").ifBlank {
        "settings:${page.localId}:${setting.optString("id")}"
    }

    fun update(value: Any?) {
        controller?.onAction?.invoke(
            page.extensionId,
            "settings:${page.localId}:${setting.optString("id")}",
            JSONObject().put("setting", setting.optString("id")).put("value", value),
        )
    }

    when (type) {
        "toggle" -> {
            var checked by remember(id, setting.optBoolean("value")) {
                mutableStateOf(setting.optBoolean("value"))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                SettingsToggleRow(label, description, checked) {
                    checked = it
                    update(it)
                }
            }
        }
        "select", "dropdown", "segmented", "tab", "tabs" -> {
            val options = setting.optJSONArray("options") ?: JSONArray()
            var selected by remember(id, setting.optString("value")) {
                mutableStateOf(setting.optString("value"))
            }
            if (type == "segmented" || type == "tab" || type == "tabs") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
                    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        for (optionIndex in 0 until options.length()) {
                            val option = options.optJSONObject(optionIndex) ?: continue
                            val value = option.optString("value")
                            SegmentedButton(
                                selected = selected == value,
                                onClick = { selected = value; update(value) },
                                shape = SegmentedButtonDefaults.itemShape(optionIndex, options.length()),
                            ) { Text(option.optString("label").ifBlank { value }) }
                        }
                    }
                }
            } else {
                val selectedLabel = (0 until options.length())
                    .mapNotNull(options::optJSONObject)
                    .firstOrNull { it.optString("value") == selected }
                    ?.optString("label")
                    .orEmpty()
                SelectionDropdownField(
                    label = label,
                    supportingText = description,
                    selectedLabel = selectedLabel.ifBlank { selected },
                    options = (0 until options.length()).mapNotNull { optionIndex ->
                        val option = options.optJSONObject(optionIndex) ?: return@mapNotNull null
                        val value = option.optString("value")
                        SelectionOption(
                            key = value,
                            title = option.optString("label").ifBlank { value },
                            subtitle = option.optString("description"),
                            selected = selected == value,
                            onClick = { selected = value; update(value) },
                        )
                    },
                )
            }
        }
        "slider" -> {
            val minimum = setting.optDouble("min", 0.0).toFloat()
            val maximum = setting.optDouble("max", 1.0).toFloat().coerceAtLeast(minimum + 0.0001f)
            val step = setting.optDouble("step", 0.01).toFloat().takeIf { it > 0f } ?: 0.01f
            val discreteSteps = (((maximum - minimum) / step).roundToInt() - 1).coerceAtLeast(0)
            var value by remember(id, setting.optDouble("value", minimum.toDouble())) {
                mutableStateOf(setting.optDouble("value", minimum.toDouble()).toFloat().coerceIn(minimum, maximum))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
                }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { update(value) },
                    valueRange = minimum..maximum,
                    steps = discreteSteps.takeIf { it in 1..20 } ?: 0,
                )
            }
        }
        "button" -> {
            val buttonIcon = setting.optString("icon")
                .takeIf(String::isNotBlank)
                ?.let(::extensionIcon)
            val buttonModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            val onClick: () -> Unit = {
                controller?.onAction?.invoke(
                    page.extensionId,
                    action,
                    setting.optJSONObject("args") ?: JSONObject(),
                )
            }
            when (setting.optString("tone").lowercase()) {
                "neutral", "secondary", "danger", "error" -> SettingsSubtleActionButton(
                    label = label,
                    onClick = onClick,
                    modifier = buttonModifier,
                    enabled = setting.optBoolean("enabled", true),
                    icon = buttonIcon,
                )
                else -> SettingsActionButton(
                    label = label,
                    onClick = onClick,
                    modifier = buttonModifier,
                    enabled = setting.optBoolean("enabled", true),
                    icon = buttonIcon,
                )
            }
        }
        "link" -> SettingsNavRow(
            icon = Icons.Rounded.Link,
            title = label,
            subtitle = description,
            enabled = setting.optBoolean("enabled", true),
        ) {
            val category = setting.optString("category")
            val url = setting.optString("url")
            when {
                category.isNotBlank() -> onCategorySelected(category)
                url.isNotBlank() -> runCatching { uriHandler.openUri(url) }
                else -> controller?.onAction?.invoke(
                    page.extensionId,
                    action,
                    setting.optJSONObject("args") ?: JSONObject(),
                )
            }
        }
        "action-row", "chips" -> {
            val actions = setting.optJSONArray("actions") ?: JSONArray()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (actionIndex in 0 until actions.length()) {
                    val item = actions.optJSONObject(actionIndex) ?: continue
                    val itemLabel = item.optString("label")
                    val itemAction = item.optString("action")
                    val itemCategory = item.optString("category")
                    val itemArgs = item.optJSONObject("args") ?: JSONObject()
                    SettingsSubtleActionButton(
                        label = itemLabel,
                        enabled = item.optBoolean("enabled", true),
                        onClick = {
                            when {
                                itemCategory.isNotBlank() -> onCategorySelected(itemCategory)
                                itemAction.isNotBlank() -> controller?.onAction?.invoke(page.extensionId, itemAction, itemArgs)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        "detail-line", "key-value" -> {
            val value = setting.optString("value").ifBlank { description }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = AetherOnSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodySmall, color = AetherOnSurface)
            }
        }
        "pill", "badge" -> {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ActionPreviewPill(label)
            }
        }
        "result-card", "callout" -> {
            val value = setting.optString("text").ifBlank { setting.optString("value").ifBlank { label } }
            val title = setting.optString("title")
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = AetherOnSurface)
                    Spacer(Modifier.height(4.dp))
                }
                Text(value, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
            }
        }
        "divider" -> CardDivider()
        "spacer" -> Spacer(Modifier.height(setting.optInt("size", 8).coerceAtLeast(1).dp))
        "label" -> Text(label, color = AetherOnSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        else -> {
            var value by rememberSaveable(id, stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(setting.optString("value")))
            }
            ChatGptTextField(
                label = label,
                value = value,
                minLines = if (type == "textarea" || setting.optBoolean("multiline")) 4 else 1,
                isSecret = type == "password" || setting.optBoolean("secret"),
                placeholder = setting.optString("placeholder").ifBlank { label },
                supportingText = description,
                keyboardOptions = if (type == "number") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                onValueChange = { value = it; update(it.text) },
            )
        }
    }
}

@Composable
private fun AetherExtensionItemCard(
    setting: JSONObject,
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    controller: AetherExtensionUiController?,
    onCategorySelected: (String) -> Unit,
) {
    val id = "${page.id}:${setting.optString("id")}"
    val title = setting.optString("title").ifBlank { setting.optString("label") }
    val subtitle = setting.optString("subtitle").ifBlank { setting.optString("tag") }
    val pill = setting.optString("pill").ifBlank { setting.optString("badge") }
    val editAction = setting.optString("editAction")
    val editCategory = setting.optString("editCategory")
    val editArgs = setting.optJSONObject("editArgs") ?: JSONObject()
    val deleteAction = setting.optString("deleteAction")
    val deleteArgs = setting.optJSONObject("deleteArgs") ?: JSONObject()
    val toggleAction = setting.optString("toggleAction")
    val hasToggle = setting.has("checked") || setting.has("value") || toggleAction.isNotBlank()
    var expanded by rememberSaveable(id) { mutableStateOf(setting.optBoolean("expanded", false)) }
    var checked by remember(id, setting.optBoolean("checked", setting.optBoolean("value", true))) {
        mutableStateOf(setting.optBoolean("checked", setting.optBoolean("value", true)))
    }
    val actions = setting.optJSONArray("actions") ?: JSONArray()
    val details = setting.optJSONArray("details") ?: JSONArray()
    val resultText = setting.optString("resultText").ifBlank { setting.optString("result").ifBlank { setting.optString("status") } }
    val subSettings = setting.optJSONArray("settings") ?: JSONArray()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
                if (pill.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ActionPreviewPill(label = pill)
                }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (editAction.isNotBlank() || editCategory.isNotBlank()) {
                IconButton(
                    onClick = {
                        if (editCategory.isNotBlank()) {
                            onCategorySelected(editCategory)
                        } else if (editAction.isNotBlank()) {
                            controller?.onAction?.invoke(page.extensionId, editAction, editArgs)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                        tint = AetherOnSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (deleteAction.isNotBlank()) {
                IconButton(
                    onClick = {
                        controller?.onAction?.invoke(page.extensionId, deleteAction, deleteArgs)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = Color(0xFFD25757),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (hasToggle) {
            Spacer(Modifier.height(12.dp))
            SettingsToggleRow(
                title = "",
                subtitle = "",
                checked = checked,
                onCheckedChange = {
                    checked = it
                    if (toggleAction.isNotBlank()) {
                        controller?.onAction?.invoke(
                            page.extensionId,
                            toggleAction,
                            JSONObject()
                                .put("setting", setting.optString("id"))
                                .put("value", it)
                                .put("checked", it),
                        )
                    }
                },
            )
        }

        if (expanded) {
            if (actions.length() > 0) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (actionIndex in 0 until actions.length()) {
                        val item = actions.optJSONObject(actionIndex) ?: continue
                        val itemLabel = item.optString("label")
                        val itemAction = item.optString("action")
                        val itemCategory = item.optString("category")
                        val itemArgs = item.optJSONObject("args") ?: JSONObject()
                        SettingsSubtleActionButton(
                            label = itemLabel,
                            enabled = item.optBoolean("enabled", true),
                            onClick = {
                                if (itemCategory.isNotBlank()) {
                                    onCategorySelected(itemCategory)
                                } else if (itemAction.isNotBlank()) {
                                    controller?.onAction?.invoke(page.extensionId, itemAction, itemArgs)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (details.length() > 0) {
                Spacer(Modifier.height(14.dp))
                for (detailIndex in 0 until details.length()) {
                    val detail = details.optJSONObject(detailIndex) ?: continue
                    val detailLabel = detail.optString("label")
                    val detailValue = detail.optString("value")
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(detailLabel, style = MaterialTheme.typography.labelSmall, color = AetherOnSurfaceVariant)
                        Text(detailValue, style = MaterialTheme.typography.bodySmall, color = AetherOnSurface)
                    }
                }
            }

            if (resultText.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                SettingsCardGroup {
                    Text(
                        text = resultText,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }

            if (subSettings.length() > 0) {
                Spacer(Modifier.height(14.dp))
                SettingsCardGroup {
                    Column {
                        for (subIndex in 0 until subSettings.length()) {
                            val subSetting = subSettings.optJSONObject(subIndex) ?: continue
                            AetherExtensionControlRow(
                                setting = subSetting,
                                page = page,
                                controller = controller,
                                onCategorySelected = onCategorySelected,
                            )
                            val subType = subSetting.optString("type").ifBlank { "text" }
                            if (subIndex < subSettings.length() - 1 && subType !in setOf("divider", "spacer")) CardDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AetherExtensionEmptyState(
    setting: JSONObject,
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    controller: AetherExtensionUiController?,
    onCategorySelected: (String) -> Unit,
) {
    val title = setting.optString("title").ifBlank { setting.optString("label") }
    val description = setting.optString("description").ifBlank { setting.optString("subtitle") }
    val buttonLabel = setting.optString("buttonLabel").ifBlank { "Add" }
    val action = setting.optString("action")
    val category = setting.optString("category")
    val args = setting.optJSONObject("args") ?: JSONObject()

    SettingsCardGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
            if (buttonLabel.isNotBlank()) {
                SettingsActionButton(
                    label = buttonLabel,
                    onClick = {
                        if (category.isNotBlank()) {
                            onCategorySelected(category)
                        } else if (action.isNotBlank()) {
                            controller?.onAction?.invoke(page.extensionId, action, args)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AetherExtensionSettingsPage(
    page: kira.ditto.data.AetherAppExtensionSettingsPage,
    category: kira.ditto.data.AetherAppExtensionSettingsCategory?,
    onBack: () -> Unit,
    onCategorySelected: (String) -> Unit = {},
) {
    val controller = LocalAetherExtensionUiController.current
    val trailingIcon = settingsTrailingIcon(category?.trailingIcon?.ifBlank { null } ?: page.trailingIcon)
    val trailingAction = category?.trailingAction?.ifBlank { null } ?: page.trailingAction
    val trailingCategory = category?.trailingCategory?.ifBlank { null } ?: page.trailingCategory
    val trailingArgs = category?.trailingArgs?.takeIf { it.length() > 0 } ?: page.trailingArgs
    SubPageScaffold(
        title = category?.title ?: page.title,
        onBack = onBack,
        trailingIcon = trailingIcon,
        onTrailingAction = {
            if (!trailingCategory.isNullOrBlank()) {
                onCategorySelected(trailingCategory)
            } else if (trailingAction.isNotBlank()) {
                controller?.onAction?.invoke(page.extensionId, trailingAction, trailingArgs)
            } else {
                onBack()
            }
        },
    ) {
        if (category != null && category.subtitle.isNotBlank()) {
            Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        AetherExtensionSettingsSections(category?.sections ?: page.sections, page, controller)
    }
}

private fun settingsTrailingIcon(name: String): ImageVector? {
    if (name.isBlank()) return null
    return when (name.lowercase()) {
        "none", "hidden", "false" -> null
        else -> extensionIcon(name)
    }
}


@Composable
private fun WebToolsPage(
    title: String,
    tavilyApiKeyValue: TextFieldValue,
    onTavilyApiKeyChanged: (TextFieldValue) -> Unit,
    tavilyBaseUrlValue: TextFieldValue,
    onTavilyBaseUrlChanged: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        onTrailingAction = onBack,
    ) {
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.settings_tavily_api_key),
                value = tavilyApiKeyValue,
                onValueChange = onTavilyApiKeyChanged,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isSecret = true,
            )
            CardDivider()
            ChatGptTextField(
                label = stringResource(R.string.settings_tavily_base_url),
                value = tavilyBaseUrlValue,
                onValueChange = onTavilyBaseUrlChanged,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_web_tools_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun BrowserSettingsPage(
    title: String,
    preferences: kira.ditto.data.BrowserPreferences,
    onUpdate: (kira.ditto.data.BrowserPreferences) -> Unit,
    onOpenBrowser: () -> Unit,
    onClearData: () -> Unit,
    onDeleteLogin: (String) -> Unit,
    onListLogins: () -> List<kira.ditto.browser.SavedBrowserLogin>,
    onBack: () -> Unit,
) {
    var homepageValue by remember(preferences.homepage) {
        mutableStateOf(TextFieldValue(preferences.homepage))
    }
    var customSearchValue by remember(preferences.customSearchTemplate) {
        mutableStateOf(TextFieldValue(preferences.customSearchTemplate))
    }
    var logins by remember { mutableStateOf(onListLogins()) }
    LaunchedEffect(Unit) {
        logins = onListLogins()
    }
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Public,
        trailingContentDescription = stringResource(R.string.settings_open_chrome),
        onTrailingAction = onOpenBrowser,
    ) {
        Text(
            text = stringResource(R.string.settings_browser_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_browser_search_engine),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(10.dp))
                kira.ditto.browser.BuiltInBrowserSearchEngines.forEach { engine ->
                    val selected = preferences.searchEngineId == engine.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate(preferences.copy(searchEngineId = engine.id)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = engine.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = AetherPrimary,
                            )
                        }
                    }
                }
            }
            if (preferences.searchEngineId == "custom") {
                CardDivider()
                ChatGptTextField(
                    label = stringResource(R.string.settings_browser_custom_search),
                    value = customSearchValue,
                    onValueChange = {
                        customSearchValue = it
                        onUpdate(preferences.copy(customSearchTemplate = it.text))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            CardDivider()
            ChatGptTextField(
                label = stringResource(R.string.settings_browser_homepage),
                value = homepageValue,
                onValueChange = {
                    homepageValue = it
                    onUpdate(preferences.copy(homepage = it.text.ifBlank { kira.ditto.data.DefaultBrowserHomepage }))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_accept_cookies),
                    subtitle = stringResource(R.string.settings_browser_accept_cookies_subtitle),
                    checked = preferences.acceptCookies,
                    onCheckedChange = { onUpdate(preferences.copy(acceptCookies = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_block_third_party),
                    subtitle = stringResource(R.string.settings_browser_block_third_party_subtitle),
                    checked = preferences.blockThirdPartyCookies,
                    onCheckedChange = { onUpdate(preferences.copy(blockThirdPartyCookies = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_tracking_protection),
                    subtitle = stringResource(R.string.settings_browser_tracking_protection_subtitle),
                    checked = preferences.trackingProtection,
                    onCheckedChange = { onUpdate(preferences.copy(trackingProtection = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_agent_tracking),
                    subtitle = stringResource(R.string.settings_browser_agent_tracking_subtitle),
                    checked = preferences.agentTrackingProtection,
                    onCheckedChange = { onUpdate(preferences.copy(agentTrackingProtection = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_javascript),
                    subtitle = stringResource(R.string.settings_browser_javascript_subtitle),
                    checked = preferences.javascriptEnabled,
                    onCheckedChange = { onUpdate(preferences.copy(javascriptEnabled = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_desktop),
                    subtitle = stringResource(R.string.settings_browser_desktop_subtitle),
                    checked = preferences.desktopMode,
                    onCheckedChange = { onUpdate(preferences.copy(desktopMode = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_popups),
                    subtitle = stringResource(R.string.settings_browser_popups_subtitle),
                    checked = preferences.popupsBlocked,
                    onCheckedChange = { onUpdate(preferences.copy(popupsBlocked = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_restore_tabs),
                    subtitle = stringResource(R.string.settings_browser_restore_tabs_subtitle),
                    checked = preferences.restoreTabs,
                    onCheckedChange = { onUpdate(preferences.copy(restoreTabs = it)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_save_passwords),
                    subtitle = stringResource(R.string.settings_browser_save_passwords_subtitle),
                    checked = preferences.savePasswords,
                    onCheckedChange = { onUpdate(preferences.copy(savePasswords = it)) },
                )
                Spacer(Modifier.height(14.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_browser_autofill),
                    subtitle = stringResource(R.string.settings_browser_autofill_subtitle),
                    checked = preferences.autofillPasswords,
                    onCheckedChange = { onUpdate(preferences.copy(autofillPasswords = it)) },
                )
            }
            if (logins.isNotEmpty()) {
                CardDivider()
                logins.forEachIndexed { index, login ->
                    if (index > 0) CardDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(login.origin, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
                            Text(login.username, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
                        }
                        IconButton(
                            onClick = {
                                onDeleteLogin(login.id)
                                logins = onListLogins()
                            },
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_remove))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_browser_clear_data),
                    onClick = onClearData,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SkillsListPage(
    title: String,
    installedSkills: List<kira.ditto.data.InstalledSkill>,
    onToggleSkillEnabled: (String, Boolean) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        Text(
            text = stringResource(R.string.settings_skills_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (installedSkills.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.settings_no_skills_installed),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_import_skills_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.settings_add_skill),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            installedSkills.forEach { skill ->
                SkillCard(
                    skill = skill,
                    onToggleEnabled = { enabled -> onToggleSkillEnabled(skill.id, enabled) },
                    onRemove = { onRemoveSkill(skill.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: kira.ditto.data.InstalledSkill,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by rememberSaveable(skill.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurface)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ActionPreviewPill(label = skill.quickActionLabel())
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = Color(0xFFD25757),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = "",
            subtitle = "",
            checked = skill.isEnabled,
            onCheckedChange = onToggleEnabled,
        )
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(14.dp))
            DetailLine(stringResource(R.string.settings_skill_id), skill.id)
            DetailLine(stringResource(R.string.settings_skill_files), "${skill.resourceEntries.size}")
            DetailLine(stringResource(R.string.settings_skill_allowed_tools), skill.allowedTools.ifEmpty { listOf(stringResource(R.string.settings_any)) }.joinToString(", "))
            if (skill.compatibility.isNotBlank()) {
                DetailLine(stringResource(R.string.settings_skill_compatibility), skill.compatibility)
            }
            if (skill.source.label.isNotBlank()) {
                DetailLine(stringResource(R.string.settings_skill_source), skill.source.label)
            }
            DetailLine(stringResource(R.string.settings_skill_path), skill.skillRootPath)
        }
    }
}

// -----------------------------------------------------------------------------
// Add Skill Page
// -----------------------------------------------------------------------------

@Composable
private fun AddSkillPage(
    title: String,
    onImportSkillFolder: () -> Unit,
    onImportSkillZip: ((Boolean) -> Unit) -> Unit,
    onInstallSkillUrl: (String, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var skillUrlValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isInstalling by remember { mutableStateOf(false) }

    val tabOptions = listOf(
        stringResource(R.string.settings_skill_source_folder),
        "Zip",
        "URL",
    )

    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_add_skill_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Segmented button row
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabOptions.size),
                    onClick = { selectedTab = index },
                    selected = selectedTab == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = Color.White,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                // Folder import
                SettingsCardGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = AetherOnSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.settings_select_skill_folder_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsActionButton(
                            label = stringResource(R.string.settings_choose_folder),
                            onClick = {
                                onImportSkillFolder()
                                // Will navigate back via callback on success
                            },
                        )
                    }
                }
            }

            1 -> {
                // Zip import
                SettingsCardGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = AetherOnSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.settings_select_skill_zip_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SettingsActionButton(
                            label = stringResource(R.string.settings_choose_zip),
                            onClick = {
                                onImportSkillZip {}
                            },
                        )
                    }
                }
            }

            2 -> {
                // URL import
                SettingsCardGroup {
                    ChatGptTextField(
                        label = stringResource(R.string.settings_remote_skill_url),
                        value = skillUrlValue,
                        onValueChange = { skillUrlValue = it },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_remote_skill_url_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(16.dp))

                if (isInstalling) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = AetherPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_installing), color = AetherOnSurfaceVariant)
                    }
                } else {
                    SettingsActionButton(
                        label = stringResource(R.string.settings_install_from_url),
                        onClick = {
                            if (skillUrlValue.text.isNotBlank()) {
                                isInstalling = true
                                onInstallSkillUrl(skillUrlValue.text) { success ->
                                    isInstalling = false
                                    if (success) {
                                        skillUrlValue = TextFieldValue("")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeModStatusCard(
    state: AetherNativeModState,
    onAllowNativeModsOnNextStart: () -> Unit,
    onDisableNativeModsOnNextStart: () -> Unit,
) {
    val safeMode = state.safeModeActive
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (safeMode) {
                    Color(0xFFFFB020).copy(alpha = 0.14f)
                } else {
                    AetherSurfaceHigh
                }
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (safeMode) {
                    Icons.Rounded.WarningAmber
                } else {
                    Icons.Rounded.Code
                },
                contentDescription = null,
                tint = if (safeMode) Color(0xFFFFB020) else AetherPrimary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(
                    if (safeMode) {
                        R.string.settings_native_mod_safe_mode_title
                    } else {
                        R.string.settings_native_mod_active_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
            )
        }
        Text(
            text = if (safeMode) {
                stringResource(R.string.settings_native_mod_safe_mode_body)
            } else {
                stringResource(
                    R.string.settings_native_mod_active_summary,
                    state.loaded.size,
                    state.discovered.size,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        if (state.suspectedCrashModId.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.settings_native_mod_suspected,
                    state.suspectedCrashModId,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
        state.failures.take(4).forEach { failure ->
            Text(
                text = stringResource(
                    R.string.settings_native_mod_failure,
                    failure.id,
                    failure.message,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB020),
            )
        }
        if (state.restartRequired) {
            Text(
                text = stringResource(R.string.settings_native_mod_restart_required),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurface,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(2.dp))
        if (safeMode) {
            SettingsActionButton(
                label = stringResource(R.string.settings_native_mod_enable_next_start),
                onClick = onAllowNativeModsOnNextStart,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SettingsSubtleActionButton(
                label = stringResource(R.string.settings_native_mod_disable_next_start),
                onClick = onDisableNativeModsOnNextStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ScriptExtensionStatusCard(
    runtimeError: String,
    errors: List<AetherAppExtensionError>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.settings_script_extension_errors_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AetherOnSurface,
            )
        }
        runtimeError.takeIf(String::isNotBlank)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurface,
            )
        }
        errors.takeLast(5).forEach { error ->
            Text(
                text = stringResource(
                    R.string.settings_script_extension_error_item,
                    error.phase,
                    error.extensionId.ifBlank { error.path },
                    error.message,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PiExtensionsPage(
    installedExtensions: List<InstalledPiExtension>,
    hasLoadedInstalledExtensions: Boolean,
    nativeModState: AetherNativeModState,
    catalog: List<PiExtensionCatalogEntry>,
    isLoading: Boolean,
    catalogError: String,
    operationSource: String,
    onRefresh: () -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: (InstalledPiExtension) -> Unit,
    onSetEnabled: (InstalledPiExtension, Boolean) -> Unit,
    onImport: () -> Unit,
    onAllowNativeModsOnNextStart: () -> Unit,
    onDisableNativeModsOnNextStart: () -> Unit,
    onSelectPackage: (PiExtensionCatalogEntry) -> Unit,
    onBack: () -> Unit,
) {
    val scriptExtensionController = LocalAetherExtensionUiController.current
    val scriptExtensionErrors = scriptExtensionController?.snapshot?.errors.orEmpty()
    val scriptExtensionRuntimeError = scriptExtensionController?.runtimeError.orEmpty()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    LaunchedEffect(Unit) {
        onRefresh()
    }
    val installedSources = installedExtensions
        .filter { it.kind == PiExtensionInstallKind.Package }
        .map { it.source }
        .toSet()
    val query = searchValue.text.trim()
    val visibleCatalog = catalog
        .asSequence()
        .filter { entry ->
            query.isBlank() || listOf(
                entry.name,
                entry.description,
                entry.author,
                entry.source,
            ).any { it.contains(query, ignoreCase = true) }
        }
        .take(40)
        .toList()

    SubPageScaffold(
        title = stringResource(R.string.settings_pi_extensions),
        onBack = onBack,
        trailingIcon = Icons.Rounded.FileUpload,
        trailingEnabled = operationSource.isBlank(),
        trailingLoading = operationSource == "import",
        trailingContentDescription = stringResource(R.string.settings_import_extension),
        onTrailingAction = onImport,
    ) {
        Text(
            text = stringResource(R.string.settings_pi_extensions_warning),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (scriptExtensionRuntimeError.isNotBlank() || scriptExtensionErrors.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            ScriptExtensionStatusCard(
                runtimeError = scriptExtensionRuntimeError,
                errors = scriptExtensionErrors,
            )
        }

        if (
            nativeModState.safeModeActive ||
            nativeModState.discovered.isNotEmpty() ||
            nativeModState.loaded.isNotEmpty() ||
            nativeModState.failures.isNotEmpty()
        ) {
            Spacer(Modifier.height(14.dp))
            NativeModStatusCard(
                state = nativeModState,
                onAllowNativeModsOnNextStart = onAllowNativeModsOnNextStart,
                onDisableNativeModsOnNextStart = onDisableNativeModsOnNextStart,
            )
        }

        Spacer(Modifier.height(16.dp))

        val tabs = listOf(
            stringResource(R.string.settings_extension_installed),
            stringResource(R.string.settings_extension_discover),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    onClick = { selectedTab = index },
                    selected = selectedTab == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = AetherOnPrimary,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (selectedTab) {
            0 -> {
                if (!hasLoadedInstalledExtensions) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = AetherPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.settings_loading_installed_extensions),
                            color = AetherOnSurfaceVariant,
                        )
                    }
                } else if (installedExtensions.isEmpty()) {
                    SettingsCardGroup {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.settings_no_extensions_installed),
                                style = MaterialTheme.typography.titleMedium,
                                color = AetherOnSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.settings_install_or_import_extension),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherOnSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            SettingsActionButton(
                                label = stringResource(R.string.settings_import_extension),
                                onClick = onImport,
                                enabled = operationSource.isBlank(),
                                isLoading = operationSource == "import",
                            )
                        }
                    }
                } else {
                    installedExtensions.forEach { extension ->
                        InstalledPiExtensionCard(
                            extension = extension,
                            isOperating = operationSource == extension.id ||
                                operationSource == extension.source,
                            actionsEnabled = operationSource.isBlank(),
                            onUpdate = { onUpdate(extension.source) },
                            onRemove = { onRemove(extension) },
                            onSetEnabled = { enabled -> onSetEnabled(extension, enabled) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            else -> {
                SettingsCardGroup {
                    ChatGptTextField(
                        label = stringResource(R.string.settings_search_extensions),
                        value = searchValue,
                        onValueChange = { searchValue = it },
                    )
                }
                Spacer(Modifier.height(14.dp))

                when {
                    isLoading && catalog.isEmpty() -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = AetherPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.settings_loading_extensions),
                                color = AetherOnSurfaceVariant,
                            )
                        }
                    }

                    catalogError.isNotBlank() && catalog.isEmpty() -> {
                        SettingsCardGroup {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = catalogError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AetherOnSurfaceVariant,
                                )
                                Spacer(Modifier.height(14.dp))
                                SettingsActionButton(
                                    label = stringResource(R.string.action_retry),
                                    onClick = onRefresh,
                                )
                            }
                        }
                    }

                    visibleCatalog.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.settings_no_extensions_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                        )
                    }

                    else -> visibleCatalog.forEach { entry ->
                        PiExtensionCatalogCard(
                            entry = entry,
                            installed = installedSources.contains(entry.source),
                            onClick = { onSelectPackage(entry) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PiExtensionCatalogCard(
    entry: PiExtensionCatalogEntry,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AetherSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (entry.author.isNotBlank()) append(entry.author)
                        if (entry.author.isNotBlank() && entry.monthlyDownloads > 0) append(" · ")
                        if (entry.monthlyDownloads > 0) {
                            append(formatExtensionDownloads(entry.monthlyDownloads))
                            append("/mo")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.compatibilityIssue != null) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = stringResource(R.string.settings_package_may_be_incompatible),
                        tint = Color(0xFFFFB020),
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (installed) {
                    ActionPreviewPill(
                        label = stringResource(R.string.settings_extension_installed),
                    )
                }
            }
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = entry.source,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PiPackageDetailPage(
    entry: PiExtensionCatalogEntry,
    details: PiPackageDetails?,
    installed: Boolean,
    isLoading: Boolean,
    error: String,
    isOperating: Boolean,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val compatibilityIssue = details?.compatibilityIssue ?: entry.compatibilityIssue
    var showInstallWarning by rememberSaveable(entry.source) { mutableStateOf(false) }

    if (showInstallWarning && compatibilityIssue != null) {
        PiPackageInstallWarningDialog(
            packageName = entry.name,
            warning = piPackageCompatibilityMessage(compatibilityIssue),
            onDismiss = { showInstallWarning = false },
            onContinue = {
                showInstallWarning = false
                onInstall()
            },
        )
    }

    SubPageScaffold(
        title = entry.name,
        onBack = onBack,
    ) {
        when {
            isLoading && details == null -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = AetherPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_loading_package_details),
                        color = AetherOnSurfaceVariant,
                    )
                }
            }

            details == null -> {
                SettingsCardGroup {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = error.ifBlank {
                                stringResource(R.string.settings_package_details_unavailable)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        SettingsActionButton(
                            label = stringResource(R.string.action_retry),
                            onClick = onRetry,
                        )
                        if (!installed) {
                            Spacer(Modifier.height(10.dp))
                            if (isOperating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = AetherPrimary,
                                )
                            } else {
                                SettingsActionButton(
                                    label = stringResource(R.string.settings_install_package),
                                    onClick = {
                                        if (compatibilityIssue == null) {
                                            onInstall()
                                        } else {
                                            showInstallWarning = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                Text(
                    text = details.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (details.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = details.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AetherOnSurfaceVariant,
                    )
                }

                if (compatibilityIssue != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFB020).copy(alpha = 0.14f))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFFB020),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = piPackageCompatibilityMessage(compatibilityIssue),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                when {
                    isOperating -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = AetherPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.settings_installing),
                                color = AetherOnSurfaceVariant,
                            )
                        }
                    }

                    installed -> {
                        ActionPreviewPill(
                            label = stringResource(R.string.settings_extension_installed),
                        )
                    }

                    else -> {
                        SettingsActionButton(
                            label = stringResource(R.string.settings_install_package),
                            onClick = {
                                if (compatibilityIssue == null) {
                                    onInstall()
                                } else {
                                    showInstallWarning = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                SettingsCardGroup {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        PackageDetailLine(stringResource(R.string.settings_package_source), details.source)
                        PackageDetailLine(stringResource(R.string.settings_package_version), details.version)
                        PackageDetailLine(stringResource(R.string.settings_package_published), details.published)
                        PackageDetailLine(stringResource(R.string.settings_package_downloads), details.downloads)
                        PackageDetailLine(stringResource(R.string.settings_package_author), details.author)
                        PackageDetailLine(stringResource(R.string.settings_package_license), details.license)
                        PackageDetailLine(stringResource(R.string.settings_package_types), details.types.joinToString(", "))
                        PackageDetailLine(stringResource(R.string.settings_package_size), details.size)
                        PackageDetailLine(stringResource(R.string.settings_package_dependencies), details.dependencies)
                    }
                }

                val links = listOf(
                    stringResource(R.string.settings_package_npm) to details.npmUrl,
                    stringResource(R.string.settings_package_repository) to details.repositoryUrl,
                ).filter { it.second.isNotBlank() }
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        links.forEach { (label, url) ->
                            SmallChipButton(
                                label = label,
                                onClick = { runCatching { uriHandler.openUri(url) } },
                            )
                        }
                    }
                }

                if (details.readmeMarkdown.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.settings_package_readme),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    MarkdownContent(
                        markdown = details.readmeMarkdown,
                        onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageDetailLine(
    label: String,
    value: String,
) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun piPackageCompatibilityMessage(
    issue: PiPackageCompatibilityIssue,
): String = when (issue) {
    PiPackageCompatibilityIssue.InteractiveUi ->
        stringResource(R.string.settings_package_incompatible_interactive_ui)
    PiPackageCompatibilityIssue.Theme ->
        stringResource(R.string.settings_package_incompatible_theme)
    PiPackageCompatibilityIssue.Prompt ->
        stringResource(R.string.settings_package_incompatible_prompt)
    PiPackageCompatibilityIssue.Platform ->
        stringResource(R.string.settings_package_incompatible_platform)
}

@Composable
private fun PiPackageInstallWarningDialog(
    packageName: String,
    warning: String,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(28.dp))
                .background(AetherSurfaceHigh)
                .padding(20.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFFFB020),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.settings_package_install_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.settings_package_install_warning_body,
                    packageName,
                    warning,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = stringResource(R.string.settings_install_anyway),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InstalledPiExtensionCard(
    extension: InstalledPiExtension,
    isOperating: Boolean,
    actionsEnabled: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AetherSurfaceHigh)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (extension.version.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "v${extension.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOperating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = AetherPrimary,
                    )
                }
                Switch(
                    checked = extension.isEnabled,
                    onCheckedChange = onSetEnabled,
                    enabled = actionsEnabled && !isOperating,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AetherOnPrimary,
                        checkedTrackColor = AetherPrimary,
                    ),
                )
            }
        }
        if (extension.description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = extension.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        run {
            val extensionLabel = if (extension.extensionCount > 0) {
                stringResource(R.string.settings_package_extensions_count, extension.extensionCount)
            } else null
            val aetherExtensionLabel = if (extension.aetherExtensionCount > 0) {
                stringResource(
                    R.string.settings_package_aether_extensions_count,
                    extension.aetherExtensionCount,
                )
            } else null
            val nativeEntrypointLabel = if (extension.nativeEntrypointCount > 0) {
                stringResource(
                    R.string.settings_package_native_mods_count,
                    extension.nativeEntrypointCount,
                )
            } else null
            val skillLabel = if (extension.skillCount > 0) {
                stringResource(R.string.settings_package_skills_count, extension.skillCount)
            } else null
            val promptLabel = if (extension.promptCount > 0) {
                stringResource(R.string.settings_package_prompts_count, extension.promptCount)
            } else null
            val themeLabel = if (extension.themeCount > 0) {
                stringResource(R.string.settings_package_themes_count, extension.themeCount)
            } else null
            val resources = listOfNotNull(
                extensionLabel,
                aetherExtensionLabel,
                nativeEntrypointLabel,
                skillLabel,
                promptLabel,
                themeLabel,
            )
            if (resources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = resources.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = extension.source,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!isOperating) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (extension.kind == PiExtensionInstallKind.Package) {
                    SmallChipButton(
                        label = stringResource(R.string.settings_update),
                        onClick = onUpdate,
                        enabled = actionsEnabled,
                    )
                }
                SmallChipButton(
                    label = stringResource(R.string.action_remove),
                    onClick = onRemove,
                    isDestructive = true,
                    enabled = actionsEnabled,
                )
            }
        }
    }
}

private fun formatExtensionDownloads(downloads: Long): String = when {
    downloads >= 1_000_000 -> String.format(Locale.US, "%.1fM", downloads / 1_000_000.0)
    downloads >= 1_000 -> String.format(Locale.US, "%.1fK", downloads / 1_000.0)
    else -> downloads.toString()
}

// -----------------------------------------------------------------------------
// MCP Servers List Page (Refactored)
// -----------------------------------------------------------------------------

@Composable
private fun McpServersListPage(
    title: String,
    mcpServers: List<kira.ditto.data.McpServerConfig>,
    onToggleMcpServerEnabled: (String, Boolean) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onTestMcpServer: (String, McpServerTestOperation, (String) -> Unit) -> Unit,
    onEdit: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    var testResultText by rememberSaveable { mutableStateOf("") }
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        Text(
            text = stringResource(R.string.settings_mcp_servers_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (mcpServers.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.settings_no_mcp_servers),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_add_mcp_server_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.settings_add_server),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            mcpServers.forEach { server ->
                McpServerCard(
                    server = server,
                    onToggleEnabled = { enabled -> onToggleMcpServerEnabled(server.id, enabled) },
                    onEdit = { onEdit(server.id) },
                    onRemove = { onRemoveMcpServer(server.id) },
                    onTest = { operation ->
                        onTestMcpServer(server.id, operation) { result ->
                            testResultText = result
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (testResultText.isNotBlank()) {
                SettingsCardGroup {
                    Text(
                        text = testResultText,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun McpServerCard(
    server: kira.ditto.data.McpServerConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onTest: (McpServerTestOperation) -> Unit,
) {
    val isGmail = kira.ditto.data.GmailMcp.isShippedServerId(server.id)
    val isSpotify = kira.ditto.data.SpotifyMcp.isShippedServerId(server.id)
    val isAmap = kira.ditto.data.AmapMcp.isShippedServerId(server.id)
    val isGithub = kira.ditto.data.GithubMcp.isShippedServerId(server.id)
    val isHuggingFace = kira.ditto.data.HuggingFaceMcp.isShippedServerId(server.id)
    val isShipped = isGmail || isSpotify || isAmap || isGithub || isHuggingFace
    val context = LocalContext.current
    val store = remember(context) { HostSecretStore(context) }
    val scope = rememberCoroutineScope()
    var oauthRevision by remember { mutableIntStateOf(0) }
    val gmailSnapshot = remember(oauthRevision) { GmailAuth.snapshot(store) }
    val spotifySnapshot = remember(oauthRevision) { SpotifyAuth.snapshot(store) }
    val amapSnapshot = remember(oauthRevision) { AmapAuth.snapshot(store) }
    val githubSnapshot = remember(oauthRevision) { GithubAuth.snapshot(store) }
    val huggingFaceSnapshot = remember(oauthRevision) { HuggingFaceAuth.snapshot(store) }
    var amapKeyDraft by remember(server.id) { mutableStateOf("") }
    var githubTokenDraft by remember(server.id) { mutableStateOf("") }
    var huggingFaceTokenDraft by remember(server.id) { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(server.id) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val amapSaveIntoView = remember { BringIntoViewRequester() }
    val githubSaveIntoView = remember { BringIntoViewRequester() }
    val huggingFaceSaveIntoView = remember { BringIntoViewRequester() }
    fun persistAmapKey() {
        val draft = amapKeyDraft
        if (draft.isBlank() || oauthBusy) return
        keyboardController?.hide()
        oauthBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AmapAuth.save(store, draft) }
            }
            oauthBusy = false
            oauthRevision += 1
            result.fold(
                onSuccess = { snapshot ->
                    if (snapshot.isConnected) {
                        amapKeyDraft = ""
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_amap_mcp_connected),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_amap_mcp_save_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { error ->
                    android.widget.Toast.makeText(
                        context,
                        error.message?.trim().orEmpty().ifBlank {
                            context.getString(R.string.settings_amap_mcp_save_failed)
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }
    fun persistGithubToken() {
        val draft = githubTokenDraft
        if (draft.isBlank() || oauthBusy) return
        keyboardController?.hide()
        oauthBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GithubAuth.save(store, draft) }
            }
            oauthBusy = false
            oauthRevision += 1
            result.fold(
                onSuccess = { snapshot ->
                    if (snapshot.isConnected) {
                        githubTokenDraft = ""
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_github_mcp_connected),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_github_mcp_save_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { error ->
                    android.widget.Toast.makeText(
                        context,
                        error.message?.trim().orEmpty().ifBlank {
                            context.getString(R.string.settings_github_mcp_save_failed)
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }
    fun persistHuggingFaceToken() {
        val draft = huggingFaceTokenDraft
        if (draft.isBlank() || oauthBusy) return
        keyboardController?.hide()
        oauthBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { HuggingFaceAuth.save(store, draft) }
            }
            oauthBusy = false
            oauthRevision += 1
            result.fold(
                onSuccess = { snapshot ->
                    if (snapshot.isConnected) {
                        huggingFaceTokenDraft = ""
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_huggingface_mcp_connected),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_huggingface_mcp_save_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { error ->
                    android.widget.Toast.makeText(
                        context,
                        error.message?.trim().orEmpty().ifBlank {
                            context.getString(R.string.settings_huggingface_mcp_save_failed)
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        isGmail && gmailSnapshot.isConnected && gmailSnapshot.email.isNotBlank() ->
                            stringResource(R.string.settings_gmail_mcp_connected_as, gmailSnapshot.email)
                        isGmail && gmailSnapshot.isConnected ->
                            stringResource(R.string.settings_gmail_mcp_connected)
                        isGmail -> stringResource(R.string.settings_gmail_mcp_not_connected)
                        isSpotify && spotifySnapshot.isConnected && spotifySnapshot.displayName.isNotBlank() ->
                            stringResource(R.string.settings_spotify_mcp_connected_as, spotifySnapshot.displayName)
                        isSpotify && spotifySnapshot.isConnected ->
                            stringResource(R.string.settings_spotify_mcp_connected)
                        isSpotify -> stringResource(R.string.settings_spotify_mcp_not_connected)
                        isAmap && amapSnapshot.isConnected ->
                            stringResource(R.string.settings_amap_mcp_connected)
                        isAmap -> stringResource(R.string.settings_amap_mcp_not_connected)
                        isGithub && githubSnapshot.isConnected ->
                            stringResource(R.string.settings_github_mcp_connected)
                        isGithub -> stringResource(R.string.settings_github_mcp_not_connected)
                        isHuggingFace && huggingFaceSnapshot.isConnected ->
                            stringResource(R.string.settings_huggingface_mcp_connected)
                        isHuggingFace -> stringResource(R.string.settings_huggingface_mcp_not_connected)
                        else -> server.transport.transportType.storageValue.uppercase()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ActionPreviewPill(label = server.quickActionLabel())
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropDown else Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (!isShipped) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                        tint = AetherOnSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = Color(0xFFD25757),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (isGmail || isSpotify) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    label = when {
                        isGmail && gmailSnapshot.isConnected ->
                            stringResource(R.string.settings_gmail_mcp_reconnect)
                        isGmail -> stringResource(R.string.settings_gmail_mcp_connect)
                        spotifySnapshot.isConnected ->
                            stringResource(R.string.settings_spotify_mcp_reconnect)
                        else -> stringResource(R.string.settings_spotify_mcp_connect)
                    },
                    onClick = {
                        oauthBusy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (isGmail) {
                                        GmailAuth.connectWithBrowser(
                                            context,
                                            store,
                                            force = gmailSnapshot.isConnected,
                                        )
                                    } else {
                                        SpotifyAuth.connectWithBrowser(
                                            context,
                                            store,
                                            force = spotifySnapshot.isConnected,
                                        )
                                    }
                                }
                            }
                            oauthBusy = false
                            oauthRevision += 1
                            result.onFailure { error ->
                                android.widget.Toast.makeText(
                                    context,
                                    error.message.orEmpty().ifBlank {
                                        context.getString(
                                            if (isGmail) R.string.settings_gmail_mcp_connect_failed
                                            else R.string.settings_spotify_mcp_connect_failed,
                                        )
                                    },
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    enabled = !oauthBusy,
                    isLoading = oauthBusy,
                    modifier = Modifier.weight(1f),
                )
                if (isGmail && gmailSnapshot.isConnected) {
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_gmail_mcp_disconnect),
                        onClick = {
                            GmailAuth.disconnect(store)
                            oauthRevision += 1
                        },
                    )
                }
                if (isSpotify && spotifySnapshot.isConnected) {
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_spotify_mcp_disconnect),
                        onClick = {
                            SpotifyAuth.disconnect(store)
                            oauthRevision += 1
                        },
                    )
                }
            }
        }

        if (isAmap) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_amap_mcp_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    label = stringResource(R.string.settings_amap_mcp_open_console),
                    onClick = {
                        runCatching { AmapAuth.openKeyConsole(context) }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (amapSnapshot.isConnected) {
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_amap_mcp_disconnect),
                        onClick = {
                            AmapAuth.disconnect(store)
                            amapKeyDraft = ""
                            oauthRevision += 1
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(amapSaveIntoView),
            ) {
                BasicTextField(
                    value = amapKeyDraft,
                    onValueChange = { amapKeyDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AetherSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(250)
                                    amapSaveIntoView.bringIntoView()
                                }
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                    cursorBrush = SolidColor(AetherPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistAmapKey() }),
                    decorationBox = { inner ->
                        if (amapKeyDraft.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_amap_mcp_key_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherOnSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionButton(
                    label = stringResource(R.string.settings_amap_mcp_save_key),
                    onClick = { persistAmapKey() },
                    enabled = amapKeyDraft.isNotBlank() && !oauthBusy,
                    isLoading = oauthBusy && isAmap,
                )
            }
        }

        if (isGithub) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_github_mcp_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    label = stringResource(R.string.settings_github_mcp_open_console),
                    onClick = {
                        runCatching { GithubAuth.openConsole(context) }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (githubSnapshot.isConnected) {
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_github_mcp_disconnect),
                        onClick = {
                            GithubAuth.disconnect(store)
                            githubTokenDraft = ""
                            oauthRevision += 1
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(githubSaveIntoView),
            ) {
                BasicTextField(
                    value = githubTokenDraft,
                    onValueChange = { githubTokenDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AetherSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(250)
                                    githubSaveIntoView.bringIntoView()
                                }
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                    cursorBrush = SolidColor(AetherPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistGithubToken() }),
                    decorationBox = { inner ->
                        if (githubTokenDraft.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_github_mcp_token_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherOnSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionButton(
                    label = stringResource(R.string.settings_github_mcp_save_token),
                    onClick = { persistGithubToken() },
                    enabled = githubTokenDraft.isNotBlank() && !oauthBusy,
                    isLoading = oauthBusy && isGithub,
                )
            }
        }

        if (isHuggingFace) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_huggingface_mcp_hint),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsActionButton(
                    label = stringResource(R.string.settings_huggingface_mcp_open_console),
                    onClick = {
                        runCatching { HuggingFaceAuth.openConsole(context) }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (huggingFaceSnapshot.isConnected) {
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.settings_huggingface_mcp_disconnect),
                        onClick = {
                            HuggingFaceAuth.disconnect(store)
                            huggingFaceTokenDraft = ""
                            oauthRevision += 1
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(huggingFaceSaveIntoView),
            ) {
                BasicTextField(
                    value = huggingFaceTokenDraft,
                    onValueChange = { huggingFaceTokenDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AetherSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(250)
                                    huggingFaceSaveIntoView.bringIntoView()
                                }
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                    cursorBrush = SolidColor(AetherPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistHuggingFaceToken() }),
                    decorationBox = { inner ->
                        if (huggingFaceTokenDraft.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_huggingface_mcp_token_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherOnSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionButton(
                    label = stringResource(R.string.settings_huggingface_mcp_save_token),
                    onClick = { persistHuggingFaceToken() },
                    enabled = huggingFaceTokenDraft.isNotBlank() && !oauthBusy,
                    isLoading = oauthBusy && isHuggingFace,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = "",
            subtitle = "",
            checked = server.isEnabled,
            onCheckedChange = onToggleEnabled,
        )
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_mcp_tools),
                    onClick = { onTest(McpServerTestOperation.ListTools) },
                    modifier = Modifier.weight(1f),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_mcp_resources),
                    onClick = { onTest(McpServerTestOperation.ListResources) },
                    modifier = Modifier.weight(1f),
                )
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_mcp_prompts),
                    onClick = { onTest(McpServerTestOperation.ListPrompts) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            DetailLine(stringResource(R.string.settings_server_id), server.id)
            DetailLine(stringResource(R.string.settings_quick_action), server.quickActionLabel())
            DetailLine(stringResource(R.string.settings_transport), server.transport.transportType.storageValue.uppercase())
            when (val transport = server.transport) {
                is kira.ditto.data.McpTransportConfig.StreamableHttp -> {
                    DetailLine("URL", transport.url)
                    DetailLine(stringResource(R.string.settings_headers), transport.headers.size.toString())
                }

                is kira.ditto.data.McpTransportConfig.StdIo -> {
                    DetailLine(stringResource(R.string.settings_command), transport.command)
                    if (transport.workingDirectory.isNotBlank()) {
                        DetailLine(stringResource(R.string.settings_working_dir), transport.workingDirectory)
                    }
                    DetailLine(stringResource(R.string.settings_environment), transport.environment.size.toString())
                }

                is kira.ditto.data.McpTransportConfig.UpaManifest -> {
                    DetailLine("UPA", transport.pluginId)
                }
            }
            DetailLine(stringResource(R.string.settings_connect_timeout), "${server.connectTimeoutMillis} ms")
            DetailLine(stringResource(R.string.settings_request_timeout), "${server.requestTimeoutMillis} ms")
        }
    }
}

// -----------------------------------------------------------------------------
// Scheduled Tasks
// -----------------------------------------------------------------------------

@Composable
private fun ScheduledTasksPage(
    tasks: List<ScheduledTask>,
    onToggleEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onEdit: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    SubPageScaffold(
        title = stringResource(R.string.settings_scheduled_tasks),
        onBack = onBack,
        trailingIcon = Icons.Rounded.Add,
        onTrailingAction = onAddNew,
    ) {
        Text(
            text = stringResource(R.string.settings_scheduled_tasks_official_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        if (tasks.isEmpty()) {
            SettingsCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.settings_no_scheduled_tasks),
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_create_scheduled_task_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.settings_add_task),
                        onClick = onAddNew,
                    )
                }
            }
        } else {
            tasks.sortedWith(compareBy<ScheduledTask> { it.nextRunAtMillis ?: Long.MAX_VALUE }.thenBy { it.name })
                .forEach { task ->
                    ScheduledTaskCard(
                        task = task,
                        onToggleEnabled = { enabled -> onToggleEnabled(task.id, enabled) },
                        onEdit = { onEdit(task.id) },
                        onRemove = { onRemove(task.id) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
        }
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task.schedule.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.action_edit),
                    tint = AetherOnSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color(0xFFD25757),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = task.prompt.take(160),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.nextRunAtMillis?.formatScheduledTaskTime()
                        ?.let { stringResource(R.string.settings_next_run, it) }
                        ?: stringResource(R.string.settings_no_next_run_scheduled),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Text(
                    text = if (task.createdBy == ScheduledTaskCreator.Agent) {
                        stringResource(R.string.settings_created_by_agent)
                    } else {
                        stringResource(R.string.settings_created_manually)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            Switch(
                checked = task.isEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

@Composable
private fun ScheduledTaskEditPage(
    existingTask: ScheduledTask?,
    onSave: (String, String, ScheduledTaskSchedule, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val existingInterval = existingTask?.schedule as? ScheduledTaskSchedule.Interval
    val existingDaily = existingTask?.schedule as? ScheduledTaskSchedule.Daily
    val existingWeekly = existingTask?.schedule as? ScheduledTaskSchedule.Weekly
    var nameValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingTask?.name.orEmpty()))
    }
    var promptValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingTask?.prompt.orEmpty()))
    }
    var enabledValue by rememberSaveable(existingTask?.id) {
        mutableStateOf(existingTask?.isEnabled ?: true)
    }
    var scheduleMode by rememberSaveable(existingTask?.id) {
        mutableIntStateOf(
            when (existingTask?.schedule) {
                is ScheduledTaskSchedule.Daily -> 1
                is ScheduledTaskSchedule.Weekly -> 2
                else -> 0
            }
        )
    }
    var intervalMinutesValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(((existingInterval?.intervalMillis ?: 60L * 60L * 1000L) / 60_000L).toString()))
    }
    var activeStartValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingInterval?.activeStartMinuteOfDay?.let(::formatTaskMinute).orEmpty()))
    }
    var activeEndValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingInterval?.activeEndMinuteOfDay?.let(::formatTaskMinute).orEmpty()))
    }
    var dailyTimesValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingDaily?.timesMinutesOfDay?.joinToString(",") { formatTaskMinute(it) } ?: "09:00"))
    }
    var weeklyDaysValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingWeekly?.daysOfWeek?.joinToString(",") ?: "1"))
    }
    var weeklyTimeValue by rememberSaveable(existingTask?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingWeekly?.minuteOfDay?.let(::formatTaskMinute) ?: "09:00"))
    }

    fun buildSchedule(): ScheduledTaskSchedule? = when (scheduleMode) {
        0 -> ScheduledTaskSchedule.Interval(
            intervalMillis = (intervalMinutesValue.text.trim().toLongOrNull() ?: 60L).coerceAtLeast(1L) * 60_000L,
            activeStartMinuteOfDay = parseTaskMinute(activeStartValue.text),
            activeEndMinuteOfDay = parseTaskMinute(activeEndValue.text),
        )
        1 -> parseTaskTimes(dailyTimesValue.text).takeIf { it.isNotEmpty() }?.let { times ->
            ScheduledTaskSchedule.Daily(timesMinutesOfDay = times)
        }
        else -> {
            val days = parseTaskDays(weeklyDaysValue.text)
            val time = parseTaskMinute(weeklyTimeValue.text)
            if (days.isEmpty() || time == null) null else ScheduledTaskSchedule.Weekly(days, time)
        }
    }

    SubPageScaffold(
        title = if (existingTask == null) {
            stringResource(R.string.settings_add_scheduled_task)
        } else {
            stringResource(R.string.settings_edit_scheduled_task)
        },
        onBack = onBack,
        trailingIcon = Icons.Rounded.Check,
        trailingEnabled = nameValue.text.isNotBlank() && promptValue.text.isNotBlank(),
        onTrailingAction = {
            buildSchedule()?.let { schedule ->
                onSave(nameValue.text, promptValue.text, schedule, enabledValue)
            }
        },
    ) {
        SettingsCardGroup {
            ChatGptTextField(
                label = stringResource(R.string.settings_name),
                value = nameValue,
                onValueChange = { nameValue = it },
            )
            CardDivider()
            ChatGptTextField(
                label = stringResource(R.string.settings_prompt),
                value = promptValue,
                minLines = 4,
                onValueChange = { promptValue = it },
            )
        }
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_enabled),
                    subtitle = stringResource(R.string.settings_disabled_tasks_saved_subtitle),
                    checked = enabledValue,
                    onCheckedChange = { enabledValue = it },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                stringResource(R.string.settings_schedule_interval),
                stringResource(R.string.settings_schedule_daily),
                stringResource(R.string.settings_schedule_weekly),
            ).forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    onClick = { scheduleMode = index },
                    selected = scheduleMode == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = Color.White,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            when (scheduleMode) {
                0 -> {
                    ChatGptTextField(
                        label = stringResource(R.string.settings_every_n_minutes),
                        value = intervalMinutesValue,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onValueChange = { intervalMinutesValue = it.copy(text = it.text.filter(Char::isDigit)) },
                    )
                    CardDivider()
                    ChatGptTextField(
                        label = stringResource(R.string.settings_active_start_time),
                        value = activeStartValue,
                        onValueChange = { activeStartValue = it },
                    )
                    CardDivider()
                    ChatGptTextField(
                        label = stringResource(R.string.settings_active_end_time),
                        value = activeEndValue,
                        onValueChange = { activeEndValue = it },
                    )
                }
                1 -> ChatGptTextField(
                    label = stringResource(R.string.settings_times_comma_separated),
                    value = dailyTimesValue,
                    onValueChange = { dailyTimesValue = it },
                )
                else -> {
                    ChatGptTextField(
                        label = stringResource(R.string.settings_days_of_week_input),
                        value = weeklyDaysValue,
                        onValueChange = { weeklyDaysValue = it },
                    )
                    CardDivider()
                    ChatGptTextField(
                        label = stringResource(R.string.settings_time_hhmm),
                        value = weeklyTimeValue,
                        onValueChange = { weeklyTimeValue = it },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_interval_window_hint),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Add MCP Server Page
// -----------------------------------------------------------------------------

@Composable
private fun AddMcpServerPage(
    title: String,
    existingServer: kira.ditto.data.McpServerConfig?,
    onSaveHttpMcpServer: (String?, String, String, String) -> Unit,
    onSaveStdIoMcpServer: (String?, String, String, String, String, String, LocalRuntimeId?) -> Unit,
    onBack: () -> Unit,
) {
    val isEditing = existingServer != null
    val existingHttpTransport = existingServer?.transport as? kira.ditto.data.McpTransportConfig.StreamableHttp
    val existingStdIoTransport = existingServer?.transport as? kira.ditto.data.McpTransportConfig.StdIo
    var selectedTab by rememberSaveable(existingServer?.id) {
        mutableIntStateOf(if (existingStdIoTransport != null) 1 else 0)
    }

    var httpServerNameValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingServer?.displayName.orEmpty()))
    }
    var httpServerUrlValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingHttpTransport?.url.orEmpty()))
    }
    var httpHeadersValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                existingHttpTransport?.headers
                    ?.joinToString("\n") { header -> "${header.key}=${header.value}" }
                    .orEmpty()
            )
        )
    }

    var stdioServerNameValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingServer?.displayName.orEmpty()))
    }
    var stdioCommandValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingStdIoTransport?.command.orEmpty()))
    }
    var stdioArgumentsValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingStdIoTransport?.arguments?.joinToString(" ").orEmpty()))
    }
    var stdioWorkingDirectoryValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(existingStdIoTransport?.workingDirectory.orEmpty()))
    }
    var stdioEnvValue by rememberSaveable(existingServer?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                existingStdIoTransport?.environment
                    ?.joinToString("\n") { env -> "${env.key}=${env.value}" }
                    .orEmpty()
            )
        )
    }
    var stdioRuntimeEnvironment by rememberSaveable(existingServer?.id) {
        mutableStateOf(existingStdIoTransport?.runtimeEnvironment)
    }
    val context = LocalContext.current
    val secretStore = remember(context) { HostSecretStore(context) }
    var secretRevision by remember { mutableIntStateOf(0) }
    var secretFields by remember(existingServer?.id) {
        mutableStateOf(emptyMap<String, TextFieldValue>())
    }

    val httpDraft = kira.ditto.data.McpServerConfig(
        id = existingServer?.id ?: "draft",
        displayName = httpServerNameValue.text.ifBlank { "mcp" },
        transport = kira.ditto.data.McpTransportConfig.StreamableHttp(
            url = httpServerUrlValue.text,
            headers = parseMcpKeyValueLines(httpHeadersValue.text),
        ),
    )
    val stdioDraft = kira.ditto.data.McpServerConfig(
        id = existingServer?.id ?: "draft",
        displayName = stdioServerNameValue.text.ifBlank { "mcp" },
        transport = kira.ditto.data.McpTransportConfig.StdIo(
            command = stdioCommandValue.text,
            arguments = stdioArgumentsValue.text.split(" ").filter { it.isNotBlank() },
            workingDirectory = stdioWorkingDirectoryValue.text,
            environment = parseMcpKeyValueLines(stdioEnvValue.text),
            runtimeEnvironment = stdioRuntimeEnvironment,
        ),
    )
    val httpSlots = inferMcpSecretSlots(httpDraft)
    val stdioSlots = inferMcpSecretSlots(stdioDraft)
    val configuredSecretIds = remember(secretRevision, existingServer?.id, httpSlots, stdioSlots) {
        (httpSlots + stdioSlots).filter { secretStore.has(it.id) }.map { it.id }.toSet()
    }

    fun secretDraftMap(): Map<String, String> {
        val slots = if (selectedTab == 0) httpSlots else stdioSlots
        return slots.associate { slot ->
            mcpSecretDraftKey(slot.kind, slot.injectKey) to secretFields[slot.id]?.text.orEmpty()
        }
    }

    val tabOptions = listOf("HTTP", stringResource(R.string.settings_stdio))

    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = if (isEditing) {
                stringResource(R.string.settings_update_mcp_server_description)
            } else {
                stringResource(R.string.settings_add_mcp_server_page_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Segmented button row
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabOptions.size),
                    onClick = { selectedTab = index },
                    selected = selectedTab == index,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AetherPrimary,
                        activeContentColor = Color.White,
                        inactiveContainerColor = AetherSurfaceHigh,
                        inactiveContentColor = AetherOnSurface,
                    ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                // HTTP server
                SettingsCardGroup {
                    ChatGptTextField(stringResource(R.string.settings_server_name), httpServerNameValue) { httpServerNameValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_server_url), httpServerUrlValue) { httpServerUrlValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_headers), httpHeadersValue, minLines = 2) { httpHeadersValue = it }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_optional_headers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                if (httpSlots.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    McpSecretSlotsEditor(
                        servers = listOf(httpDraft),
                        slots = httpSlots,
                        values = secretFields,
                        configuredIds = configuredSecretIds,
                        onValueChange = { id, next -> secretFields = secretFields + (id to next) },
                        onSaveSlot = if (existingServer != null) {
                            { slot ->
                                val value = secretFields[slot.id]?.text.orEmpty().trim()
                                if (value.isNotBlank()) {
                                    runCatching { secretStore.put(slot.id, value) }
                                    secretFields = secretFields + (slot.id to TextFieldValue(""))
                                    secretRevision += 1
                                }
                            }
                        } else {
                            null
                        },
                        onDeleteSlot = if (existingServer != null) {
                            { slot ->
                                runCatching { secretStore.delete(slot.id) }
                                secretFields = secretFields + (slot.id to TextFieldValue(""))
                                secretRevision += 1
                            }
                        } else {
                            null
                        },
                        title = stringResource(R.string.settings_mcp_secrets),
                        body = stringResource(R.string.settings_mcp_secrets_hint),
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsActionButton(
                    label = if (isEditing) stringResource(R.string.settings_save_http_server) else stringResource(R.string.settings_add_http_server),
                    onClick = {
                        if (httpServerNameValue.text.isNotBlank() && httpServerUrlValue.text.isNotBlank()) {
                            val folded = foldMcpSecretDrafts(httpDraft, secretDraftMap())
                            val http = folded.transport as kira.ditto.data.McpTransportConfig.StreamableHttp
                            onSaveHttpMcpServer(
                                existingServer?.id,
                                httpServerNameValue.text,
                                http.url,
                                http.headers.joinToString("\n") { header -> "${header.key}=${header.value}" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            1 -> {
                // Stdio server
                SettingsCardGroup {
                    ChatGptTextField(stringResource(R.string.settings_server_name), stdioServerNameValue) { stdioServerNameValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_command), stdioCommandValue, minLines = 2) { stdioCommandValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_arguments), stdioArgumentsValue, minLines = 2) { stdioArgumentsValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_working_directory), stdioWorkingDirectoryValue) { stdioWorkingDirectoryValue = it }
                    CardDivider()
                    ChatGptTextField(stringResource(R.string.settings_environment), stdioEnvValue, minLines = 2) { stdioEnvValue = it }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_optional_environment_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                if (stdioSlots.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    McpSecretSlotsEditor(
                        servers = listOf(stdioDraft),
                        slots = stdioSlots,
                        values = secretFields,
                        configuredIds = configuredSecretIds,
                        onValueChange = { id, next -> secretFields = secretFields + (id to next) },
                        onSaveSlot = if (existingServer != null) {
                            { slot ->
                                val value = secretFields[slot.id]?.text.orEmpty().trim()
                                if (value.isNotBlank()) {
                                    runCatching { secretStore.put(slot.id, value) }
                                    secretFields = secretFields + (slot.id to TextFieldValue(""))
                                    secretRevision += 1
                                }
                            }
                        } else {
                            null
                        },
                        onDeleteSlot = if (existingServer != null) {
                            { slot ->
                                runCatching { secretStore.delete(slot.id) }
                                secretFields = secretFields + (slot.id to TextFieldValue(""))
                                secretRevision += 1
                            }
                        } else {
                            null
                        },
                        title = stringResource(R.string.settings_mcp_secrets),
                        body = stringResource(R.string.settings_mcp_secrets_hint),
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsCardGroup {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_runtime_environment),
                            style = MaterialTheme.typography.labelLarge,
                            color = AetherOnSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_runtime_environment_default_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                        )
                        SettingsChoiceRow(
                            title = stringResource(R.string.settings_default_runtime),
                            subtitle = stringResource(R.string.settings_default_runtime_help),
                            selected = stdioRuntimeEnvironment == null,
                            onClick = { stdioRuntimeEnvironment = null },
                        )
                        SettingsChoiceRow(
                            title = "Alpine",
                            subtitle = stringResource(R.string.settings_runtime_alpine_stdio_subtitle),
                            selected = stdioRuntimeEnvironment == LocalRuntimeId.Alpine,
                            onClick = { stdioRuntimeEnvironment = LocalRuntimeId.Alpine },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                SettingsActionButton(
                    label = if (isEditing) stringResource(R.string.settings_save_stdio_server) else stringResource(R.string.settings_add_stdio_server),
                    onClick = {
                        if (stdioServerNameValue.text.isNotBlank() && stdioCommandValue.text.isNotBlank()) {
                            val folded = foldMcpSecretDrafts(stdioDraft, secretDraftMap())
                            val stdio = folded.transport as kira.ditto.data.McpTransportConfig.StdIo
                            onSaveStdIoMcpServer(
                                existingServer?.id,
                                stdioServerNameValue.text,
                                stdioCommandValue.text,
                                stdioArgumentsValue.text,
                                stdioWorkingDirectoryValue.text,
                                stdio.environment.joinToString("\n") { env -> "${env.key}=${env.value}" },
                                stdioRuntimeEnvironment,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Termux sub-page
// -----------------------------------------------------------------------------

@Composable
private fun TermuxSettingsPage(
    title: String,
    termuxSetupState: TermuxSetupState,
    rootSetupState: RootSetupState,
    environmentVariables: List<TermuxEnvironmentVariable>,
    onEnvironmentVariablesChanged: (List<TermuxEnvironmentVariable>) -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onRefreshRootSetup: () -> Unit,
    onConfigureWithRoot: () -> Unit,
    onBack: () -> Unit,
) {
    var showAlreadyConfiguredDialog by rememberSaveable { mutableStateOf(false) }

    fun requestRootSetup() {
        if (termuxSetupState.isReady && !rootSetupState.isRunning) {
            showAlreadyConfiguredDialog = true
        } else {
            onConfigureWithRoot()
        }
    }

    if (showAlreadyConfiguredDialog) {
        RootSetupAlreadyConfiguredDialog(
            title = stringResource(R.string.settings_termux_already_configured_title),
            body = stringResource(R.string.settings_termux_already_configured_body),
            onDismiss = { showAlreadyConfiguredDialog = false },
            onContinue = {
                showAlreadyConfiguredDialog = false
                onConfigureWithRoot()
            },
        )
    }

    LaunchedEffect(Unit) { onRefreshRootSetup() }
    SettingsAutoRefreshEffect(refresh = onRefreshTermuxSetup)
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Refresh,
        onTrailingAction = onRefreshTermuxSetup,
    ) {
        Text(
            text = stringResource(R.string.settings_termux_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        TermuxEnvironmentVariablesSection(
            variables = environmentVariables,
            onVariablesChanged = onEnvironmentVariablesChanged,
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            RootSetupSettingsSection(
                title = stringResource(R.string.settings_root_automatic_setup),
                rootSetupState = rootSetupState,
                body = rootSetupSettingsBody(rootSetupState),
                onConfigureWithRoot = ::requestRootSetup,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (termuxSetupState.isReady) {
            SettingsCardGroup {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_termux_connected), style = MaterialTheme.typography.labelLarge, color = AetherOnSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_termux_connected_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        } else {
            TermuxSetupNotice(
                setupState = termuxSetupState,
                onRequestPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefresh = onRefreshTermuxSetup,
                showRefreshAction = false,
            )
        }
    }
}

@Composable
private fun RuntimeCleanupDeveloperSettingsSection(
    autoCleanOldCommandHistory: Boolean,
    oldCommandHistoryRetentionHours: TextFieldValue,
    onAutoCleanOldCommandHistoryChanged: (Boolean) -> Unit,
    onOldCommandHistoryRetentionHoursChanged: (TextFieldValue) -> Unit,
) {
    SettingsCardGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_old_command_history_retention_hours),
                subtitle = stringResource(R.string.settings_old_command_history_retention_hours_description),
                checked = autoCleanOldCommandHistory,
                onCheckedChange = onAutoCleanOldCommandHistoryChanged,
            )
            AnimatedVisibility(visible = autoCleanOldCommandHistory) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    ChatGptTextField(
                        value = oldCommandHistoryRetentionHours,
                        onValueChange = { value ->
                            onOldCommandHistoryRetentionHoursChanged(
                                value.copy(text = value.text.filter(Char::isDigit)),
                            )
                        },
                        label = stringResource(R.string.settings_old_command_history_retention_hours_value),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_old_command_history_retention_hours_value_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlpineSettingsPage(
    title: String,
    setupState: LocalRuntimeSetupState,
    packageProfiles: Map<String, PackageProfileState>,
    installProgress: Map<String, AlpineSetupProgress>,
    isDefaultRuntime: Boolean,
    onInitialize: () -> Unit,
    onReset: () -> Unit,
    onRefresh: () -> Unit,
    onInstallPackageProfile: (String) -> Unit,
    onSetDefault: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onRefresh()
    }
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Terminal,
        trailingEnabled = setupState.isReady,
        trailingContentDescription = stringResource(R.string.settings_open_terminal),
        onTrailingAction = onOpenTerminal,
        secondaryTrailingIcon = Icons.Rounded.Folder,
        secondaryTrailingEnabled = setupState.isReady,
        secondaryTrailingContentDescription = "Open files",
        onSecondaryTrailingAction = onOpenFiles,
    ) {
        Text(
            text = stringResource(R.string.settings_alpine_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_runtime_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = alpineSetupStatusText(setupState),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                if (setupState.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = setupState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsActionButton(
                        label = when (setupState.issue) {
                            LocalRuntimeIssue.Ready -> stringResource(R.string.settings_ready)
                            else -> stringResource(R.string.settings_initialize)
                        },
                        onClick = onInitialize,
                        modifier = Modifier.weight(1f),
                        enabled = !setupState.isReady,
                    )
                    SettingsSubtleActionButton(
                        label = stringResource(R.string.common_refresh),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_reset_alpine_data),
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (setupState.isReady && !isDefaultRuntime) {
                    Spacer(Modifier.height(10.dp))
                    SettingsActionButton(
                        label = stringResource(R.string.settings_use_as_default_runtime),
                        onClick = onSetDefault,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_environment_presets),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_environment_presets_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                AlpineProfileRow(
                    title = stringResource(R.string.settings_python_environment),
                    subtitle = "python3, pip, virtualenv",
                    profileState = packageProfiles["python"],
                    installProgress = installProgress["python"],
                    enabled = setupState.isReady,
                    onInstall = { onInstallPackageProfile("python") },
                )
                CardDivider()
                AlpineProfileRow(
                    title = stringResource(R.string.settings_node_environment),
                    subtitle = "nodejs, npm",
                    profileState = packageProfiles["node"],
                    installProgress = installProgress["node"],
                    enabled = setupState.isReady,
                    onInstall = { onInstallPackageProfile("node") },
                )
                CardDivider()
                AlpineProfileRow(
                    title = stringResource(R.string.settings_git_ripgrep_tools),
                    subtitle = "git, ripgrep",
                    profileState = packageProfiles["git_search"],
                    installProgress = installProgress["git_search"],
                    enabled = setupState.isReady,
                    onInstall = { onInstallPackageProfile("git_search") },
                )
                CardDivider()
                AlpineProfileRow(
                    title = stringResource(R.string.settings_ssh_tools),
                    subtitle = "openssh-client",
                    profileState = packageProfiles["ssh"],
                    installProgress = installProgress["ssh"],
                    enabled = setupState.isReady,
                    onInstall = { onInstallPackageProfile("ssh") },
                )
            }
        }
    }
}

@Composable
private fun AlpineProfileRow(
    title: String,
    subtitle: String,
    profileState: PackageProfileState?,
    installProgress: AlpineSetupProgress?,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    val isInstalling = installProgress != null
    val progressText = installProgress?.let { alpinePackageProgressText(it) }
    val progressButtonText = installProgress?.let { alpinePackageProgressValue(it) }
    val persistedError = profileState?.lastError
        ?.takeUnless { it == "Installing..." }
        .orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
            if (progressText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (persistedError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = persistedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SettingsSubtleActionButton(
            label = when {
                isInstalling -> progressButtonText.orEmpty()
                profileState?.installed == true -> stringResource(R.string.settings_installed)
                else -> stringResource(R.string.common_install)
            },
            onClick = onInstall,
            enabled = enabled && profileState?.installed != true && !isInstalling,
        )
    }
}

@Composable
private fun RuntimeDefaultsPage(
    title: String,
    termuxReady: Boolean,
    alpineReady: Boolean,
    enabledRuntimeIds: Set<LocalRuntimeId>,
    defaultRuntimeId: LocalRuntimeId?,
    onSetDefaultRuntime: (LocalRuntimeId) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_runtime_defaults_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsChoiceRow(
                    title = "Alpine",
                    subtitle = if (alpineReady) {
                        stringResource(R.string.settings_runtime_alpine_ready_subtitle)
                    } else {
                        stringResource(R.string.settings_runtime_alpine_unavailable_subtitle)
                    },
                    selected = defaultRuntimeId == LocalRuntimeId.Alpine,
                    onClick = {
                        if (alpineReady || LocalRuntimeId.Alpine in enabledRuntimeIds) {
                            onSetDefaultRuntime(LocalRuntimeId.Alpine)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun alpineSetupStatusText(
    setupState: LocalRuntimeSetupState,
): String = when (setupState.issue) {
    LocalRuntimeIssue.Ready -> stringResource(R.string.settings_alpine_status_ready)
    LocalRuntimeIssue.NotConfigured,
    LocalRuntimeIssue.NotInstalled -> stringResource(R.string.settings_alpine_status_not_installed)
    LocalRuntimeIssue.UnsupportedAbi -> stringResource(R.string.settings_alpine_status_unsupported_abi)
    LocalRuntimeIssue.MissingAssets -> stringResource(R.string.settings_alpine_status_missing_assets)
    LocalRuntimeIssue.Failed -> stringResource(R.string.settings_alpine_status_failed)
    LocalRuntimeIssue.PermissionMissing,
    LocalRuntimeIssue.ExternalAppsDisabled,
    LocalRuntimeIssue.DispatchFailed -> stringResource(R.string.settings_alpine_status_not_ready)
}

@Composable
private fun AgentModeSettingsPage(
    title: String,
    termuxSetupState: TermuxSetupState,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    rootSetupState: RootSetupState,
    onAgentModeAuthorizationEnabledChanged: (Boolean) -> Unit,
    onAgentModeAuthorizationMethodChanged: (AgentModeAuthorizationMethod) -> Unit,
    agentModeDisplayState: AgentModeDisplayState,
    onRequestShizukuPermission: () -> Unit,
    onRefreshAgentModeAuthorization: (Boolean, AgentModeAuthorizationMethod) -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onRefreshRootSetup: () -> Unit,
    onConfigureWithRoot: () -> Unit,
    onStopAgentModeDisplay: () -> Unit,
    onRefreshAgentModeDisplays: (AgentModeAuthorizationMethod) -> Unit,
    onBack: () -> Unit,
) {
    fun refreshAgentModeStatus() {
        onRefreshAgentModeAuthorization(
            agentModeAuthorizationEnabled,
            AgentModeAuthorizationMethod.Shizuku,
        )
        onRefreshAgentModeDisplays(AgentModeAuthorizationMethod.Shizuku)
    }

    SettingsAutoRefreshEffect(key = agentModeAuthorizationEnabled, refresh = ::refreshAgentModeStatus)
    SubPageScaffold(
        title = title,
        onBack = onBack,
        trailingIcon = Icons.Rounded.Refresh,
        onTrailingAction = ::refreshAgentModeStatus,
    ) {
        Text(
            text = stringResource(R.string.settings_agent_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_agent_mode_authorization),
                    subtitle = stringResource(R.string.settings_agent_mode_authorization_subtitle),
                    checked = agentModeAuthorizationEnabled,
                    onCheckedChange = onAgentModeAuthorizationEnabledChanged,
                )
                Spacer(Modifier.height(14.dp))
                AgentModeAuthorizationNotice(
                    enabled = agentModeAuthorizationEnabled,
                    method = AgentModeAuthorizationMethod.Shizuku,
                    state = agentModeAuthorizationState,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onOpenShizuku = onOpenShizuku,
                    onInstallShizuku = onInstallShizuku,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_virtual_display),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (agentModeDisplayState.isActive) {
                        stringResource(
                            R.string.settings_agent_mode_display_active,
                            agentModeDisplayState.displayId ?: "-",
                            agentModeDisplayState.width,
                            agentModeDisplayState.height,
                        )
                    } else {
                        stringResource(R.string.settings_agent_mode_no_virtual_display)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                if (agentModeDisplayState.latestWorkspacePath.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = agentModeDisplayState.latestWorkspacePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_visible_displays),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(8.dp))
                if (agentModeDisplayState.displays.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_no_visible_displays),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        agentModeDisplayState.displays.forEach { display ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                if (display.isAetherDisplay) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                                } else {
                                    AetherSettingsBackground
                                }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.agent_mode_display_id, display.displayId),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AetherOnSurface,
                                    )
                                    Text(
                                        text = listOf(
                                            display.name.ifBlank { stringResource(R.string.settings_unnamed_display) },
                                            "${display.width} x ${display.height}",
                                            if (display.isAetherDisplay) "Aether" else "",
                                        ).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AetherOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SettingsActionButton(
                    label = stringResource(R.string.settings_stop_virtual_display),
                    onClick = onStopAgentModeDisplay,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = agentModeDisplayState.isActive,
                )
            }
        }
    }
}

@Composable
private fun RootSetupAlreadyConfiguredDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(28.dp))
                .background(AetherSurfaceHigh)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = stringResource(R.string.action_continue),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class RootSetupProgressStepStatus {
    Pending,
    Active,
    Complete,
    Attention,
}

@Composable
private fun RootSetupProgressPage(
    rootSetupState: RootSetupState,
    termuxSetupState: TermuxSetupState,
    agentModeAuthorizationEnabled: Boolean,
    agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    agentModeAuthorizationState: AgentModeAuthorizationState,
    onRunRootSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val rootAgentModeReady = agentModeAuthorizationEnabled &&
        agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root &&
        agentModeAuthorizationState.isReady
    val rootStepStatus = when {
        rootSetupState.isReady || rootSetupState.rootAvailable -> RootSetupProgressStepStatus.Complete
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Active
        rootSetupState.issue == RootSetupIssue.Unavailable -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }
    val termuxStepStatus = when {
        rootSetupState.isReady || termuxSetupState.isReady -> RootSetupProgressStepStatus.Complete
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Active
        rootSetupState.issue == RootSetupIssue.TermuxNotInstalled ||
            rootSetupState.issue == RootSetupIssue.Failed -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }
    val agentModeStepStatus = when {
        rootSetupState.isReady || rootAgentModeReady -> RootSetupProgressStepStatus.Complete
        rootSetupState.isRunning && termuxStepStatus == RootSetupProgressStepStatus.Complete ->
            RootSetupProgressStepStatus.Active
        rootSetupState.isRunning -> RootSetupProgressStepStatus.Pending
        rootSetupState.issue == RootSetupIssue.PermissionDenied ||
            rootSetupState.issue == RootSetupIssue.Failed -> RootSetupProgressStepStatus.Attention
        else -> RootSetupProgressStepStatus.Pending
    }

    SubPageScaffold(
        title = stringResource(R.string.settings_root_automatic_setup),
        onBack = onBack,
    ) {
        SettingsCardGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(rootSetupProgressAccent(rootSetupState.issue).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (rootSetupState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 3.dp,
                            color = AetherPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (rootSetupState.isReady) Icons.Rounded.Check else Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = rootSetupProgressAccent(rootSetupState.issue),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = rootSetupProgressTitle(rootSetupState.issue),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = rootSetupProgressBody(rootSetupState.issue),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                if (rootSetupState.detail.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = rootSetupState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RootSetupProgressStep(
                    title = stringResource(R.string.settings_root_setup_step_root_access),
                    subtitle = stringResource(R.string.settings_root_setup_step_root_access_subtitle),
                    status = rootStepStatus,
                )
                RootSetupProgressStep(
                    title = stringResource(R.string.settings_root_setup_step_termux_access),
                    subtitle = stringResource(R.string.settings_root_setup_step_termux_access_subtitle),
                    status = termuxStepStatus,
                )
                RootSetupProgressStep(
                    title = stringResource(R.string.settings_root_setup_step_agent_mode),
                    subtitle = stringResource(R.string.settings_root_setup_step_agent_mode_subtitle),
                    status = agentModeStepStatus,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            rootSetupState.isRunning -> SettingsSubtleActionButton(
                label = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )

            rootSetupState.isReady -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_run_again),
                    onClick = onRunRootSetup,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = stringResource(R.string.action_done),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    label = stringResource(R.string.action_try_again),
                    onClick = onRunRootSetup,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RootSetupProgressStep(
    title: String,
    subtitle: String,
    status: RootSetupProgressStepStatus,
) {
    val (containerColor, contentColor) = when (status) {
        RootSetupProgressStepStatus.Complete -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) to AetherPrimary
        RootSetupProgressStepStatus.Active -> AetherPrimary.copy(alpha = 0.16f) to AetherPrimary
        RootSetupProgressStepStatus.Attention -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.64f) to MaterialTheme.colorScheme.error
        RootSetupProgressStepStatus.Pending -> AetherSurface to AetherOnSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            if (status == RootSetupProgressStepStatus.Active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Icon(
                    imageVector = when (status) {
                        RootSetupProgressStepStatus.Complete -> Icons.Rounded.Check
                        RootSetupProgressStepStatus.Attention -> Icons.Rounded.Info
                        RootSetupProgressStepStatus.Pending,
                        RootSetupProgressStepStatus.Active -> Icons.Rounded.Refresh
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rootSetupProgressAccent(issue: RootSetupIssue): Color = when (issue) {
    RootSetupIssue.Ready -> AetherPrimary
    RootSetupIssue.Running,
    RootSetupIssue.Available -> AetherPrimary
    RootSetupIssue.PermissionDenied,
    RootSetupIssue.TermuxNotInstalled,
    RootSetupIssue.Failed -> MaterialTheme.colorScheme.error
    RootSetupIssue.Unknown,
    RootSetupIssue.Unavailable -> AetherOnSurfaceVariant
}

@Composable
private fun rootSetupProgressTitle(
    issue: RootSetupIssue,
): String = when (issue) {
RootSetupIssue.Running -> stringResource(R.string.settings_root_setup_configuring)
    RootSetupIssue.Ready -> stringResource(R.string.settings_root_setup_completed)
    RootSetupIssue.Available -> stringResource(R.string.settings_root_setup_ready_to_start)
    RootSetupIssue.Unavailable -> stringResource(R.string.settings_root_setup_root_unavailable)
    RootSetupIssue.PermissionDenied -> stringResource(R.string.settings_root_setup_permission_denied)
    RootSetupIssue.TermuxNotInstalled -> stringResource(R.string.settings_root_setup_termux_required)
    RootSetupIssue.Failed -> stringResource(R.string.settings_root_setup_failed)
    RootSetupIssue.Unknown -> stringResource(R.string.settings_root_setup_preparing)
}

@Composable
private fun rootSetupProgressBody(
    issue: RootSetupIssue,
): String = when (issue) {
RootSetupIssue.Running -> stringResource(R.string.settings_root_setup_progress_body_running)
    RootSetupIssue.Ready -> stringResource(R.string.settings_root_setup_progress_body_ready)
    RootSetupIssue.Available,
    RootSetupIssue.Unknown -> stringResource(R.string.settings_root_setup_progress_body_available)
    RootSetupIssue.Unavailable -> stringResource(R.string.settings_root_setup_progress_body_unavailable)
    RootSetupIssue.PermissionDenied -> stringResource(R.string.settings_root_setup_progress_body_permission_denied)
    RootSetupIssue.TermuxNotInstalled -> stringResource(R.string.settings_root_setup_progress_body_termux_not_installed)
    RootSetupIssue.Failed -> stringResource(R.string.settings_root_setup_progress_body_failed)
}
@Composable
private fun RootSetupSettingsSection(
    title: String,
    rootSetupState: RootSetupState,
    body: String,
    onConfigureWithRoot: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
            )
            if (rootSetupState.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AetherPrimary,
                )
            }
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        if (rootSetupState.detail.isNotBlank()) {
            Text(
                text = rootSetupState.detail,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsSubtleActionButton(
            label = when (rootSetupState.issue) {
                RootSetupIssue.Running -> stringResource(R.string.settings_configuring)
                RootSetupIssue.Ready -> stringResource(R.string.settings_run_root_setup_again)
                RootSetupIssue.Unavailable -> stringResource(R.string.settings_try_root_setup)
                else -> stringResource(R.string.settings_configure_with_root)
            },
            onClick = onConfigureWithRoot,
            modifier = Modifier.fillMaxWidth(),
            enabled = !rootSetupState.isRunning,
        )
    }
}

@Composable
private fun rootSetupSettingsBody(
    rootSetupState: RootSetupState,
): String = when (rootSetupState.issue) {
    RootSetupIssue.Ready -> stringResource(R.string.settings_root_setup_body_ready)

    RootSetupIssue.Available,
    RootSetupIssue.Running -> stringResource(R.string.settings_root_setup_body_available)

    RootSetupIssue.Unavailable,
    RootSetupIssue.Unknown -> stringResource(R.string.settings_root_setup_body_unavailable)

    RootSetupIssue.PermissionDenied,
    RootSetupIssue.TermuxNotInstalled,
    RootSetupIssue.Failed -> stringResource(R.string.settings_root_setup_body_failed)
}

@Composable
private fun AgentModeAuthorizationNotice(
    enabled: Boolean,
    method: AgentModeAuthorizationMethod,
    state: AgentModeAuthorizationState,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
) {
    val statusText = when {
        enabled && method == AgentModeAuthorizationMethod.Root -> when (state.issue) {
            AgentModeAuthorizationIssue.Ready -> stringResource(R.string.settings_root_authorization_ready)
            AgentModeAuthorizationIssue.RootUnavailable -> stringResource(R.string.settings_root_authorization_no_su)
            AgentModeAuthorizationIssue.RootPermissionMissing -> stringResource(R.string.settings_root_authorization_grant_su)
            AgentModeAuthorizationIssue.RootPermissionDenied -> stringResource(R.string.settings_root_authorization_denied)
            AgentModeAuthorizationIssue.Error -> state.detail.ifBlank {
                stringResource(R.string.settings_root_authorization_inspect_failed)
            }
            else -> state.detail.ifBlank {
                stringResource(R.string.settings_root_authorization_refresh_first)
            }
        }
        !enabled -> stringResource(R.string.settings_agent_mode_authorization_off)
        method == AgentModeAuthorizationMethod.Root -> stringResource(R.string.settings_agent_mode_root_requests_su)
        else -> when (state.issue) {
            AgentModeAuthorizationIssue.Ready -> stringResource(R.string.settings_shizuku_authorized)
            AgentModeAuthorizationIssue.ShizukuNotInstalled -> stringResource(R.string.settings_shizuku_install_first)
            AgentModeAuthorizationIssue.ShizukuNotRunning -> stringResource(R.string.settings_shizuku_start_first)
            AgentModeAuthorizationIssue.ShizukuPermissionMissing -> stringResource(R.string.settings_shizuku_grant_permission)
            AgentModeAuthorizationIssue.ShizukuPermissionDenied -> stringResource(R.string.settings_shizuku_permission_denied)
            AgentModeAuthorizationIssue.Disabled -> stringResource(R.string.settings_shizuku_save_then_refresh)
            AgentModeAuthorizationIssue.RootUnavailable,
            AgentModeAuthorizationIssue.RootPermissionMissing,
            AgentModeAuthorizationIssue.RootPermissionDenied -> state.detail.ifBlank {
                stringResource(R.string.settings_switch_to_root_to_inspect)
            }
            AgentModeAuthorizationIssue.Error -> state.detail.ifBlank {
                stringResource(R.string.settings_shizuku_inspect_failed)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_authorization_status),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
        Text(
            text = if (state.issue == AgentModeAuthorizationIssue.Error && state.detail.isNotBlank()) {
                state.detail
            } else {
                statusText
            },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )

        when {
            !enabled || method == AgentModeAuthorizationMethod.Root -> Unit

            state.issue == AgentModeAuthorizationIssue.ShizukuNotInstalled -> {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_install_shizuku),
                    onClick = onInstallShizuku,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.issue ==             AgentModeAuthorizationIssue.ShizukuNotRunning -> {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_open_shizuku),
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.issue == AgentModeAuthorizationIssue.ShizukuPermissionMissing ||
                state.issue == AgentModeAuthorizationIssue.ShizukuPermissionDenied -> {
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_grant_access),
                    onClick = onRequestShizukuPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun DeveloperSettingsPage(
    title: String,
    onReplayFollowUpOnboarding: () -> Unit,
    onReplayAlpineSetupPreview: () -> Unit,
    onImportAppData: () -> Unit,
    onExportAppData: () -> Unit,
    onExportLogs: () -> Unit,
    onForceUpdateCheckForTesting: () -> Unit,
    autoCleanOldCommandHistory: Boolean,
    oldCommandHistoryRetentionHours: TextFieldValue,
    onAutoCleanOldCommandHistoryChanged: (Boolean) -> Unit,
    onOldCommandHistoryRetentionHoursChanged: (TextFieldValue) -> Unit,
    termuxReadyForTesting: Boolean,
    onTermuxReadyForTestingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = title, onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_developer_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(16.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_app_data),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_app_data_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_import_app_data),
                    onClick = onImportAppData,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_export_app_data),
                    onClick = onExportAppData,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_replay_alpine_setup_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_replay_alpine_setup_preview_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_replay_setup_preview),
                    onClick = onReplayAlpineSetupPreview,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_logs),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_logs_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_export_logs),
                    onClick = onExportLogs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_update_testing),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_update_testing_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_force_update_prompt),
                    onClick = onForceUpdateCheckForTesting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        RuntimeCleanupDeveloperSettingsSection(
            autoCleanOldCommandHistory = autoCleanOldCommandHistory,
            oldCommandHistoryRetentionHours = oldCommandHistoryRetentionHours,
            onAutoCleanOldCommandHistoryChanged = onAutoCleanOldCommandHistoryChanged,
            onOldCommandHistoryRetentionHoursChanged = onOldCommandHistoryRetentionHoursChanged,
        )

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_termux_readiness_override),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_termux_readiness_override_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.settings_treat_termux_as_ready),
                    subtitle = stringResource(R.string.settings_termux_ready_toggle_subtitle),
                    checked = termuxReadyForTesting,
                    onCheckedChange = onTermuxReadyForTestingChanged,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsCardGroup {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_replay_follow_up_tour),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_replay_follow_up_tour_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingsSubtleActionButton(
                    label = stringResource(R.string.settings_replay_second_part),
                    onClick = onReplayFollowUpOnboarding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Shared building blocks
// -----------------------------------------------------------------------------

// Sub-page scaffold

@Composable
private fun AboutPage(
    title: String,
    appUpdate: AppUpdateUiState,
    onOpenGitHub: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val updateSubtitle = when {
        appUpdate.isDownloading -> appUpdate.downloadProgress?.let { progress ->
            stringResource(R.string.settings_update_downloading_percent, (progress * 100).toInt())
        } ?: stringResource(R.string.settings_update_downloading)
        appUpdate.isChecking -> stringResource(R.string.settings_update_checking_github_releases)
        appUpdate.availableRelease != null -> stringResource(R.string.app_update_available)
        else -> stringResource(R.string.settings_update_check_newer_apk)
    }
    val releaseLabel = stringResource(R.string.settings_release_summary, BuildConfig.VERSION_NAME)
    val kernelLabel = stringResource(R.string.settings_kernel_value, BundledKimiCodeCliVersion)
    SubPageScaffold(title = title, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.aether_mark),
                contentDescription = stringResource(R.string.settings_aether_logo),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.size(112.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = releaseLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        SettingsCardGroup {
            AboutInfoRow(label = stringResource(R.string.settings_author), value = stringResource(R.string.settings_author_name))
            CardDivider()
            AboutInfoRow(label = stringResource(R.string.settings_version), value = kernelLabel)
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.settings_check_for_updates),
                subtitle = updateSubtitle,
                onClick = onCheckForUpdates,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.settings_github),
                subtitle = "",
                onClick = onOpenGitHub,
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = "",
                onClick = onOpenPrivacyPolicy,
            )
        }

        if (appUpdate.availableRelease != null) {
            Spacer(Modifier.height(16.dp))
            SettingsActionButton(
                label = if (appUpdate.isDownloading) {
                    updateSubtitle
                } else {
                    stringResource(R.string.settings_download_and_install)
                },
                onClick = onDownloadAndInstallUpdate,
                enabled = !appUpdate.isDownloading,
                isLoading = appUpdate.isDownloading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GitHubLinksPage(
    title: String,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    SubPageScaffold(title = title, onBack = onBack) {
        SettingsCardGroup {
            SettingsNavRow(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.settings_github_aether_title),
                subtitle = stringResource(R.string.settings_github_aether_subtitle),
                onClick = { uriHandler.openUri(AetherGitHubUrl) },
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.settings_github_ditto_title),
                subtitle = stringResource(R.string.settings_github_ditto_subtitle),
                onClick = { uriHandler.openUri(AetherPersonalGitHubUrl) },
            )
            CardDivider()
            SettingsNavRow(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.settings_github_upa_title),
                subtitle = stringResource(R.string.settings_github_upa_subtitle),
                onClick = { uriHandler.openUri(AetherUpaPluginGitHubUrl) },
            )
        }
    }
}

@Composable
private fun PrivacyPolicyPlaceholderPage(
    title: String,
    onBack: () -> Unit,
) {
    SubPageScaffold(title = title, onBack = onBack) {
        SettingsCardGroup {
            Text(
                text = stringResource(R.string.settings_privacy_policy_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    trailingLoading: Boolean = false,
    trailingContentDescription: String = title,
    onTrailingAction: (() -> Unit)? = null,
    secondaryTrailingIcon: ImageVector? = null,
    secondaryTrailingEnabled: Boolean = true,
    secondaryTrailingContentDescription: String = title,
    onSecondaryTrailingAction: (() -> Unit)? = null,
    tertiaryTrailingIcon: ImageVector? = null,
    tertiaryTrailingEnabled: Boolean = true,
    tertiaryTrailingContentDescription: String = title,
    onTertiaryTrailingAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherSettingsBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = topBarBodyHeight)
                    .padding(horizontal = 20.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Spacer(Modifier.height(6.dp))
                content()
                Spacer(Modifier.height(32.dp))
            }

            SettingsTopBarOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                title = title,
                onBack = onBack,
                trailingIcon = trailingIcon,
                trailingEnabled = trailingEnabled,
                trailingLoading = trailingLoading,
                trailingContentDescription = trailingContentDescription,
                onTrailingAction = onTrailingAction,
                secondaryTrailingIcon = secondaryTrailingIcon,
                secondaryTrailingEnabled = secondaryTrailingEnabled,
                secondaryTrailingContentDescription = secondaryTrailingContentDescription,
                onSecondaryTrailingAction = onSecondaryTrailingAction,
                tertiaryTrailingIcon = tertiaryTrailingIcon,
                tertiaryTrailingEnabled = tertiaryTrailingEnabled,
                tertiaryTrailingContentDescription = tertiaryTrailingContentDescription,
                onTertiaryTrailingAction = onTertiaryTrailingAction,
                onBodyHeightChanged = { topBarBodyHeightPx = it },
            )
        }
    }
}

// Top bar

@Composable
private fun SettingsTopBarOverlay(
    modifier: Modifier = Modifier,
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    trailingLoading: Boolean = false,
    trailingContentDescription: String = title,
    onTrailingAction: (() -> Unit)? = null,
    secondaryTrailingIcon: ImageVector? = null,
    secondaryTrailingEnabled: Boolean = true,
    secondaryTrailingContentDescription: String = title,
    onSecondaryTrailingAction: (() -> Unit)? = null,
    tertiaryTrailingIcon: ImageVector? = null,
    tertiaryTrailingEnabled: Boolean = true,
    tertiaryTrailingContentDescription: String = title,
    onTertiaryTrailingAction: (() -> Unit)? = null,
    onBodyHeightChanged: (Int) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(settingsTopOverlayBodyGradient())
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            SettingsTopBar(
                title = title,
                onBack = onBack,
                trailingIcon = trailingIcon,
                trailingEnabled = trailingEnabled,
                trailingLoading = trailingLoading,
                trailingContentDescription = trailingContentDescription,
                onTrailingAction = onTrailingAction,
                secondaryTrailingIcon = secondaryTrailingIcon,
                secondaryTrailingEnabled = secondaryTrailingEnabled,
                secondaryTrailingContentDescription = secondaryTrailingContentDescription,
                onSecondaryTrailingAction = onSecondaryTrailingAction,
                tertiaryTrailingIcon = tertiaryTrailingIcon,
                tertiaryTrailingEnabled = tertiaryTrailingEnabled,
                tertiaryTrailingContentDescription = tertiaryTrailingContentDescription,
                onTertiaryTrailingAction = onTertiaryTrailingAction,
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsTopFadeHeight)
                .background(settingsTopOverlayTailGradient())
        )
    }
}

@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    trailingIcon: ImageVector? = null,
    trailingEnabled: Boolean = true,
    trailingLoading: Boolean = false,
    trailingContentDescription: String = title,
    onTrailingAction: (() -> Unit)? = null,
    secondaryTrailingIcon: ImageVector? = null,
    secondaryTrailingEnabled: Boolean = true,
    secondaryTrailingContentDescription: String = title,
    onSecondaryTrailingAction: (() -> Unit)? = null,
    tertiaryTrailingIcon: ImageVector? = null,
    tertiaryTrailingEnabled: Boolean = true,
    tertiaryTrailingContentDescription: String = title,
    onTertiaryTrailingAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SettingsCircleButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = if (tertiaryTrailingIcon != null) (-48).dp else 0.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tertiaryTrailingIcon != null && onTertiaryTrailingAction != null) {
                SettingsCircleButton(
                    icon = tertiaryTrailingIcon,
                    contentDescription = tertiaryTrailingContentDescription,
                    enabled = tertiaryTrailingEnabled,
                    onClick = onTertiaryTrailingAction,
                )
            }
            if (secondaryTrailingIcon != null && onSecondaryTrailingAction != null) {
                SettingsCircleButton(
                    icon = secondaryTrailingIcon,
                    contentDescription = secondaryTrailingContentDescription,
                    enabled = secondaryTrailingEnabled,
                    onClick = onSecondaryTrailingAction,
                )
            }
            if (trailingLoading) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AetherPrimary,
                    )
                }
            } else if (trailingIcon != null && onTrailingAction != null) {
                SettingsCircleButton(
                    icon = trailingIcon,
                    contentDescription = trailingContentDescription,
                    enabled = trailingEnabled,
                    onClick = onTrailingAction,
                )
            } else if (secondaryTrailingIcon == null) {
                Spacer(Modifier.size(44.dp))
            }
        }
    }
}

@Composable
private fun alpinePackageProgressText(progress: AlpineSetupProgress): String {
    val value = alpinePackageProgressValue(progress)
    return when (progress.activity) {
        AlpineSetupActivity.Extracting ->
            stringResource(R.string.settings_profile_extracting_rate, value)
        AlpineSetupActivity.Downloading ->
            stringResource(R.string.settings_profile_downloading_rate, value)
        AlpineSetupActivity.Installing ->
            stringResource(R.string.settings_profile_installing_percent, value)
        AlpineSetupActivity.None ->
            stringResource(R.string.settings_profile_processing_rate, value)
    }
}

private fun alpinePackageProgressValue(progress: AlpineSetupProgress): String =
    if (progress.activity == AlpineSetupActivity.Installing) {
        "${progress.progressPercent ?: 0}%"
    } else {
        formatTransferRate(progress.bytesPerSecond)
    }

private fun formatTransferRate(bytesPerSecond: Long): String {
    val rate = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        rate >= 1024.0 * 1024.0 ->
            String.format(Locale.US, "%.1f MB/s", rate / (1024.0 * 1024.0))
        rate >= 1024.0 ->
            String.format(Locale.US, "%.0f KB/s", rate / 1024.0)
        else -> String.format(Locale.US, "%.0f B/s", rate)
    }
}

@Composable
private fun SettingsCircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(10.dp, RoundedCornerShape(50), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AetherSurface else AetherSurface.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) AetherOnSurface else AetherOnSurface.copy(alpha = 0.45f),
        )
    }
}

// -----------------------------------------------------------------------------
// Memory
// -----------------------------------------------------------------------------

/**
 * Local conversation notes the agent wrote down.
 *
 * EverMe holds long-term recall. This screen only lists Markdown files on disk; forgetting one
 * deletes the file, not the cloud entry.
 */
@Composable
private fun MemorySettingsPage(
    pages: List<kira.ditto.data.MemoryPage>,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var confirmClear by remember { mutableStateOf(false) }
    val notes = remember(pages) { pages.filter { it.isSessionNote } }

    SubPageScaffold(title = stringResource(R.string.settings_memory), onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_memory_description),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (pages.isEmpty()) {
            SettingsCardGroup {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.settings_memory_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            return@SubPageScaffold
        }

        if (notes.isNotEmpty()) {
            SettingsSectionHeading(stringResource(R.string.settings_memory_notes_heading))
            notes.forEach { page ->
                MemoryPageCard(
                    page = page,
                    provenance = stringResource(R.string.settings_memory_note),
                    body = page.summary,
                    onDelete = { onDelete(page.id) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { confirmClear = true }) {
            Text(stringResource(R.string.settings_memory_clear_all))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_memory_clear_all)) },
            text = { Text(stringResource(R.string.settings_memory_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                    onRefresh()
                }) { Text(stringResource(R.string.settings_memory_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeading(text: String) {
    Text(
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = AetherOnSurfaceVariant,
    )
}

@Composable
private fun MemoryPageCard(
    page: kira.ditto.data.MemoryPage,
    provenance: String,
    body: String,
    onDelete: () -> Unit,
) {
    SettingsCardGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = page.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.settings_memory_forget))
                }
            }
            // Provenance up front: a note is an opinion the agent formed, an archive is a record of
            // what was said, and the user is entitled to weigh the two differently.
            Text(
                text = provenance,
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
            )
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = AetherOnSurface)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
