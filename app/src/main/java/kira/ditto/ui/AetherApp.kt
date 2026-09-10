package kira.ditto.ui

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Patterns
import android.widget.Toast
import kira.ditto.AetherLocaleManager
import kira.ditto.R
import kira.ditto.browser.BrowserDesk
import kira.ditto.browser.looksLikeHttpUrl
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kira.ditto.AetherApplication
import kira.ditto.data.AetherPrivacyPolicyUrl
import kira.ditto.data.AetherGitHubUrl
import kira.ditto.data.AetherWebsiteUrl
import kira.ditto.data.AsrEngineRouter
import kira.ditto.data.AgentModeAuthorizationMethod
import kira.ditto.data.AppLanguage
import kira.ditto.data.AppSettings
import kira.ditto.data.AutomaticModelPurpose
import kira.ditto.data.ProviderModelOption
import kira.ditto.data.PiExtensionUiRequest
import kira.ditto.data.isOnboardingComplete
import kira.ditto.data.resolveAutomaticModelKey
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.usableTitle
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.mod.AetherNativeModState
import kira.ditto.runtime.LocalRuntimeIssue
import kira.ditto.runtime.LocalRuntimeSetupState
import kira.ditto.runtime.AndroidAlpineFileManagerRuntime
import kira.ditto.termux.TermuxContract
import kira.ditto.termux.TermuxSetupIssue
import kira.ditto.termux.TermuxSetupState
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherSettingsBackground
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.ui.theme.AetherSurfaceHigher
import kira.ditto.ui.theme.AetherTheme
import kira.ditto.ui.TtsPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject


private data class SuggestionAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
)

private sealed interface PendingSaveTarget {
    data class Attachment(val attachment: ChatAttachment) : PendingSaveTarget
    data class WorkspaceFile(val rawLink: String) : PendingSaveTarget
}

private const val ScreenTransitionDuration = 320
private val ScreenTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private const val PrivacyPolicyAnnotationTag = "privacy_policy"
internal const val WideAppLayoutMinWidthDp = 600f

internal fun shouldUseWideAppLayout(availableWidthDp: Float): Boolean =
    availableWidthDp >= WideAppLayoutMinWidthDp

internal const val ExpandedAppLayoutMinWidthDp = 840f

internal fun supportingPaneWidthDp(availableWidthDp: Float): Float {
    val fraction = if (availableWidthDp >= ExpandedAppLayoutMinWidthDp) 0.30f else 0.50f
    return (availableWidthDp * fraction).coerceIn(320f, 460f)
}

private fun supportingPaneWidth(maxWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    supportingPaneWidthDp(maxWidth.value).dp

private fun AppScreen.isSupportingPane(): Boolean = when (this) {
    AppScreen.Settings, AppScreen.DigiCrew, AppScreen.UpaPlugin, AppScreen.Remote -> true
    else -> false
}

private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Onboarding -> 0
    AppScreen.Chat -> 1
    AppScreen.Settings,
    AppScreen.DigiCrew,
    AppScreen.UpaPlugin,
    AppScreen.Remote -> 2
}

private val ScreenSnapSizeTransform = SizeTransform(clip = false) { _, _ -> snap() }

private fun appScreenContentTransform(
    initialState: AppScreen,
    targetState: AppScreen,
    reduceMotion: Boolean,
): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            fadeIn(tween(80)),
            fadeOut(tween(60)),
            sizeTransform = ScreenSnapSizeTransform,
        )
    }
    val isForward = targetState.depth() > initialState.depth()
    val enter = slideInHorizontally(
        animationSpec = tween(ScreenTransitionDuration, easing = ScreenTransitionEasing),
        initialOffsetX = { if (isForward) it / 3 else -it / 3 },
    ) + fadeIn(tween(ScreenTransitionDuration, easing = ScreenTransitionEasing))
    val exit = slideOutHorizontally(
        animationSpec = tween(ScreenTransitionDuration, easing = ScreenTransitionEasing),
        targetOffsetX = { if (isForward) -it / 3 else it / 3 },
    ) + fadeOut(tween(ScreenTransitionDuration, easing = ScreenTransitionEasing))
    return ContentTransform(enter, exit, sizeTransform = ScreenSnapSizeTransform)
}

private fun AetherUiState.toAetherExtensionContext(): JSONObject {
    val activeSession = sessions.firstOrNull { it.id == currentSessionId }
    val execution = sessionExecutionStates[currentSessionId]
    val selectedSkillIds = activeSession?.selectedSkillIds ?: draftSelectedSkillIds
    val selectedMcpServerIds = activeSession?.activeMcpServerIds ?: draftSelectedMcpServerIds
    return JSONObject().apply {
        put("screen", currentScreen.name.lowercase())
        put("session_id", currentSessionId)
        put("session_title", activeSession?.title.orEmpty())
        put("message_count", activeSession?.messages?.size ?: 0)
        put("draft_input", draftInput)
        put("is_running", execution?.isRunning == true)
        put("is_editing", editingMessageId != null)
        put("selected_model_key", activeSession?.selectedModelKey ?: draftSelectedModelKey)
        put("agent_mode_enabled", activeSession?.agentModeEnabled ?: draftAgentModeEnabled)
        put("selected_skill_ids", JSONArray(selectedSkillIds))
        put("selected_mcp_server_ids", JSONArray(selectedMcpServerIds))
        put("default_skill_ids", JSONArray(settings.defaultSelectedSkillIds))
        put(
            "skills",
            JSONArray().apply {
                installedSkills.forEach { skill ->
                    put(
                        JSONObject().apply {
                            put("id", skill.id)
                            put("name", skill.name)
                            put("description", skill.description)
                            put("action_label", skill.actionLabel)
                            put("enabled", skill.isEnabled)
                            put("selected", skill.id in selectedSkillIds)
                            put("default_selected", skill.id in settings.defaultSelectedSkillIds)
                        }
                    )
                }
            },
        )
        put("language", settings.language.storageValue)
        put("theme", settings.themeMode.storageValue)
        put("extension_count", installedPiExtensions.size)
        put("skill_count", installedSkills.count { it.isEnabled })
        put("mcp_server_count", mcpServers.count { it.isEnabled })
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AetherApp(
    viewModel: AetherViewModel = viewModel(),
    onNotificationPermissionRequested: () -> Unit = {},
    onFirstFrameReady: () -> Unit = {},
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val keepScreenOn = shouldKeepAgentScreenOn(
        isSending = uiState.isSending,
        pendingResponseSessionId = uiState.pendingResponseSessionId,
        agentModeDisplayActive = uiState.agentModeDisplayState.isActive,
        sessionExecutionStates = uiState.sessionExecutionStates,
    )
    DisposableEffect(keepScreenOn) {
        val window = context.findActivity()?.window
        applyKeepScreenOn(window, keepScreenOn)
        onDispose { applyKeepScreenOn(window, false) }
    }
    val applicationLanguage = AetherLocaleManager.currentApplicationLanguage()
    val effectiveLanguage = applicationLanguage ?: uiState.settings.language
    val appRuntime = remember(context) {
        (context.applicationContext as AetherApplication).runtime
    }
    val extensionManager = appRuntime.aetherAppExtensionManager
    val extensionState = extensionManager.state.collectAsStateWithLifecycle().value
    val piExtensionUiRequest = extensionManager.piUiRequest.collectAsStateWithLifecycle().value
    val nativeModState = appRuntime.nativeModManager.state.collectAsStateWithLifecycle().value
    val nativeComponents =
        appRuntime.modKernel.components.registrations.collectAsStateWithLifecycle().value
    LaunchedEffect(uiState.isStartupRouteResolved) {
        if (uiState.isStartupRouteResolved) {
            onFirstFrameReady()
            // Releases the ViewModel's deferred startup tier (mesh discovery, catalogs,
            // extension scans) now that there is something on screen.
            viewModel.notifyFirstFrameRendered()
        }
    }
    // Keep the controller identity stable across streaming uiState updates:
    // it is provided through a CompositionLocal, and recreating it on every
    // token used to force the whole UI tree to recompose (global jank while
    // text was streaming). Consumers read the latest uiState lazily instead.
    val currentUiState = rememberUpdatedState(uiState)
    val extensionController = remember(
        extensionState.snapshot,
        extensionState.error,
        nativeComponents,
        viewModel,
        extensionManager,
    ) {
        AetherExtensionUiController(
            snapshot = extensionState.snapshot,
            runtimeError = extensionState.error,
            nativeComponents = nativeComponents,
            uiStateProvider = { currentUiState.value },
            publicStateProvider = { currentUiState.value.toAetherExtensionContext() },
            onHostCall = viewModel::handleAetherExtensionHostCall,
            onAction = { extensionId, action, args ->
                extensionManager.invokeAction(extensionId, action, args)
            },
        )
    }

    LaunchedEffect(uiState.settings.language, applicationLanguage) {
        if (applicationLanguage == null) {
            AetherLocaleManager.applyIfChanged(uiState.settings.language)
        } else if (applicationLanguage != uiState.settings.language) {
            viewModel.updateAppLanguage(applicationLanguage)
        }
    }

    // Script-extension subscriptions are part of the removed Pi runtime. Keep
    // the original extension host UI inert until it is backed by Kimi plugins;
    // starting it here would continuously launch pi-bridge/bridge.mjs.

    LaunchedEffect(extensionManager, context) {
        extensionManager.notifications.collect { notification ->
            Toast.makeText(context, notification.message, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(extensionManager, viewModel) {
        extensionManager.setHostHandler(viewModel::handleAetherExtensionHostCall)
        onDispose {
            extensionManager.clearHostHandler()
        }
    }

    CompositionLocalProvider(
        LocalAetherExtensionUiController provides extensionController,
        LocalUpaRenderUi provides uiState.settings.upaRenderUi,
        LocalUpaDisabledUiPlugins provides uiState.settings.upaDisabledUiPlugins,
        LocalUpaMcpBindings provides uiState.settings.upaMcpBindings,
        LocalUpaUserAction provides { pluginId, serverIds, name ->
            viewModel.dispatchA2uiUserAction(pluginId, serverIds, name)
        },
    ) {
        AetherTheme(
            themeMode = uiState.settings.themeMode,
            language = effectiveLanguage,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AetherExtensionComponentHost(
                        target = AetherExtensionComponentAppContent,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AetherAppContent(
                                viewModel = viewModel,
                                uiState = uiState,
                                language = effectiveLanguage,
                                nativeModState = nativeModState,
                                onNotificationPermissionRequested = onNotificationPermissionRequested,
                                drawerOpenedEventRegistered =
                                    "drawer.opened" in extensionState.snapshot.eventNames,
                                onDrawerOpened = {
                                    extensionManager.emitEvent(
                                        event = "drawer.opened",
                                        context = currentUiState.value.toAetherExtensionContext(),
                                    )
                                },
                        )
                    }
                    AetherExtensionOverlaySlot(Modifier.fillMaxSize())
                }
            }
            piExtensionUiRequest?.let { request ->
                PiExtensionUiDialog(
                    request = request,
                    onResult = { value ->
                        extensionManager.respondToPiExtensionUiRequest(request.callId, value)
                    },
                )
            }
        }
    }
}

@Composable
private fun PiExtensionUiDialog(
    request: PiExtensionUiRequest,
    onResult: (Any?) -> Unit,
) {
    var input by remember(request.callId) { mutableStateOf("") }
    val dismissValue: Any? = if (request.method == "pi_extension_confirm") false else null
    AlertDialog(
        onDismissRequest = { onResult(dismissValue) },
        containerColor = AetherSurface,
        titleContentColor = AetherOnSurface,
        textContentColor = AetherOnSurfaceVariant,
        title = { Text(request.title) },
        text = {
            when (request.method) {
                "pi_extension_select" -> Column {
                    request.options.forEach { option ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onResult(option) },
                        ) {
                            Text(option, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                "pi_extension_input" -> OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = request.placeholder.takeIf(String::isNotBlank)?.let { placeholder ->
                        { Text(placeholder) }
                    },
                    singleLine = true,
                )

                else -> Text(request.message)
            }
        },
        confirmButton = {
            if (request.method != "pi_extension_select") {
                TextButton(
                    onClick = {
                        onResult(
                            if (request.method == "pi_extension_confirm") true else input,
                        )
                    },
                ) {
                    Text(stringResource(R.string.common_done))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(dismissValue) }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AetherAppContent(
    viewModel: AetherViewModel,
    uiState: AetherUiState,
    language: AppLanguage,
    nativeModState: AetherNativeModState,
    onNotificationPermissionRequested: () -> Unit,
    drawerOpenedEventRegistered: Boolean,
    onDrawerOpened: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val latestOnDrawerOpened by rememberUpdatedState(onDrawerOpened)
    val drawerOpenedEventGate = remember { AetherDrawerOpenedEventGate() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(drawerState, drawerOpenedEventRegistered) {
        snapshotFlow { drawerState.currentValue to drawerState.targetValue }
            .distinctUntilChanged()
            .collect { (currentValue, targetValue) ->
                val shouldDispatchDrawerOpened = drawerOpenedEventGate.onDrawerSnapshotChanged(
                    currentOpen = currentValue == DrawerValue.Open,
                    targetOpen = targetValue == DrawerValue.Open,
                    eventRegistered = drawerOpenedEventRegistered,
                )
                if (shouldDispatchDrawerOpened) {
                    latestOnDrawerOpened()
                }
            }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val appRuntime = remember(context) {
        (context.applicationContext as AetherApplication).runtime
    }
    val workspaceFileBridge = appRuntime.workspaceFileBridge
    val alpineFileManagerRuntime = remember(appRuntime.alpineRuntime) {
        AndroidAlpineFileManagerRuntime(appRuntime.alpineRuntime)
    }
    val ttsPlayingMessageId = viewModel.playingMessageId.collectAsStateWithLifecycle().value
    val ttsPlaybackState = remember(
        ttsPlayingMessageId,
        uiState.settings.ttsEnabled,
        uiState.settings.defaultTtsModelKey,
    ) {
        TtsPlaybackState(
            showButton = uiState.settings.ttsEnabled && uiState.settings.defaultTtsModelKey.isNotBlank(),
            playingMessageId = ttsPlayingMessageId,
            onPlay = { messageId -> viewModel.onPlayMessage(messageId) },
        )
    }
    val runtimeWorkspaceFileBridge = appRuntime.runtimeWorkspaceFileBridge
    val activeSession = uiState.sessions.firstOrNull { it.id == uiState.currentSessionId }
    val activeProviderConfig = uiState.providerConfigs.firstOrNull { it.isEnabled }
        ?: uiState.providerConfigs.firstOrNull()
    val currentSessionExecution = uiState.sessionExecutionStates[uiState.currentSessionId]
    val activeStreamingResponseGroupId = currentSessionExecution
        ?.takeIf { it.isRunning }
        ?.activeResponseGroupId
    val sessionMessages = activeSession?.messages.orEmpty()
    val currentMessages = remember(sessionMessages, activeStreamingResponseGroupId) {
        if (activeStreamingResponseGroupId == null) {
            sessionMessages
        } else {
            sessionMessages.filterNot { message ->
                message.isIncomplete && message.responseGroupId == activeStreamingResponseGroupId
            }
        }
    }
    val sessionSummaries = rememberStructurallyEqual(
        uiState.sessionSummaries.ifEmpty { uiState.sessions.map { it.toSummary() } },
    )
    val selectedSkillIds = activeSession?.selectedSkillIds ?: uiState.draftSelectedSkillIds
    val selectedMcpServerIds = activeSession?.activeMcpServerIds ?: uiState.draftSelectedMcpServerIds
    val effectiveTermuxSetupState = effectiveTermuxSetupState(
        setupState = uiState.termuxSetupState,
        developerOverride = uiState.developerTermuxReadyOverride,
        termuxSetupCompleted = uiState.settings.termuxSetupCompleted,
    )
    val agentModeReady = uiState.settings.agentModeAuthorizationEnabled &&
        uiState.agentModeAuthorizationState.isReady
    val agentModeSelected = activeSession?.agentModeEnabled ?: uiState.draftAgentModeEnabled
    val chromeAvailable = false
    val chromeSelected = activeSession?.chromeEnabled ?: uiState.draftChromeEnabled
    val conversationModelOptions = remember(
        uiState.providerConfigs,
        uiState.remoteModelOptionsByMachineId,
        activeSession?.remoteMachineId,
    ) {
        uiState.chatModelOptions(activeSession)
    }
    val selectedConversationModelKey = remember(
        activeSession?.selectedModelKey,
        uiState.draftSelectedModelKey,
        uiState.settings.defaultChatModelKey,
        conversationModelOptions,
    ) {
        resolveConversationModelKey(
            session = activeSession,
            draftSelectedModelKey = uiState.draftSelectedModelKey,
            defaultChatModelKey = uiState.settings.defaultChatModelKey,
            options = conversationModelOptions,
        )
    }
    val pendingToolInvocations = currentSessionExecution?.pendingToolInvocations.orEmpty()
    val pendingResponseBlocks = currentSessionExecution?.pendingResponseBlocks.orEmpty()
    val pendingAssistantText = currentSessionExecution?.pendingAssistantText.orEmpty()
    val pendingInputs = currentSessionExecution?.pendingInputs.orEmpty()
    val isCurrentSessionRunning = currentSessionExecution?.isRunning == true
    val currentSessionPlanEntries = currentSessionExecution?.planEntries.orEmpty()
    val currentSessionContextUsage = currentSessionExecution?.contextUsage
    val currentSessionModeId = currentSessionExecution?.agentConfig?.modeId
        ?: activeSession?.remoteMachineId?.takeIf { it.isNotBlank() }?.let { machineId ->
            uiState.remotePermissionModeByMachineId[machineId]
        }
        ?: uiState.settings.kimiPermissionMode
    val sessionPermissionRequests = remember(
        uiState.pendingPermissionRequests,
        uiState.currentSessionId,
    ) {
        uiState.pendingPermissionRequests.filter { request ->
            request.sessionId.isBlank() || request.sessionId == uiState.currentSessionId
        }
    }
    val sessionElicitationRequests = remember(
        uiState.pendingElicitationRequests,
        uiState.currentSessionId,
    ) {
        uiState.pendingElicitationRequests.filter { request ->
            request.sessionId.isBlank() || request.sessionId == uiState.currentSessionId
        }
    }
    val currentWorkspaceDirectory = workspaceFileBridge.workspaceDirectory(
        uiState.sessions.firstOrNull { it.id == uiState.currentSessionId }?.workspaceId
            ?: kira.ditto.data.chatdb.DefaultWorkspaceId,
    )
    val currentRuntimeWorkspaceDirectory = appRuntime.runtimeRouter.runtimeWorkspaceDirectory(
        settings = uiState.settings,
        termuxWorkspaceDirectory = currentWorkspaceDirectory,
    )

    LaunchedEffect(viewModel, context) {
        viewModel.transientMessages.collectLatest { message ->
            Toast.makeText(context, message.resolve(context), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(uiState.appUpdate.pendingInstallUri) {
        val installUri = uiState.appUpdate.pendingInstallUri
        if (installUri.isNotBlank()) {
            requestApkInstall(context, Uri.parse(installUri))
            viewModel.consumePendingUpdateInstallUri()
        }
    }
    LaunchedEffect(uiState.isStartupRouteResolved, uiState.settings.privacyPolicyAccepted) {
        if (!uiState.isStartupRouteResolved) return@LaunchedEffect
        if (!uiState.settings.privacyPolicyAccepted) {
            viewModel.acceptPrivacyPolicy()
            return@LaunchedEffect
        }
        onNotificationPermissionRequested()
    }
    var pendingSaveTarget by remember { mutableStateOf<PendingSaveTarget?>(null) }
    var pendingSessionExportId by remember { mutableStateOf<String?>(null) }
    var pendingSkillZipCompletion by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingTermuxPermissionSource by remember { mutableStateOf("unknown") }
    var pendingTermuxPermissionGrantedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var didAutoRequestTermuxPermission by rememberSaveable { mutableStateOf(false) }
    var showAppDataExportWarning by remember { mutableStateOf(false) }
    val onPickedDocuments: (List<Uri>) -> Unit = { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.appendDraftAttachments(uris)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = onPickedDocuments,
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = onPickedDocuments,
    )
    val skillFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.installSkillFromDirectory(treeUri)
            }
        },
    )
    val skillZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { zipUri ->
            val completion = pendingSkillZipCompletion
            pendingSkillZipCompletion = null
            if (zipUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        zipUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.installSkillFromZip(zipUri) { success ->
                    completion?.invoke(success)
                }
            } else {
                completion?.invoke(false)
            }
        },
    )
    val piExtensionImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { extensionUri ->
            if (extensionUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        extensionUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.importPiExtension(extensionUri)
            }
        },
    )
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { destinationUri ->
            val saveTarget = pendingSaveTarget
            pendingSaveTarget = null

            if (saveTarget == null || destinationUri == null) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                val didSave = withContext(Dispatchers.IO) {
                    when (saveTarget) {
                        is PendingSaveTarget.Attachment -> saveAttachmentToDocument(
                            context = context,
                            attachment = saveTarget.attachment,
                            destinationUri = destinationUri,
                        )

                        is PendingSaveTarget.WorkspaceFile -> runtimeWorkspaceFileBridge.saveWorkspaceFileToDocument(
                            settings = uiState.settings,
                            workspaceDirectory = currentRuntimeWorkspaceDirectory,
                            termuxWorkspaceDirectory = currentWorkspaceDirectory,
                            path = saveTarget.rawLink,
                            destinationUri = destinationUri,
                        )
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(if (didSave) R.string.file_saved else R.string.file_could_not_save),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
    val sessionExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { destinationUri ->
            val sessionId = pendingSessionExportId
            pendingSessionExportId = null
            if (sessionId != null && destinationUri != null) {
                viewModel.exportSessionToUri(sessionId, destinationUri)
            }
        },
    )
    val appDataExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { destinationUri ->
            if (destinationUri != null) {
                viewModel.exportAllDataToUri(destinationUri)
            }
        },
    )
    val logExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { destinationUri ->
            if (destinationUri != null) {
                viewModel.exportLogsToUri(destinationUri)
            }
        },
    )
    val appDataImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { sourceUri ->
            if (sourceUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        sourceUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.importAllDataFromUri(sourceUri)
            }
        },
    )
    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val source = pendingTermuxPermissionSource
            val onGranted = pendingTermuxPermissionGrantedAction
            pendingTermuxPermissionSource = "unknown"
            pendingTermuxPermissionGrantedAction = null
            viewModel.trackPermissionResult(
                permission = "termux_run_command",
                granted = granted,
                source = source,
            )
            viewModel.refreshTermuxSetup()
            Toast.makeText(
                context,
                context.getString(
                    if (granted) {
                        R.string.termux_access_granted
                    } else {
                        R.string.termux_access_not_granted
                    },
                ),
                Toast.LENGTH_SHORT,
            ).show()
            if (granted) {
                onGranted?.invoke()
            }
        },
    )
    var recordAudioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            recordAudioGranted = granted
        },
    )
    val asrAvailable = remember(uiState.settings.defaultAsrModelKey, uiState.providerConfigs) {
        AsrEngineRouter(context) { uiState.providerConfigs }.isComposerReady(uiState.settings)
    }
    val conversationActions = remember { ConversationScreenActions() }
    val settingsActions = remember { SettingsScreenActions() }
    fun requestTermuxPermission(
        source: String,
        onGranted: (() -> Unit)? = null,
    ) {
        viewModel.trackTermuxSetupStarted(source)
        viewModel.trackPermissionRequested(
            permission = "termux_run_command",
            source = source,
        )
        pendingTermuxPermissionSource = source
        pendingTermuxPermissionGrantedAction = onGranted
        termuxPermissionLauncher.launch(TermuxContract.RunCommandPermission)
    }

    fun runRootSetupAfterTermuxPermission(
        source: String,
        action: () -> Unit,
    ) {
        if (shouldRequestTermuxPermissionBeforeRootSetup(uiState.termuxSetupState.issue)) {
            requestTermuxPermission(source = source, onGranted = action)
        } else {
            action()
        }
    }

    LaunchedEffect(
        uiState.isStartupRouteResolved,
        uiState.settings.privacyPolicyAccepted,
        uiState.termuxSetupState.issue,
        uiState.rootSetupState.issue,
    ) {
        if (
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = uiState.isStartupRouteResolved,
                privacyPolicyAccepted = uiState.settings.privacyPolicyAccepted,
                setupIssue = uiState.termuxSetupState.issue,
                didAutoRequest = didAutoRequestTermuxPermission,
            )
        ) {
            didAutoRequestTermuxPermission = true
            requestTermuxPermission(
                source = if (shouldResumeRootSetupAfterTermuxPermission(uiState.rootSetupState.issue)) {
                    "root_setup_termux_installed_permission"
                } else {
                    "termux_detected_permission_missing"
                },
                onGranted = viewModel::configureLocalAccessWithRoot.takeIf {
                    shouldResumeRootSetupAfterTermuxPermission(uiState.rootSetupState.issue)
                },
            )
        }
    }

    fun startTermuxSetupAction(
        source: String,
        action: () -> Unit,
    ) {
        viewModel.trackTermuxSetupStarted(source)
        action()
    }

    settingsActions.onRefreshUsageStatistics = viewModel::refreshUsageStatisticsSnapshots
    settingsActions.onSave = viewModel::saveSettings
    settingsActions.onUpdateLanguage = { selectedLanguage ->
        viewModel.updateAppLanguage(selectedLanguage)
        AetherLocaleManager.apply(selectedLanguage)
    }
    settingsActions.onUpdateThemeMode = viewModel::updateAppThemeMode
    settingsActions.onUpsertProviderConfig = viewModel::upsertProviderConfig
    settingsActions.onRemoveProviderConfig = viewModel::removeProviderConfig
    settingsActions.onSetProviderEnabled = viewModel::setProviderEnabled
    settingsActions.onFetchModels = viewModel::fetchModels
    settingsActions.onStartProviderLogin = viewModel::startProviderLogin
    settingsActions.onSubmitProviderAuthPrompt = viewModel::submitProviderAuthPrompt
    settingsActions.onClearProviderAuthState = viewModel::clearProviderAuthState
    settingsActions.onImportSkillFolder = { skillFolderPicker.launch(null) }
    settingsActions.onImportSkillZip = { onComplete ->
        pendingSkillZipCompletion = onComplete
        skillZipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }
    settingsActions.onInstallSkillUrl = { url, onComplete ->
        viewModel.installSkillFromRemote(url, onComplete)
    }
    settingsActions.onToggleSkillEnabled = viewModel::setSkillEnabled
    settingsActions.onRemoveSkill = viewModel::removeSkill
    settingsActions.onRefreshPiExtensions = viewModel::refreshPiExtensions
    settingsActions.onInstallPiExtensionPackage = viewModel::installPiExtensionPackage
    settingsActions.onLoadPiPackageDetails = viewModel::loadPiPackageDetails
    settingsActions.onUpdatePiExtensionPackage = viewModel::updatePiExtensionPackage
    settingsActions.onRemovePiExtension = viewModel::removePiExtension
    settingsActions.onSetPiExtensionEnabled = viewModel::setPiExtensionEnabled
    settingsActions.onImportPiExtension = {
        piExtensionImportPicker.launch(
            arrayOf(
                "application/zip",
                "application/javascript",
                "text/javascript",
                "text/typescript",
                "application/octet-stream",
                "*/*",
            )
        )
    }
    settingsActions.onAllowNativeModsOnNextStart =
        appRuntime.nativeModManager::allowNativeModsOnNextStart
    settingsActions.onDisableNativeModsOnNextStart =
        appRuntime.nativeModManager::requestDisableOnNextStart
    settingsActions.onSaveHttpMcpServer = viewModel::saveStreamableHttpMcpServer
    settingsActions.onSaveStdIoMcpServer = viewModel::saveStdIoMcpServer
    settingsActions.onToggleMcpServerEnabled = viewModel::setMcpServerEnabled
    settingsActions.onRemoveMcpServer = viewModel::removeMcpServer
    settingsActions.onTestMcpServer = viewModel::testMcpServer
    settingsActions.onSaveScheduledTask = viewModel::saveScheduledTask
    settingsActions.onToggleScheduledTaskEnabled = viewModel::setScheduledTaskEnabled
    settingsActions.onRemoveScheduledTask = viewModel::removeScheduledTask
    settingsActions.onRefreshScheduledTasks = viewModel::refreshScheduledTasksFromDisk
    settingsActions.onRequestTermuxPermission = { requestTermuxPermission("settings_termux_permission") }
    settingsActions.onImportAppData = {
        appDataImportLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
    }
    settingsActions.onExportAppData = { showAppDataExportWarning = true }
    settingsActions.onExportLogs = { logExportLauncher.launch("aether-logs.txt") }
    settingsActions.onOpenAppPermissions = {
        startTermuxSetupAction("settings_app_permissions") { openAppPermissionSettings(context) }
    }
    settingsActions.onOpenTermuxSettings = {
        startTermuxSetupAction("settings_termux_settings") { openTermuxSettings(context) }
    }
    settingsActions.onOpenTermux = {
        startTermuxSetupAction("settings_open_termux") { openTermux(context) }
    }
    settingsActions.onInstallTermux = {
        startTermuxSetupAction("settings_install_termux") { openTermuxInstallPage(context) }
    }
    settingsActions.onRefreshTermuxSetup = viewModel::refreshTermuxSetup
    settingsActions.onInitializeAlpineRuntime = { viewModel.initializeAlpineRuntime(makeDefault = false) }
    settingsActions.onResetAlpineRuntime = viewModel::resetAlpineRuntime
    settingsActions.onRefreshAlpineSetup = { viewModel.refreshAlpineSetup(startPiIfReady = false) }
    settingsActions.onInstallAlpinePackageProfile = viewModel::installAlpinePackageProfile
    settingsActions.onCreateAlpineTerminalLaunchSpec = viewModel::createAlpineTerminalLaunchSpec
    settingsActions.onUpdateBrowserPreferences = viewModel::updateBrowserPreferences
    settingsActions.onOpenBuiltInBrowser = { viewModel.openBuiltInBrowser() }
    settingsActions.onClearBrowserData = viewModel::clearBrowserBrowsingData
    settingsActions.onRefreshMemoryPages = viewModel::refreshMemoryPages
    settingsActions.onDeleteMemoryPage = viewModel::forgetMemoryPage
    settingsActions.onClearMemoryPages = viewModel::forgetAllMemoryPages
    settingsActions.onRefreshStorageUsage = viewModel::refreshStorageUsage
    settingsActions.onClearAppStorage = viewModel::clearAppStorage
    settingsActions.onUpdateCacheCleanupPolicy = viewModel::updateCacheCleanupPolicy
    settingsActions.onDeleteBrowserLogin = viewModel::deleteBrowserLogin
    settingsActions.onListBrowserLogins = viewModel::listBrowserLogins
    settingsActions.onSetDefaultRuntime = viewModel::setDefaultRuntime
    settingsActions.onRefreshRootSetup = viewModel::refreshRootSetup
    settingsActions.onStartRootSetupFromSettings = { returnPage ->
        runRootSetupAfterTermuxPermission(
            source = "settings_root_setup_termux_permission",
            action = { viewModel.startRootSetupFromSettings(returnPage) },
        )
    }
    settingsActions.onDismissRootSetupProgress = viewModel::dismissRootSetupProgress
    settingsActions.onRequestShizukuPermission = viewModel::requestShizukuPermission
    settingsActions.onRefreshAgentModeAuthorization = viewModel::refreshAgentModeAuthorization
    settingsActions.onOpenShizuku = { openShizuku(context) }
    settingsActions.onInstallShizuku = viewModel::installBundledShizuku
    settingsActions.onReplayOnboarding = viewModel::openOnboardingFromSettings
    settingsActions.onReplayFollowUpOnboarding = viewModel::openFollowUpOnboardingFromSettings
    settingsActions.onReplayAlpineSetupPreview = viewModel::openDeveloperAlpineSetupPreview
    settingsActions.onStopAgentModeDisplay = viewModel::stopAgentModeDisplay
    settingsActions.onRefreshAgentModeDisplays = viewModel::refreshAgentModeDisplays
    settingsActions.onOpenWebsite = { openExternalUrl(context, AetherWebsiteUrl) }
    settingsActions.onOpenGitHub = { openExternalUrl(context, AetherGitHubUrl) }
    settingsActions.onOpenPrivacyPolicy = { openExternalUrl(context, AetherPrivacyPolicyUrl) }
    settingsActions.onCheckForUpdates = viewModel::checkForUpdates
    settingsActions.onForceUpdateCheckForTesting = viewModel::forceUpdateCheckForTesting
    settingsActions.onSetDeveloperTermuxReadyOverride = viewModel::setDeveloperTermuxReadyOverride
    settingsActions.onDownloadAndInstallUpdate = viewModel::downloadAndInstallUpdate
    settingsActions.onBack = viewModel::closeSettings

    val settingsState = SettingsScreenState(
        systemPrompt = uiState.settings.systemPrompt,
        tavilyApiKey = uiState.settings.tavilyApiKey,
        tavilyBaseUrl = uiState.settings.tavilyBaseUrl,
        llmInactivityReconnectTimeoutSeconds = uiState.settings.llmInactivityReconnectTimeoutSeconds,
        keepTasksRunningInBackground = uiState.settings.keepTasksRunningInBackground,
        notifyOnTaskCompletion = uiState.settings.notifyOnTaskCompletion,
        autoCleanOldCommandHistory = uiState.settings.autoCleanOldCommandHistory,
        oldCommandHistoryRetentionHours = uiState.settings.oldCommandHistoryRetentionHours,
        termuxEnvironmentVariables = uiState.settings.termuxEnvironmentVariables,
        agentModeAuthorizationEnabled = uiState.settings.agentModeAuthorizationEnabled,
        agentModeAuthorizationMethod = uiState.settings.agentModeAuthorizationMethod,
        agentModeAuthorizationState = uiState.agentModeAuthorizationState,
        rootSetupState = uiState.rootSetupState,
        rootSetupProgressReturnPage = uiState.rootSetupProgressReturnPage,
        language = language,
        themeMode = uiState.settings.themeMode,
        defaultChatModelKey = uiState.settings.defaultChatModelKey,
        defaultTitleModelKey = uiState.settings.defaultTitleModelKey,
        defaultNamingModelKey = uiState.settings.defaultNamingModelKey,
        defaultCompactingModelKey = uiState.settings.defaultCompactingModelKey,
        defaultVectorModelKey = uiState.settings.defaultVectorModelKey,
        defaultImageModelKey = uiState.settings.defaultImageModelKey,
        defaultAsrModelKey = uiState.settings.defaultAsrModelKey,
        defaultTtsModelKey = uiState.settings.defaultTtsModelKey,
        ttsEnabled = uiState.settings.ttsEnabled,
        ttsVoiceId = uiState.settings.ttsVoiceId,
        agentModeDisplayState = uiState.agentModeDisplayState,
        providerConfigs = uiState.providerConfigs,
        usageStatisticsSnapshots = uiState.usageStatisticsSnapshots,
        usageStatisticsTotals = uiState.usageStatisticsTotals,
        usageStatisticsRefreshing = uiState.usageStatisticsRefreshing,
        storageUsage = uiState.storageUsage,
        memoryPages = uiState.memoryPages,
        storageBusy = uiState.storageBusy,
        scheduledTasks = uiState.scheduledTasks,
        termuxSetupState = effectiveTermuxSetupState,
        alpineSetupState = uiState.alpineSetupState,
        enabledRuntimeIds = uiState.settings.enabledRuntimeIds,
        defaultRuntimeId = uiState.settings.defaultRuntimeId,
        alpinePackageProfiles = uiState.settings.alpinePackageProfiles,
        alpinePackageInstallProgress = uiState.alpinePackageInstallProgress,
        alpineFileManagerRuntime = alpineFileManagerRuntime,
        browserPreferences = uiState.settings.browserPreferences,
        developerTermuxReadyOverride = uiState.developerTermuxReadyOverride,
        installedSkills = uiState.installedSkills,
        installedPiExtensions = uiState.installedPiExtensions,
        hasLoadedInstalledPiExtensions = uiState.hasLoadedInstalledPiExtensions,
        nativeModState = nativeModState,
        piExtensionCatalog = uiState.piExtensionCatalog,
        isLoadingPiExtensions = uiState.isLoadingPiExtensions,
        piExtensionCatalogError = uiState.piExtensionCatalogError,
        piExtensionOperationSource = uiState.piExtensionOperationSource,
        selectedPiPackageDetails = uiState.selectedPiPackageDetails,
        selectedPiPackageSource = uiState.selectedPiPackageSource,
        isLoadingPiPackageDetails = uiState.isLoadingPiPackageDetails,
        piPackageDetailsError = uiState.piPackageDetailsError,
        mcpServers = uiState.mcpServers,
        isFetchingModels = uiState.isFetchingModels,
        providerAuthState = uiState.providerAuthState,
        appUpdate = uiState.appUpdate,
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTermuxSetup()
                viewModel.refreshAgentModeAuthorization()
                viewModel.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (drawerState.isOpen) {
        AetherPredictiveBackHandler {
            scope.launch { drawerState.close() }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutMaxWidth = maxWidth
        val useWideLayout = shouldUseWideAppLayout(layoutMaxWidth.value) &&
            uiState.isStartupRouteResolved &&
            uiState.currentScreen != AppScreen.Onboarding
        val supportingScreen = uiState.currentScreen.takeIf { it.isSupportingPane() }
        val usePermanentDrawer = useWideLayout && layoutMaxWidth >= 700.dp
        val paneWidth = supportingPaneWidth(layoutMaxWidth)
        LaunchedEffect(useWideLayout, uiState.currentScreen) {
            viewModel.setChatSurfaceVisible(
                useWideLayout || uiState.currentScreen == AppScreen.Chat,
            )
        }
        var paneScreen by remember { mutableStateOf<AppScreen?>(null) }
        if (supportingScreen != null) {
            paneScreen = supportingScreen
        }
        if (useWideLayout && supportingScreen != null) {
            AetherPredictiveBackHandler {
                if (supportingScreen == AppScreen.DigiCrew) {
                    viewModel.closePersona()
                } else {
                    viewModel.closeSettings()
                }
            }
        }

        val navigateFromDrawer: (() -> Unit) -> Unit = { change ->
            if (usePermanentDrawer) {
                change()
            } else {
                scope.launch {
                    if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
                        drawerState.close()
                    }
                    change()
                }
            }
        }
        val drawerContent = @Composable { permanent: Boolean ->
            ConversationDrawer(
                // Only this space's conversations. The filter is the whole point of a space:
                // what another space holds should not be reachable by scrolling.
                sessions = sessionSummaries.filter {
                    it.workspaceId == uiState.currentWorkspaceId
                },
                workspaces = uiState.workspaces,
                currentWorkspaceId = uiState.currentWorkspaceId,
                onWorkspaceSelected = viewModel::selectWorkspace,
                onCreateWorkspace = viewModel::createWorkspace,
                onRenameWorkspace = viewModel::renameWorkspace,
                onDeleteWorkspace = viewModel::deleteWorkspace,
                selectedSessionId = uiState.currentSessionId,
                sessionExecutionStates = uiState.sessionExecutionStates,
                unviewedCompletedSessionIds = uiState.unviewedCompletedSessionIds,
                permanent = permanent,
                onNewChat = {
                    navigateFromDrawer(viewModel::startNewChat)
                },
                onSessionSelected = { sessionId ->
                    if (usePermanentDrawer) {
                        viewModel.selectSession(sessionId)
                    } else {
                        scope.launch {
                            viewModel.prefetchSession(sessionId)
                            if (drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
                                drawerState.close()
                            }
                            viewModel.selectSession(sessionId)
                        }
                    }
                },
                onRenameSession = viewModel::renameSession,
                onExportSession = { sessionId ->
                    val title = sessionSummaries.firstOrNull { it.id == sessionId }?.title
                    pendingSessionExportId = sessionId
                    sessionExportLauncher.launch("${title?.ifBlank { "aether-session" } ?: "aether-session"}.json")
                },
                onDeleteSession = viewModel::deleteSession,
                onForkSession = viewModel::forkSession,
                digiCrewSelected = uiState.currentScreen == AppScreen.DigiCrew,
                upaPluginSelected = uiState.currentScreen == AppScreen.UpaPlugin,
                remoteSelected = uiState.currentScreen == AppScreen.Remote,
                onSettingsSelected = {
                    navigateFromDrawer(viewModel::openSettings)
                },
                onBrowserSelected = {
                    navigateFromDrawer { viewModel.openBuiltInBrowser() }
                },
                onDigiCrewSelected = {
                    navigateFromDrawer(viewModel::openDigiCrew)
                },
                onUpaPluginSelected = {
                    navigateFromDrawer(viewModel::openUpaPlugin)
                },
                onRemoteSelected = {
                    navigateFromDrawer(viewModel::openRemote)
                },
            )
        }

        val bodyContent = @Composable {
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Box(modifier = Modifier.fillMaxSize()) {
        if (!uiState.isStartupRouteResolved) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherBackground)
            )
        } else {
            AnimatedContent(
                targetState = if (uiState.currentScreen.isSupportingPane()) {
                    AppScreen.Chat
                } else {
                    uiState.currentScreen
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    appScreenContentTransform(
                        initialState = initialState,
                        targetState = targetState,
                        reduceMotion = reduceMotion,
                    )
                },
                label = "app_screen_transition",
            ) { currentScreen ->
                Box(pageStackGpuLayer()) {
                when (currentScreen) {
                    AppScreen.Onboarding -> OnboardingScreen(
                        initialStep = uiState.onboardingStep,
                        replayMode = uiState.isOnboardingReplay,
                        setupPreviewMode = uiState.developerAlpineSetupPreviewState != null,
                        existingProviderConfig = activeProviderConfig,
                        isFetchingModels = uiState.isFetchingModels,
                        providerAuthState = uiState.providerAuthState,
                        piCoreSetupState = uiState.developerAlpineSetupPreviewState
                            ?: uiState.piCoreSetupState,
                        everMeBindingState = uiState.everMeBindingState,
                        termuxSetupState = effectiveTermuxSetupState,
                        alpineSetupState = if (uiState.developerAlpineSetupPreviewState != null) {
                            LocalRuntimeSetupState(
                                runtimeId = LocalRuntimeId.Alpine,
                                issue = LocalRuntimeIssue.Ready,
                            )
                        } else {
                            uiState.alpineSetupState
                        },
                        rootSetupState = uiState.rootSetupState,
                        agentModeAuthorizationMethod = uiState.settings.agentModeAuthorizationMethod,
                        tavilyApiKey = uiState.settings.tavilyApiKey,
                        onFetchModels = viewModel::fetchModels,
                        onStartProviderLogin = viewModel::startProviderLogin,
                        onSubmitProviderAuthPrompt = viewModel::submitProviderAuthPrompt,
                        onClearProviderAuthState = viewModel::clearProviderAuthState,
                        onSkip = viewModel::skipOnboarding,
                        onClose = viewModel::closeOnboarding,
                        onCompleteProviderSetup = viewModel::completeOnboardingProviderSetup,
                        onSaveTavilyApiKey = viewModel::saveOnboardingTavilyApiKey,
                        onRequestTermuxPermission = { requestTermuxPermission("onboarding_termux_permission") },
                        onOpenAppPermissions = {
                            startTermuxSetupAction("onboarding_app_permissions") { openAppPermissionSettings(context) }
                        },
                        onOpenTermuxSettings = {
                            startTermuxSetupAction("onboarding_termux_settings") { openTermuxSettings(context) }
                        },
                        onOpenTermux = {
                            startTermuxSetupAction("onboarding_open_termux") { openTermux(context) }
                        },
                        onInstallTermux = {
                            startTermuxSetupAction("onboarding_install_termux") { openTermuxInstallPage(context) }
                        },
                        onRefreshTermuxSetup = viewModel::refreshTermuxSetup,
                        onInitializeAlpineRuntime = {
                            if (uiState.developerAlpineSetupPreviewState != null) {
                                viewModel.restartDeveloperAlpineSetupPreview()
                            } else {
                                viewModel.initializeAlpineRuntime(makeDefault = true)
                            }
                        },
                        onRetryAlpineSetup = {
                            if (uiState.developerAlpineSetupPreviewState != null) {
                                viewModel.restartDeveloperAlpineSetupPreview()
                            } else {
                                viewModel.retryAlpineRuntimeSetup(makeDefault = true)
                            }
                        },
                        onRefreshAlpineSetup = viewModel::playOnboardingRuntimeSetup,
                        onRefreshEverMeBinding = viewModel::refreshEverMeBinding,
                        onStartEverMeBinding = viewModel::startEverMeBinding,
                        onCompleteEverMeBinding = viewModel::completeEverMeBinding,
                        onRefreshRootSetup = viewModel::refreshRootSetup,
                        onConfigureWithRoot = {
                            runRootSetupAfterTermuxPermission(
                                source = "onboarding_root_setup_termux_permission",
                                action = viewModel::configureLocalAccessWithRoot,
                            )
                        },
                        onSaveAgentModeAuthorization = { enabled, method ->
                            if (enabled && method == AgentModeAuthorizationMethod.Shizuku) {
                                viewModel.requestEnableAgentModeShizuku()
                            } else {
                                viewModel.saveOnboardingAgentModeAuthorization(enabled, method)
                            }
                        },
                        onCompleteFollowUp = viewModel::completeFollowUpOnboarding,
                    )

                    AppScreen.Chat -> AetherExtensionComponentHost(
                        target = AetherExtensionComponentChatScreen,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val activePersona = uiState.personas.firstOrNull { it.id == uiState.activePersonaId }
                        val personaChrome = activePersona?.let { persona ->
                            ConversationPersonaChrome(
                                name = persona.name.ifBlank {
                                    context.getString(R.string.persona_untitled)
                                },
                                onBack = viewModel::openPersona,
                                onEdit = { viewModel.openPersonaEditor(persona.id) },
                                onNewChat = { viewModel.startPersonaChat(persona) },
                            )
                        }
                        AetherPredictiveBackHandler(
                            enabled = !drawerState.isOpen &&
                                uiState.activePersonaId.isNotBlank() &&
                                uiState.currentScreen == AppScreen.Chat,
                        ) {
                            viewModel.openPersona()
                        }
                        CompositionLocalProvider(
                            LocalPhoneGuiStepsByToolCall provides uiState.agentModeLiveGuiStepsByToolCall,
                            LocalAgentModeTurnIdentityKey provides agentModeCrewIdentityKey(
                                conversationStateKey = uiState.currentSessionId,
                                lastUserMessageId = currentMessages.lastOrNull { message ->
                                    message.author == MessageAuthor.User
                                }?.id.orEmpty(),
                            ),
                            LocalAgentModeListening provides uiState.agentModeDisplayState.listening,
                            LocalAgentModeListenTranscript provides uiState.agentModeDisplayState.listenTranscript,
                            LocalAgentModeListenVisualOnly provides uiState.agentModeDisplayState.listenVisualOnly,
                            LocalAmapPlaceNavigateInChat provides viewModel::openAmapNavigationInChat,
                        ) {
                        conversationActions.onLoadOlderMessages = viewModel::loadOlderMessages
                        conversationActions.onGoalControl = viewModel::sendGoalControl
                        conversationActions.onSessionModeSelected = { modeId ->
                            viewModel.setSessionMode(uiState.currentSessionId, modeId)
                        }
                        conversationActions.onSendCommand = viewModel::sendCommandPrompt
                        conversationActions.onSetPromptDirective = viewModel::setComposerPromptDirective
                        conversationActions.onInputChanged = viewModel::updateDraftInput
                        conversationActions.onModelSelected =
                            viewModel::setCurrentChatModelSelectionAndResolveThinkingLevels
                        conversationActions.onModelSelectorOpened = viewModel::refreshCurrentChatThinkingLevels
                        conversationActions.onReasoningEffortSelected = viewModel::setReasoningEffort
                        conversationActions.onRemoveDraftAttachment = viewModel::removeDraftAttachment
                        conversationActions.onSetSkillSelected = viewModel::setComposerSkillSelected
                        conversationActions.onSetMcpServerSelected = viewModel::setComposerMcpServerSelected
                        conversationActions.onSetAgentModeSelected = { selected ->
                            viewModel.setComposerAgentModeSelected(selected)
                            if (selected) viewModel.requestEnableAgentModeShizuku()
                        }
                        conversationActions.onSetChromeSelected = viewModel::setComposerChromeSelected
                        conversationActions.onCancelEdit = viewModel::cancelMessageEdit
                        conversationActions.onSend = viewModel::sendCurrentMessage
                        conversationActions.onSteerPendingInput = viewModel::steerPendingInput
                        conversationActions.onMenu = { scope.launch { drawerState.open() } }
                        conversationActions.onNewChat = viewModel::startNewChat
                        conversationActions.personaChrome = personaChrome
                        conversationActions.onPickImages = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        conversationActions.onPickFiles = { filePicker.launch("*/*") }
                        conversationActions.onSaveAttachment = { attachment ->
                            pendingSaveTarget = PendingSaveTarget.Attachment(attachment)
                            saveAttachmentLauncher.launch(attachment.name)
                        }
                        conversationActions.onOpenLink = { rawLink ->
                            scope.launch {
                                handleAssistantLink(
                                    context = context,
                                    rawLink = rawLink,
                                    onSaveWorkspaceFile = { fileLink ->
                                        pendingSaveTarget = PendingSaveTarget.WorkspaceFile(fileLink)
                                        saveAttachmentLauncher.launch(
                                            workspaceFileBridge.resolveWorkspaceDownloadName(fileLink)
                                        )
                                    },
                                )
                            }
                        }
                        conversationActions.onEditMessage = { messageId ->
                            activeSession?.let { viewModel.startEditingUserMessage(it.id, messageId) }
                        }
                        conversationActions.onDeleteMessage = { messageId ->
                            activeSession?.let { viewModel.deleteMessage(it.id, messageId) }
                        }
                        conversationActions.onRedoAgentMessage = { messageId ->
                            activeSession?.let { viewModel.redoAgentMessage(it.id, messageId) }
                        }
                        conversationActions.onRetryUserMessage = { messageId ->
                            activeSession?.let { viewModel.retryUserMessage(it.id, messageId) }
                        }
                        conversationActions.onSwitchUserMessageBranch = { messageId, delta ->
                            activeSession?.let { viewModel.switchUserMessageBranch(it.id, messageId, delta) }
                        }
                        conversationActions.onCopyMessage = { message ->
                            clipboardManager.setText(AnnotatedString(message.text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.file_reply_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        conversationActions.onPlayMessage = { messageId ->
                            viewModel.onPlayMessage(messageId)
                        }
                        conversationActions.onRequestTermuxPermission = {
                            requestTermuxPermission("chat_termux_permission")
                        }
                        conversationActions.onOpenAppPermissions = {
                            startTermuxSetupAction("chat_app_permissions") { openAppPermissionSettings(context) }
                        }
                        conversationActions.onOpenTermuxSettings = {
                            startTermuxSetupAction("chat_termux_settings") { openTermuxSettings(context) }
                        }
                        conversationActions.onOpenTermux = {
                            startTermuxSetupAction("chat_open_termux") { openTermux(context) }
                        }
                        conversationActions.onInstallTermux = {
                            startTermuxSetupAction("chat_install_termux") { openTermuxInstallPage(context) }
                        }
                        conversationActions.onRefreshTermuxSetup = viewModel::refreshTermuxSetup
                        conversationActions.onAttachAgentModePreviewSurface =
                            viewModel::attachAgentModePreviewSurface
                        conversationActions.onDetachAgentModePreviewSurface =
                            viewModel::detachAgentModePreviewSurface
                        conversationActions.onBindAgentModePreviewSurfaceView =
                            viewModel::bindAgentModePreviewSurfaceView
                        conversationActions.onTapAgentModeDisplay = viewModel::tapAgentModeDisplay
                        conversationActions.onSwipeAgentModeDisplay = viewModel::swipeAgentModeDisplay
                        conversationActions.onSuppressAgentModeIme = viewModel::suppressAgentModeIme
                        conversationActions.onFinishAgentModeTeaching = viewModel::finishAgentModeTeaching
                        conversationActions.onToggleAgentModeTeaching = viewModel::toggleAgentModeTeaching
                        conversationActions.onPauseGeneration = viewModel::pauseGeneration
                        conversationActions.onDismissTermuxSetupNotice = viewModel::dismissTermuxSetupNotice
                        conversationActions.onDismissStarterPromptHint = viewModel::dismissStarterPromptHint
                        conversationActions.onAnswerPermissionRequest = viewModel::answerPermissionRequest
                        conversationActions.onAnswerElicitationRequest = viewModel::answerElicitationRequest
                        conversationActions.onSubmitMcpSecrets = viewModel::submitPendingMcpSecrets
                        conversationActions.onSkipMcpSecrets = viewModel::skipPendingMcpSecrets
                        conversationActions.onRequestRecordAudio = {
                            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        conversationActions.onActivateSpotifyOverlay = viewModel::activateSpotifyOverlay
                        conversationActions.onSetSpotifyOverlayDocked = viewModel::setSpotifyOverlayDocked
                        conversationActions.onSetSpotifyOrbMenuOpen = viewModel::setSpotifyOrbMenuOpen
                        conversationActions.onSetSpotifyOrbUnlocked = viewModel::setSpotifyOrbUnlocked
                        conversationActions.onSetSpotifyOrbOffsetY = viewModel::setSpotifyOrbOffsetY
                        conversationActions.onDestroySpotifyOverlay = viewModel::destroySpotifyOverlay
                        ConversationScreen(
                            state = ConversationScreenState(
                                conversationStateKey = uiState.currentSessionId,
                                messages = currentMessages,
                                hasOlderMessages = (activeSession?.loadedFromPosition ?: 0) > 0,
                                workspaceDirectory = currentRuntimeWorkspaceDirectory,
                                pendingToolInvocations = pendingToolInvocations,
                                pendingToolInvocationStateKey = "pending-tools-${uiState.currentSessionId}",
                                pendingResponseBlocks = pendingResponseBlocks,
                                pendingAssistantText = pendingAssistantText,
                                pendingStatusText = currentSessionExecution?.pendingStatusText.orEmpty(),
                                pendingStatusDetail = currentSessionExecution?.pendingStatusDetail.orEmpty(),
                                isPreparingWorkspace = currentSessionExecution?.isPreparingWorkspace == true,
                                activeResponseGroupId = currentSessionExecution?.activeResponseGroupId,
                                activeResponseMessageIdPrefix = currentSessionExecution?.activeResponseMessageIdPrefix,
                                activeTurnStartedAtMillis = currentSessionExecution?.activeTurnStartedAtMillis,
                                activeTurnInteractionClock = currentSessionExecution?.activeTurnInteractionClock,
                                isCompacting = uiState.compactingSessionId == uiState.currentSessionId,
                                pendingInputs = pendingInputs,
                                inputValue = uiState.draftInput,
                                draftAttachments = uiState.draftAttachments,
                                modelOptions = conversationModelOptions,
                                modelCatalogInfo = uiState.modelCatalogInfo,
                                selectedModelKey = selectedConversationModelKey,
                                reasoningEffort = uiState.settings.reasoningEffort,
                                thinkingLevelsByProviderModel = uiState.thinkingLevelsByProviderModel,
                                thinkingLevelClampsByProviderModel = uiState.thinkingLevelClampsByProviderModel,
                                agentSlashCommands = uiState.agentSlashCommands,
                                workspaceFileSuggestions = uiState.workspaceFileSuggestions,
                                pendingPermissionRequests = sessionPermissionRequests,
                                pendingElicitationRequests = sessionElicitationRequests,
                                pendingMcpSecretPrompt = uiState.pendingMcpSecretPrompt?.takeIf {
                                    it.sessionId == uiState.currentSessionId
                                },
                                planEntries = currentSessionPlanEntries,
                                planAnchorMessageId = currentSessionExecution?.planAnchorMessageId,
                                planAnchorGroupId = currentSessionExecution?.planAnchorGroupId,
                                planDocumentMarkdown = currentSessionExecution?.planDocumentMarkdown.orEmpty(),
                                goalSnapshot = currentSessionExecution?.goalSnapshot,
                                acpContextUsage = currentSessionContextUsage,
                                sessionModeId = currentSessionModeId,
                                promptDirective = uiState.draftPromptDirective,
                                availableSkills = uiState.installedSkills.filter { it.isEnabled },
                                availableMcpServers = uiState.mcpServers.filter { it.isEnabled },
                                selectedSkillIds = selectedSkillIds,
                                selectedMcpServerIds = selectedMcpServerIds,
                                agentModeAvailable = agentModeReady,
                                agentModeSelected = agentModeSelected,
                                agentModeDisplayState = uiState.agentModeDisplayState,
                                chromeAvailable = chromeAvailable,
                                chromeSelected = chromeSelected,
                                chromeDisplayState = uiState.chromeDisplayState,
                                browserDeskState = uiState.browserDeskState,
                                allowRootImageRead = uiState.rootSetupState.isReady ||
                                    (
                                        uiState.settings.agentModeAuthorizationEnabled &&
                                            uiState.settings.agentModeAuthorizationMethod ==
                                            AgentModeAuthorizationMethod.Root &&
                                            uiState.agentModeAuthorizationState.isReady
                                        ),
                                isEditing = uiState.editingMessageId != null,
                                showMenu = !usePermanentDrawer,
                                termuxSetupState = effectiveTermuxSetupState,
                                showStarterPromptHint = uiState.showStarterPromptHint,
                                showTermuxSetupNotice = false,
                                phoneDeskHandoff = uiState.phoneDeskHandoff,
                                phoneSettlement = uiState.phoneSettlement,
                                agentModeReviewingEverMe = uiState.agentModeReviewingEverMe,
                                isSending = isCurrentSessionRunning ||
                                    uiState.pendingMcpSecretPrompt?.sessionId == uiState.currentSessionId,
                                composerInteractive = uiState.currentScreen == AppScreen.Chat || useWideLayout,
                                asrAvailable = asrAvailable,
                                appSettings = uiState.settings,
                                providerConfigs = uiState.providerConfigs,
                                recordAudioGranted = recordAudioGranted,
                                spotifyOverlay = uiState.spotifyOverlay,
                                ttsPlaybackState = ttsPlaybackState,
                            ),
                            actions = conversationActions,
                        )
                        }
                    }

                    AppScreen.Settings -> AetherExtensionComponentHost(
                        target = AetherExtensionComponentSettingsScreen,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SettingsScreen(
                            state = settingsState,
                            actions = settingsActions,
                        )
                    }

                    AppScreen.DigiCrew -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AetherSettingsBackground),
                    ) {
                        DigiCrewScreen(
                        personas = uiState.personas,
                        installedSkills = uiState.installedSkills,
                        mcpServers = uiState.mcpServers,
                        knowledgeImport = uiState.personaKnowledgeImport,
                        onSavePersona = viewModel::savePersona,
                        onDeletePersona = viewModel::deletePersona,
                        onImportKnowledge = { persona, bytes, displayName, mimeType ->
                            viewModel.importPersonaKnowledge(persona, bytes, displayName, mimeType)
                        },
                        onRemoveKnowledge = viewModel::removePersonaKnowledge,
                        onStartChat = viewModel::openPersonaConversation,
                        onJoinInvite = viewModel::joinDigiCrewByInvite,
                        onBack = viewModel::closePersona,
                        initialEditId = uiState.personaEditorId,
                        onConsumedEditId = viewModel::consumePersonaEditorRequest,
                    )
                    }

                    AppScreen.UpaPlugin -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AetherSettingsBackground),
                    ) {
                        UpaPluginScreen(
                            installedPlugins = uiState.installedUpaPlugins,
                            isCheckingUpdates = uiState.isCheckingUpaPluginUpdates,
                            disabledUiPlugins = uiState.settings.upaDisabledUiPlugins,
                            revokedPermissions = uiState.settings.upaRevokedPermissions,
                            mcpServers = uiState.mcpServers,
                            mcpBindings = uiState.settings.upaMcpBindings,
                            onPluginRenderUiChange = viewModel::updateUpaPluginRenderUi,
                            onPluginPermissionChange = viewModel::updateUpaPluginPermission,
                            onPluginMcpBindingChange = viewModel::updateUpaPluginMcpBinding,
                            onScanSource = { source, onProgress, onDone ->
                                viewModel.previewUpaPlugin(source, onProgress, onDone)
                            },
                            onInstallPreview = viewModel::installUpaPlugin,
                            onUninstall = viewModel::uninstallUpaPlugin,
                            onCheckUpdates = viewModel::checkUpaPluginUpdates,
                            onBack = viewModel::closeSettings,
                        )
                    }

                    AppScreen.Remote -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AetherSettingsBackground),
                    ) {
                        RemoteScreen(
                            machines = uiState.settings.remoteMachines,
                            sessions = uiState.sessions,
                            isConnecting = uiState.isConnectingRemote,
                            localRunning = uiState.localRemoteControlRunning,
                            localUrl = uiState.localRemoteControlUrl,
                            localBusy = uiState.localRemoteControlBusy,
                            onConnectUrl = viewModel::connectRemoteMachine,
                            onOpenRemote = { machine -> viewModel.prepareRemoteSession(machine.id) },
                            onRemoveRemote = { machine -> viewModel.removeRemoteMachine(machine.id) },
                            onStartLocal = viewModel::startLocalRemoteControl,
                            onStopLocal = viewModel::stopLocalRemoteControl,
                            onRefreshLocal = viewModel::refreshLocalRemoteControl,
                            onBack = viewModel::closeSettings,
                        )
                    }

                }
        }
            }
        }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = supportingScreen != null,
            modifier = if (useWideLayout) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            },
            // Opaque full-width sheet slide: no alpha fade, otherwise the chat
            // composer underneath shows through the pane as a "ghost" mid-transition.
            enter = slideInHorizontally(
                animationSpec = tween(ScreenTransitionDuration, easing = ScreenTransitionEasing),
                initialOffsetX = { width -> width },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(240, easing = ScreenTransitionEasing),
                targetOffsetX = { width -> width },
            ),
        ) {
            val supportingPaneAnimating = transition.isRunning
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (useWideLayout) Modifier.width(paneWidth + 1.dp) else Modifier.fillMaxWidth(),
                    ),
            ) {
                if (useWideLayout) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(AetherOutlineSoft.copy(alpha = 0.55f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            if (useWideLayout) {
                                Modifier
                                    .width(paneWidth)
                                    .background(AetherSettingsBackground)
                            } else {
                                Modifier.fillMaxWidth()
                            },
                        )
                        .clipToBounds()
                        .graphicsLayer {
                            compositingStrategy = if (supportingPaneAnimating) {
                                CompositingStrategy.Offscreen
                            } else {
                                CompositingStrategy.Auto
                            }
                        },
                ) {
                    when (paneScreen) {
                        AppScreen.Settings -> AetherExtensionComponentHost(
                            target = AetherExtensionComponentSettingsScreen,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Settings stays mounted in this supporting pane on wide layouts.
                            SettingsScreen(
                                state = settingsState,
                                actions = settingsActions,
                            )
                        }
                        AppScreen.DigiCrew -> DigiCrewScreen(
                            personas = uiState.personas,
                            installedSkills = uiState.installedSkills,
                            mcpServers = uiState.mcpServers,
                            knowledgeImport = uiState.personaKnowledgeImport,
                            onSavePersona = viewModel::savePersona,
                            onDeletePersona = viewModel::deletePersona,
                            onImportKnowledge = { persona, bytes, displayName, mimeType ->
                                viewModel.importPersonaKnowledge(persona, bytes, displayName, mimeType)
                            },
                            onRemoveKnowledge = viewModel::removePersonaKnowledge,
                            onStartChat = viewModel::openPersonaConversation,
                            onJoinInvite = viewModel::joinDigiCrewByInvite,
                            onBack = viewModel::closePersona,
                            initialEditId = uiState.personaEditorId,
                            onConsumedEditId = viewModel::consumePersonaEditorRequest,
                        )
                        AppScreen.UpaPlugin -> UpaPluginScreen(
                            installedPlugins = uiState.installedUpaPlugins,
                            isCheckingUpdates = uiState.isCheckingUpaPluginUpdates,
                            disabledUiPlugins = uiState.settings.upaDisabledUiPlugins,
                            revokedPermissions = uiState.settings.upaRevokedPermissions,
                            mcpServers = uiState.mcpServers,
                            mcpBindings = uiState.settings.upaMcpBindings,
                            onPluginRenderUiChange = viewModel::updateUpaPluginRenderUi,
                            onPluginPermissionChange = viewModel::updateUpaPluginPermission,
                            onPluginMcpBindingChange = viewModel::updateUpaPluginMcpBinding,
                            onScanSource = { source, onProgress, onDone ->
                                viewModel.previewUpaPlugin(source, onProgress, onDone)
                            },
                            onInstallPreview = viewModel::installUpaPlugin,
                            onUninstall = viewModel::uninstallUpaPlugin,
                            onCheckUpdates = viewModel::checkUpaPluginUpdates,
                            onBack = viewModel::closeSettings,
                        )
                        AppScreen.Remote -> RemoteScreen(
                            machines = uiState.settings.remoteMachines,
                            sessions = uiState.sessions,
                            isConnecting = uiState.isConnectingRemote,
                            localRunning = uiState.localRemoteControlRunning,
                            localUrl = uiState.localRemoteControlUrl,
                            localBusy = uiState.localRemoteControlBusy,
                            onConnectUrl = viewModel::connectRemoteMachine,
                            onOpenRemote = { machine -> viewModel.prepareRemoteSession(machine.id) },
                            onRemoveRemote = { machine -> viewModel.removeRemoteMachine(machine.id) },
                            onStartLocal = viewModel::startLocalRemoteControl,
                            onStopLocal = viewModel::stopLocalRemoteControl,
                            onRefreshLocal = viewModel::refreshLocalRemoteControl,
                            onBack = viewModel::closeSettings,
                        )
                        else -> Unit
                    }
                }
            }
        }
        }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !usePermanentDrawer &&
                (uiState.currentScreen == AppScreen.Chat ||
                    drawerState.currentValue != DrawerValue.Closed ||
                    drawerState.targetValue != DrawerValue.Closed),
            scrimColor = AetherScrim,
            // On wide layouts the drawer is rendered permanently in the Row below, so
            // composing it here too would build the whole session list twice.
            drawerContent = { if (!usePermanentDrawer) drawerContent(false) },
            content = {
                val chatPaused = !usePermanentDrawer &&
                    (drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open)
                Row(modifier = Modifier.fillMaxSize()) {
                    if (usePermanentDrawer) {
                        drawerContent(true)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        CompositionLocalProvider(LocalChatActivityPaused provides chatPaused) {
                            bodyContent()
                        }
                    }
                }
            },
        )

        val availableUpdate = uiState.appUpdate.availableRelease
        if (
            uiState.appUpdate.showAvailableDialog &&
            availableUpdate != null
        ) {
            AppUpdateAvailableDialog(
                updateState = uiState.appUpdate,
                onDismiss = viewModel::dismissUpdateAvailableDialog,
                onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
            )
        }
        if (showAppDataExportWarning) {
            AlertDialog(
                onDismissRequest = { showAppDataExportWarning = false },
                containerColor = AetherSurface,
                titleContentColor = AetherOnSurface,
                textContentColor = AetherOnSurfaceVariant,
                title = {
                    Text(stringResource(R.string.settings_export_app_data_warning_title))
                },
                text = {
                    Text(stringResource(R.string.settings_export_app_data_warning_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAppDataExportWarning = false
                            appDataExportLauncher.launch("aether-data.json")
                        },
                    ) {
                        Text(stringResource(R.string.common_export))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAppDataExportWarning = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        if (uiState.pendingShizukuInstallConsent) {
            AlertDialog(
                onDismissRequest = viewModel::dismissShizukuInstallConsent,
                containerColor = AetherSurface,
                titleContentColor = AetherOnSurface,
                textContentColor = AetherOnSurfaceVariant,
                title = {
                    Text(stringResource(R.string.settings_shizuku_install_consent_title))
                },
                text = {
                    Text(stringResource(R.string.settings_shizuku_install_consent_message))
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmShizukuInstall) {
                        Text(stringResource(R.string.common_agree))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissShizukuInstallConsent) {
                        Text(stringResource(R.string.common_decline))
                    }
                },
            )
        }
    }
}

@Composable
private fun PrivacyPolicyConsentDialog(
    onOpenPolicy: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = AetherSurface,
        titleContentColor = AetherOnSurface,
        textContentColor = AetherOnSurfaceVariant,
        title = {
            Text(
                text = stringResource(R.string.app_privacy_policy_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            val policyText = stringResource(R.string.app_privacy_policy_title)
            val messagePrefix = stringResource(R.string.app_privacy_policy_message_prefix)
            val messageSuffix = stringResource(R.string.app_privacy_policy_message_suffix)
            val annotatedText = buildAnnotatedString {
                append(messagePrefix)
                pushStringAnnotation(
                    tag = PrivacyPolicyAnnotationTag,
                    annotation = AetherPrivacyPolicyUrl,
                )
                withStyle(SpanStyle(color = Color(0xFF3B82F6))) {
                    append(policyText)
                }
                pop()
                append(messageSuffix)
            }
            ClickableText(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurfaceVariant),
                onClick = { offset ->
                    annotatedText
                        .getStringAnnotations(PrivacyPolicyAnnotationTag, offset, offset)
                        .firstOrNull()
                        ?.let { onOpenPolicy() }
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AetherPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(R.string.common_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(
                    text = stringResource(R.string.common_decline),
                    color = AetherOnSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AppUpdateAvailableDialog(
    updateState: AppUpdateUiState,
    onDismiss: () -> Unit,
    onDownloadAndInstall: () -> Unit,
) {
    val release = updateState.availableRelease ?: return
    val progress = updateState.downloadProgress
    AlertDialog(
        onDismissRequest = {
            if (!updateState.isDownloading) onDismiss()
        },
        containerColor = AetherSurface,
        titleContentColor = AetherOnSurface,
        textContentColor = AetherOnSurfaceVariant,
        title = {
            Text(
                text = stringResource(R.string.app_update_available),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.app_update_available_message, release.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
                if (updateState.isDownloading) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AetherPrimary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = if (progress != null) {
                                "${(progress * 100).toInt()}%"
                            } else {
                                stringResource(R.string.app_downloading)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadAndInstall,
                enabled = !updateState.isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AetherPrimary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = stringResource(R.string.app_download_and_install),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !updateState.isDownloading,
            ) {
                Text(
                    text = stringResource(R.string.common_later),
                    color = AetherOnSurfaceVariant,
                )
            }
        },
    )
}

private fun saveAttachmentToDocument(
    context: android.content.Context,
    attachment: ChatAttachment,
    destinationUri: Uri,
): Boolean = runCatching {
    val resolver = context.contentResolver
    val sourceUri = Uri.parse(attachment.uri)
    val expectedSize = resolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0L }
    }
    var copiedBytes = 0L
    resolver.openInputStream(sourceUri)?.use { input ->
        resolver.openOutputStream(destinationUri, "w")?.use { output ->
            copiedBytes = input.copyTo(output)
            output.flush()
        }
    } != null && (expectedSize == null || expectedSize == copiedBytes)
}.getOrDefault(false)

private suspend fun handleAssistantLink(
    context: android.content.Context,
    rawLink: String,
    onSaveWorkspaceFile: (String) -> Unit,
) {
    parseAssistantLocalFileLink(rawLink)?.let { localPath ->
        onSaveWorkspaceFile(localPath)
        return
    }

    val normalizedLink = normalizeAssistantLink(rawLink)
    if (looksLikeWorkspaceFileLink(normalizedLink)) {
        onSaveWorkspaceFile(normalizedLink)
        return
    }
    if (looksLikeHttpUrl(normalizedLink)) {
        BrowserDesk.requestOpenUrl(normalizedLink)
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedLink)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, context.getString(R.string.app_unable_to_open_link), Toast.LENGTH_SHORT).show()
    }
}

private fun requestApkInstall(
    context: android.content.Context,
    apkUri: Uri,
) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, context.getString(R.string.app_unable_to_open_apk_installer), Toast.LENGTH_SHORT).show()
    }
}

internal fun shouldAutoRequestTermuxPermission(
    isStartupRouteResolved: Boolean,
    privacyPolicyAccepted: Boolean,
    setupIssue: TermuxSetupIssue,
    didAutoRequest: Boolean,
): Boolean = isStartupRouteResolved &&
    privacyPolicyAccepted &&
    setupIssue == TermuxSetupIssue.PermissionMissing &&
    !didAutoRequest

internal fun shouldRequestTermuxPermissionBeforeRootSetup(
    setupIssue: TermuxSetupIssue,
): Boolean = setupIssue == TermuxSetupIssue.PermissionMissing

internal fun shouldResumeRootSetupAfterTermuxPermission(
    rootSetupIssue: kira.ditto.data.RootSetupIssue,
): Boolean = rootSetupIssue == kira.ditto.data.RootSetupIssue.TermuxNotInstalled

private fun normalizeAssistantLink(rawLink: String): String {
    val trimmed = rawLink.trim().removeSurrounding("<", ">")
    if (trimmed.isBlank()) return trimmed
    if (looksLikeWorkspaceFileLink(trimmed)) return trimmed
    if (trimmed.contains("://")) return trimmed
    if (trimmed.startsWith("www.", ignoreCase = true)) {
        return "https://$trimmed"
    }
    return if (Patterns.WEB_URL.matcher(trimmed).matches() && !trimmed.startsWith("/")) {
        "https://$trimmed"
    } else {
        trimmed
    }
}

private fun looksLikeWorkspaceFileLink(rawLink: String): Boolean {
    val trimmed = rawLink.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("file://", ignoreCase = true)) return true
    if (trimmed.startsWith("~/")) return true
    if (trimmed.startsWith("/")) return true
    return false
}

private fun resolveConversationModelKey(
    session: ChatSession?,
    draftSelectedModelKey: String,
    defaultChatModelKey: String,
    options: List<ProviderModelOption>,
): String {
    val preferredKey = session?.selectedModelKey
        ?.takeIf { key -> options.any { it.key == key } }
        ?: draftSelectedModelKey.takeIf { key -> options.any { it.key == key } }
        ?: defaultChatModelKey.takeIf { key -> options.any { it.key == key } }
    return preferredKey ?: options.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
}

private fun openAppPermissionSettings(
    context: android.content.Context,
) {
    val managePermissionsIntent = Intent("android.intent.action.MANAGE_APP_PERMISSIONS").apply {
        putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, managePermissionsIntent) {
        launchIntentSafely(context, fallbackIntent)
    }
}

private fun openTermuxSettings(
    context: android.content.Context,
) {
    val intent = Intent().apply {
        setClassName(TermuxContract.PackageName, TermuxContract.TermuxSettingsActivity)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        openTermux(context)
    }
}

private fun openTermux(
    context: android.content.Context,
) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(TermuxContract.PackageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (launchIntent != null) {
        launchIntentSafely(context, launchIntent)
    } else {
        openTermuxInstallPage(context)
    }
}

private fun openTermuxInstallPage(
    context: android.content.Context,
) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://f-droid.org/en/packages/com.termux/"),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent)
}

private fun openShizuku(
    context: android.content.Context,
) {
    val launchIntent = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
    ).firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
    if (launchIntent != null) {
        launchIntentSafely(context, launchIntent)
    } else {
        openShizukuInstallPage(context)
    }
}

private fun openShizukuInstallPage(
    context: android.content.Context,
) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://shizuku.rikka.app/download/"),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent)
}

private fun openPrivacyPolicy(
    context: android.content.Context,
) {
    openExternalUrl(context, AetherPrivacyPolicyUrl)
}

private fun openExternalUrl(
    context: android.content.Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchIntentSafely(context, intent) {
        Toast.makeText(context, context.getString(R.string.app_unable_to_open_link), Toast.LENGTH_SHORT).show()
    }
}

private fun launchIntentSafely(
    context: android.content.Context,
    intent: Intent,
    fallback: (() -> Unit)? = null,
) {
    val launchResult = runCatching {
        context.startActivity(intent)
    }
    if (launchResult.isSuccess) {
        return
    }

    val failure = launchResult.exceptionOrNull()
    if (failure is ActivityNotFoundException || failure is SecurityException) {
        if (fallback != null) {
            fallback()
        } else {
            Toast.makeText(context, context.getString(R.string.app_unable_to_open_screen), Toast.LENGTH_SHORT).show()
        }
    } else if (fallback != null) {
        fallback()
    } else {
        Toast.makeText(context, context.getString(R.string.app_unable_to_open_screen), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ChatScreen(
    messages: List<ChatMessage>,
    inputValue: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    isSending: Boolean,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isSending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        topBar = { ChatTopBar(onMenu, onNewChat) },
        bottomBar = { ComposerBar(inputValue, onInputChanged, onSend, messages.isNotEmpty(), isSending) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(AetherBackground, Color(0xFF0B0B0D), Color(0xFF070708))
                    )
                )
                .padding(innerPadding)
        ) {
            if (messages.isEmpty()) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (isSending) {
                        item {
                            TypingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfaceIconButton(Icons.Rounded.Menu, stringResource(R.string.common_menu), onMenu)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SurfaceIconButton(Icons.Rounded.Create, stringResource(R.string.common_new_chat), onNewChat)
            SurfaceIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.common_more), {})
        }
    }
}

@Composable
private fun EmptyChatState() {
    val actions = listOf(
        SuggestionAction(Icons.Rounded.Image, stringResource(R.string.chat_create_image), Color(0xFF22C55E)),
        SuggestionAction(Icons.Rounded.Lightbulb, stringResource(R.string.chat_brainstorm), Color(0xFFFACC15)),
        SuggestionAction(Icons.Rounded.Visibility, stringResource(R.string.chat_analyze_images), AetherPrimary),
        SuggestionAction(Icons.Rounded.AutoAwesome, stringResource(R.string.common_more), AetherPrimary),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_welcome_help),
            style = MaterialTheme.typography.headlineMedium,
            color = AetherOnSurface,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.take(2).forEach { action ->
                    SuggestionChip(Modifier.weight(1f), action)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.drop(2).forEach { action ->
                    SuggestionChip(Modifier.weight(1f), action)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    modifier: Modifier = Modifier,
    action: SuggestionAction,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0C0D10))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = action.tint,
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.author == MessageAuthor.User) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = message.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF5B36D7), Color(0xFF6E48FF)))
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurfaceVariant,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = AetherPrimary,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.chat_aether_is_thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    hasMessages: Boolean,
    isSending: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(AetherSurfaceHigher)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleAction(Icons.Rounded.Add, {})
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = if (hasMessages) stringResource(R.string.chat_reply_to_aether) else stringResource(R.string.chat_ask_aether),
                    color = AetherOnSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AetherOnSurface),
                cursorBrush = SolidColor(AetherOnSurface),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = {}, enabled = !isSending) {
            Icon(
                imageVector = Icons.Rounded.KeyboardVoice,
                contentDescription = stringResource(R.string.chat_voice),
                tint = AetherOnSurfaceVariant,
            )
        }
        CircleAction(
            icon = if (value.isBlank()) Icons.Rounded.AutoAwesome else Icons.Rounded.ArrowUpward,
            onClick = onSend,
            enabled = !isSending,
            highlighted = value.isNotBlank(),
            containerColor = AetherPrimary,
            contentColor = Color.White,
        )
    }
}

@Composable
private fun AppDrawer(
    sessions: List<ChatSession>,
    selectedSessionId: String,
    onNewChat: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSettingsSelected: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(312.dp),
        drawerContainerColor = AetherSurface,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBarStub(modifier = Modifier.weight(1f))
                SurfaceIconButton(Icons.Rounded.Create, stringResource(R.string.common_new_chat), onNewChat)
            }

            Spacer(modifier = Modifier.height(14.dp))
            DrawerPrimaryAction(Icons.Rounded.Create, stringResource(R.string.common_new_chat), onNewChat)
            DrawerPrimaryAction(Icons.Rounded.Image, stringResource(R.string.chat_images), {})
            DrawerPrimaryAction(Icons.Rounded.GridView, stringResource(R.string.chat_apps), {})
            DrawerPrimaryAction(Icons.Rounded.AutoAwesome, stringResource(R.string.chat_gpts), {})

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.chat_recent),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_no_conversations_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            onClick = { onSessionSelected(session.id) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Spacer(modifier = Modifier.height(12.dp))
            DrawerPrimaryAction(Icons.Rounded.Settings, stringResource(R.string.settings_title), onSettingsSelected)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SearchBarStub(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurfaceHigher)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.common_search),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun DrawerPrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = AetherOnSurface)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherSurfaceHigher else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = session.usableTitle(),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = session.preview.ifBlank { stringResource(R.string.chat_empty_draft) },
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun effectiveTermuxSetupState(
    setupState: TermuxSetupState,
    developerOverride: Boolean?,
    termuxSetupCompleted: Boolean,
): TermuxSetupState = when (developerOverride) {
    true -> TermuxSetupState(previouslyConfigured = termuxSetupCompleted)
    false -> TermuxSetupState(
        issue = TermuxSetupIssue.DispatchFailed,
        detail = "Developer override: Termux is treated as not ready.",
        previouslyConfigured = termuxSetupCompleted,
    )
    null -> if (termuxSetupCompleted && setupState.issue == TermuxSetupIssue.ExternalAppsDisabled) {
        setupState.copy(
            issue = TermuxSetupIssue.DispatchFailed,
            detail = "",
            previouslyConfigured = true,
        )
    } else {
        setupState.copy(
            previouslyConfigured = setupState.previouslyConfigured || termuxSetupCompleted,
        )
    }
}

// SettingsScreen is now in SettingsScreen.kt

@Composable
private fun SurfaceIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(12.dp, CircleShape, ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(CircleShape)
            .background(AetherSurface.copy(alpha = 0.88f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AetherOnSurface,
        )
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = enabled,
    containerColor: Color = AetherSurfaceHigh,
    contentColor: Color = AetherOnSurface,
) {
    val highlight by animateFloatAsState(
        targetValue = if (enabled && highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)),
        label = "circle_action_highlight",
    )
    val fill = lerp(containerColor.copy(alpha = 0.28f), containerColor, highlight)
    val tint = lerp(contentColor.copy(alpha = 0.42f), contentColor, highlight)
    Box(
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                val scale = 0.92f + 0.08f * highlight
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AetherAppPreview() {
    AetherTheme {
        ChatScreen(
            messages = defaultPreviewMessages(),
            inputValue = "",
            onInputChanged = {},
            onSend = {},
            onMenu = {},
            onNewChat = {},
            isSending = false,
        )
    }
}

private fun defaultPreviewMessages(): List<ChatMessage> = listOf(
    ChatMessage("preview-user", MessageAuthor.User, "How should I wire this app to an LLM API?"),
    ChatMessage(
        "preview-agent",
        MessageAuthor.Agent,
        "Start with persistent settings, then call a configurable OpenAI-compatible chat endpoint from the composer.",
    ),
)
