package kira.ditto.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kira.ditto.AetherApplication
import kira.ditto.data.AppSettings
import kira.ditto.data.AsrEngineRouter
import kira.ditto.data.ComposerAsrSession
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherOnPrimary
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.EngineView
import mozilla.components.lib.state.ext.flow

/** The start page is ours, not a search engine's. */
internal const val AetherBrowserHome = "about:home"

private class ActivityEngineViewHost(
    val engineView: EngineView,
    var renderedTabId: String? = null,
)

/** What the bottom chrome needs from the store, recomputed on every content change. */
private data class BrowserChrome(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val tabCount: Int = 0,
    // Not drawn anywhere. They are here so that switching tabs, or the engine session being
    // created late (it is linked lazily), still counts as a change worth recomposing for —
    // AndroidView's update block is the only thing that can call render(), and it only runs on
    // recomposition.
    val tabId: String = "",
    val hasSession: Boolean = false,
)

private data class BrowserTabRow(
    val id: String,
    val title: String,
    val url: String,
    val selected: Boolean,
)

private fun chromeOf(state: BrowserState): BrowserChrome {
    val tab = state.selectedTab
    return BrowserChrome(
        url = tab?.content?.url.orEmpty(),
        title = tab?.content?.title.orEmpty(),
        loading = tab?.content?.loading == true,
        progress = tab?.content?.progress ?: 0,
        canGoBack = tab?.content?.canGoBack == true,
        canGoForward = tab?.content?.canGoForward == true,
        tabCount = state.tabs.size,
        tabId = tab?.id.orEmpty(),
        hasSession = tab?.engineState?.engineSession != null,
    )
}

private fun tabRowsOf(state: BrowserState): List<BrowserTabRow> = state.tabs.map { entry ->
    BrowserTabRow(
        id = entry.id,
        title = entry.content.title,
        url = entry.content.url,
        selected = entry.id == state.selectedTabId,
    )
}

/**
 * The chrome is one row at the bottom: back, forward, the address pill, voice, tabs. The page owns
 * everything above it — on a phone the reachable edge is the bottom one, and the content should
 * not pay a toolbar's height at the top as well.
 */
class BrowserActivity : AppCompatActivity() {
    private var engineHost: ActivityEngineViewHost? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AetherApplication
        val bootSettings = runBlocking { app.runtime.settingsRepository.settings.first() }
        val prefs = bootSettings.browserPreferences
        AetherBrowserRuntime.updatePreferences(prefs, agentSession = false)
        AetherBrowserRuntime.loginVault = app.runtime.browserLoginVault
        val components = AetherBrowserRuntime.get(this)
        intent.getStringExtra(ExtraUrl)?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            components.navigate(normalizeBrowserAddress(raw, prefs))
        }
        setContent {
            // Seeded from the store rather than from defaults: the tabs may already be restored
            // and settled by the time this composes, and a chrome that starts empty also starts
            // covering the page with the home surface.
            var chrome by remember { mutableStateOf(chromeOf(components.store.state)) }
            var tabRows by remember { mutableStateOf(tabRowsOf(components.store.state)) }
            var editing by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf(TextFieldValue("")) }
            var menuOpen by remember { mutableStateOf(false) }
            var tabsOpen by remember { mutableStateOf(false) }
            var homeRequested by remember { mutableStateOf(false) }
            var search by remember { mutableStateOf<BrowserSearchState?>(null) }
            val answerSessionId = remember { BrowserAnswer.newSessionId() }
            var listening by remember { mutableStateOf(false) }
            // The home surface is ours and lives above the engine; a real page in the selected tab
            // is what dismisses it.
            val atHome = homeRequested || chrome.url.isBlank() || isSeedBrowserUrl(chrome.url)
            val focusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val clipboard = LocalClipboardManager.current
            val scope = rememberCoroutineScope()

            val settings by app.runtime.settingsRepository.settings.collectAsState(initial = bootSettings)
            val providerConfigs by app.runtime.settingsRepository.providerConfigs
                .collectAsState(initial = emptyList())
            val asrSession = remember {
                ComposerAsrSession(this@BrowserActivity, AsrEngineRouter(this@BrowserActivity) { providerConfigs })
            }
            DisposableEffect(Unit) { onDispose { asrSession.cancel() } }

            // store.flow() replays the current state on collection. observeManually does not: it
            // only delivers the *next* change, so opening onto an already-settled tab left the
            // chrome empty and the page unrendered.
            // The answer streams through the execution manager, not the chat store — this session
            // is intentionally absent from the store, so nothing is persisted.
            val executions = app.runtime.sessionExecutionManager
            val executionState by executions.executionStates.collectAsState()
            LaunchedEffect(executionState) {
                val live = executionState[answerSessionId] ?: return@LaunchedEffect
                val current = search ?: return@LaunchedEffect
                val blocks = live.pendingResponseBlocks
                search = current.copy(
                    answer = current.answer.copy(
                        running = live.isRunning,
                        // Completion clears the pending blocks; keep the last non-empty set so the
                        // finished answer stays on screen.
                        blocks = if (blocks.isNotEmpty()) blocks else current.answer.blocks,
                        responseGroupId = live.activeResponseGroupId
                            ?: current.answer.responseGroupId,
                        messageIdPrefix = live.activeResponseMessageIdPrefix
                            ?: current.answer.messageIdPrefix,
                    ),
                )
            }

            LaunchedEffect(components) {
                components.store.flow().collect { state ->
                    chrome = chromeOf(state)
                    tabRows = tabRowsOf(state)
                    val tab = state.selectedTab
                    val url = tab?.content?.url.orEmpty()
                    val title = tab?.content?.title.orEmpty()
                    if (url.isNotBlank() && !isSeedBrowserUrl(url)) homeRequested = false
                    AetherBrowserRuntime.updateVisitTitle(url, title)
                    AetherBrowserRuntime.publishChrome(
                        AetherBrowserRuntime.ChromeState(
                            isActive = true,
                            url = url,
                            title = title,
                            status = "Browser is running.",
                        ),
                    )
                }
            }

            fun openUrl(url: String) {
                search = null
                homeRequested = false
                components.navigate(url)
            }

            fun runSearch(query: String, tab: BrowserSearchTab) {
                val prefs = AetherBrowserRuntime.currentPreferences()
                search = (search ?: BrowserSearchState()).copy(
                    query = query,
                    tab = tab,
                    loadingLinks = tab == BrowserSearchTab.Links,
                    loadingImages = tab == BrowserSearchTab.Images,
                )
                when (tab) {
                    BrowserSearchTab.Links -> scope.launch {
                        val hits = BrowserNativeSearch.links(components, query, prefs)
                        search = search?.takeIf { it.query == query }
                            ?.copy(links = hits, loadingLinks = false)
                    }
                    BrowserSearchTab.Images -> scope.launch {
                        val hits = BrowserNativeSearch.images(components, query, prefs)
                        search = search?.takeIf { it.query == query }
                            ?.copy(images = hits, loadingImages = false)
                    }
                    BrowserSearchTab.Answer -> {
                        val current = search ?: return
                        if (current.answer.query == query && current.answer.hasContent) return
                        search = current.copy(
                            answer = BrowserAnswerState(query = query, running = true),
                        )
                        scope.launch {
                            // The answer is grounded in the link results, so make sure they exist
                            // before asking: an answer with nothing under it is just a guess.
                            val hits = current.links.ifEmpty {
                                BrowserNativeSearch.links(components, query, prefs).also { fetched ->
                                    search = search?.takeIf { it.query == query }
                                        ?.copy(links = fetched, loadingLinks = false)
                                }
                            }
                            BrowserAnswer.start(
                                executions = executions,
                                sessionId = answerSessionId,
                                settings = settings,
                                providerConfigs = providerConfigs,
                                query = query,
                                hits = hits,
                            )
                        }
                    }
                }
            }

            fun go(raw: String) {
                val text = raw.trim()
                editing = false
                focusManager.clearFocus()
                if (text.isBlank()) return
                homeRequested = false
                // A search term stays in our own results surface; only an address hands the tab
                // over to the engine. Bing is the index here, not the destination.
                if (looksLikeUrl(text)) {
                    openUrl(normalizeBrowserAddress(text, AetherBrowserRuntime.currentPreferences()))
                } else {
                    runSearch(text, BrowserSearchTab.Links)
                }
            }

            fun goHome() {
                editing = false
                focusManager.clearFocus()
                search = null
                homeRequested = true
            }

            fun startVoice() {
                val current: AppSettings = settings ?: return
                scope.launch {
                    val started = asrSession.start(
                        settings = current,
                        onRms = {},
                        onPartial = { partial ->
                            editing = true
                            draft = TextFieldValue(partial, TextRange(partial.length))
                        },
                        onError = { listening = false },
                    )
                    listening = started
                }
            }

            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) startVoice() }

            fun toggleVoice() {
                if (listening) {
                    scope.launch {
                        val text = asrSession.stop()
                        listening = false
                        if (text.isNotBlank()) go(text)
                    }
                    return
                }
                val granted = ContextCompat.checkSelfPermission(
                    this@BrowserActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) startVoice() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            BackHandler(enabled = search != null) {
                search = null
                homeRequested = true
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherBackground)
                    .statusBarsPadding(),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    BrowserContent(components) { host -> engineHost = host }
                    val session = search
                    if (session != null) {
                        BrowserSearchPage(
                            state = session,
                            onTab = { tab -> runSearch(session.query, tab) },
                            onOpen = ::openUrl,
                        )
                    } else if (atHome) {
                        BrowserHomePage(onGo = ::go)
                    }
                }
                if (chrome.loading && !atHome) {
                    LinearProgressIndicator(
                        progress = { chrome.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = AetherPrimary,
                        trackColor = AetherBackground,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChromeIcon(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        description = "后退",
                        enabled = chrome.canGoBack && !atHome,
                        onClick = { components.sessionUseCases.goBack() },
                    )
                    ChromeIcon(
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        description = "前进",
                        enabled = chrome.canGoForward,
                        onClick = { components.sessionUseCases.goForward() },
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(AetherSurfaceHigh)
                            .border(1.dp, AetherOutlineSoft, RoundedCornerShape(21.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            ChromeIcon(
                                icon = Icons.Rounded.MoreVert,
                                description = "菜单",
                                size = 34.dp,
                                onClick = { menuOpen = true },
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("主页") },
                                    leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        goHome()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("新建标签页") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        components.tabsUseCases.addTab(
                                            url = AetherBrowserHome,
                                            selectTab = true,
                                        )
                                        goHome()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("复制链接") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        clipboard.setText(AnnotatedString(chrome.url))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("分享") },
                                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        if (chrome.url.isNotBlank()) {
                                            startActivity(
                                                Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_TEXT, chrome.url)
                                                    },
                                                    "分享",
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (editing) {
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                                    cursorBrush = SolidColor(AetherPrimary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { go(draft.text) }),
                                )
                                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            draft = TextFieldValue(
                                                text = chrome.url,
                                                selection = TextRange(0, chrome.url.length),
                                            )
                                            editing = true
                                        },
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = AetherOnSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = search?.query
                                            ?: addressLabel(chrome.url).ifBlank { "搜索或输入网址" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (atHome) AetherOnSurfaceVariant else AetherOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        ChromeIcon(
                            icon = if (editing) Icons.Rounded.Close else Icons.Rounded.Refresh,
                            description = if (editing) "取消" else "刷新",
                            size = 34.dp,
                            onClick = {
                                if (editing) {
                                    editing = false
                                    focusManager.clearFocus()
                                } else {
                                    components.sessionUseCases.reload()
                                }
                            },
                        )
                    }
                    ChromeIcon(
                        icon = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        description = if (listening) "停止语音输入" else "语音输入",
                        tint = if (listening) AetherPrimary else AetherOnSurface,
                        onClick = ::toggleVoice,
                    )
                    TabCountButton(count = chrome.tabCount, onClick = { tabsOpen = true })
                }
            }

            if (tabsOpen) {
                ModalBottomSheet(
                    onDismissRequest = { tabsOpen = false },
                    sheetState = rememberModalBottomSheetState(),
                    containerColor = AetherBackground,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        tabRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        components.selectTab(row.id)
                                        tabsOpen = false
                                    }
                                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = row.title.ifBlank { addressLabel(row.url).ifBlank { "新标签页" } },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (row.selected) AetherPrimary else AetherOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = row.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AetherOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                ChromeIcon(
                                    icon = Icons.Rounded.Close,
                                    description = "关闭标签页",
                                    onClick = { components.tabsUseCases.removeTab(row.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BrowserEngineSurface.activityForeground.value = true
        AetherBrowserRuntime.peek()?.let { components ->
            // Coming back from the chat means the browser card may have taken the session onto its
            // own surface. Claim it back before anything else — nothing here recomposes on its own,
            // so a stale surface would just stay blank.
            engineHost?.let { host ->
                AetherBrowserRuntime.attachEngineView(host.engineView)
                components.reRenderSelected(host.engineView)
                host.renderedTabId = components.selectedTabId()
            }
            // The tab you are looking at has to be the active Gecko session, or its timers and rAF
            // callbacks stay throttled and the page repaints in slow motion.
            val id = components.selectedTabId()
            if (id.isNotBlank()) components.activateTab(id)
        }
    }

    override fun onPause() {
        BrowserEngineSurface.activityForeground.value = false
        super.onPause()
        val app = application as AetherApplication
        val components = AetherBrowserRuntime.peek() ?: return
        // Persisting tabs is disk work; doing it with runBlocking on the main thread stalled every
        // exit from the browser.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val current = app.runtime.settingsRepository.settings.first()
                app.runtime.settingsRepository.updateSettings(
                    current.copy(browserPreferences = components.persistTabs(current.browserPreferences)),
                )
            }
        }
    }

    override fun onDestroy() {
        BrowserEngineSurface.activityForeground.value = false
        super.onDestroy()
    }

    companion object {
        const val ExtraUrl = "url"

        fun launch(context: Context, url: String? = null) {
            val intent = Intent(context, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (!url.isNullOrBlank()) intent.putExtra(ExtraUrl, url)
            context.startActivity(intent)
        }
    }
}

/**
 * Our own start page. A search engine's homepage is someone else's product surface; this one is a
 * single field in the middle, shaped like the composer the rest of the app types into.
 */
@Composable
private fun BrowserHomePage(onGo: (String) -> Unit) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    Box(
        modifier = Modifier.fillMaxSize().background(AetherBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ditto",
                style = MaterialTheme.typography.headlineMedium,
                color = AetherOnSurface,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(27.dp))
                    .background(AetherSurfaceHigh)
                    .border(1.dp, AetherOutlineSoft, RoundedCornerShape(27.dp))
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.text.isEmpty()) {
                        Text(
                            text = "搜索或输入网址",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AetherOnSurface),
                        cursorBrush = SolidColor(AetherPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo(query.text) }),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AetherPrimary)
                        .clickable { onGo(query.text) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = "打开",
                        tint = AetherOnPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserContent(
    components: AetherBrowserRuntime.Components,
    onHostReady: (ActivityEngineViewHost) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val engineView = components.createEngineView(ctx)
            AetherBrowserRuntime.attachEngineView(engineView)
            val host = ActivityEngineViewHost(engineView)
            onHostReady(host)
            engineView.asView().apply { tag = host }
        },
        update = { view ->
            val host = view.tag as? ActivityEngineViewHost ?: return@AndroidView
            val tab = components.store.state.selectedTab
            val session = tab?.engineState?.engineSession ?: return@AndroidView
            // "Same tab as last time" is not enough to skip the render. The chat's browser card
            // takes this very EngineView out of here (takeEngineViewForCompose) and parks the
            // session on another surface, and a GeckoSession only draws into whichever view
            // rendered it last — so the test is whether this view is still the one the runtime
            // considers attached, not whether the tab changed.
            val owned = AetherBrowserRuntime.attachedEngineView() === host.engineView
            if (owned && host.renderedTabId == tab.id) return@AndroidView
            AetherBrowserRuntime.attachEngineView(host.engineView)
            host.engineView.render(session)
            host.renderedTabId = tab.id
            components.activateTab(tab.id)
        },
        onRelease = { view ->
            val host = view.tag as? ActivityEngineViewHost ?: return@AndroidView
            AetherBrowserRuntime.detachEngineView(host.engineView)
        },
    )
}

@Composable
private fun ChromeIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    tint: androidx.compose.ui.graphics.Color = AetherOnSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else AetherOnSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The tab count sits inside its own square outline, the way every phone browser draws it. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.7.dp, AetherOnSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99" else count.coerceAtLeast(1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurface,
            )
        }
    }
}

/**
 * What the pill shows when it is not being edited: the words you searched for if this is a search
 * result page, otherwise the host. A full URL in a 42dp pill is unreadable anyway.
 */
internal fun addressLabel(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank() || isSeedBrowserUrl(trimmed)) return ""
    val parsed = runCatching { android.net.Uri.parse(trimmed) }.getOrNull() ?: return trimmed
    for (key in listOf("q", "query", "wd", "text", "kw")) {
        val value = runCatching { parsed.getQueryParameter(key) }.getOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return parsed.host.orEmpty().removePrefix("www.").ifBlank { trimmed }
}
