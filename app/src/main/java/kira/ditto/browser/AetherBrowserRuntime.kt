package kira.ditto.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kira.ditto.data.BrowserPreferences
import kira.ditto.data.DefaultBrowserHomepage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import mozilla.components.lib.state.ext.flow
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.prompt.PromptRequest
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

object BrowserEngineSurface {
    val activityForeground = MutableStateFlow(false)
    private val hostRef = AtomicReference<WeakReference<Activity>?>(null)

    fun bindHost(activity: Activity) {
        hostRef.set(WeakReference(activity))
    }

    fun unbindHost(activity: Activity) {
        if (hostRef.get()?.get() === activity) hostRef.set(null)
    }

    fun hostActivity(): Activity? = hostRef.get()?.get()
}

internal const val MaxNeckoBodyBytes = 1_048_576
private val BrowserHttpLimits = BrowserFetchLimits()
private val BrowserParseLimits = Semaphore(2)
private val BrowserBodyTimeouts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "browser-body-deadline").apply { isDaemon = true }
}

/**
 * How many Gecko sessions may stay active at once.
 *
 * An inactive session throttles timers and suspends requestAnimationFrame, so a client-rendered
 * page in it never mounts. Activating exactly one tab meant that with two agents researching in
 * parallel, each call switched activation and left the other agent's page frozen mid-load — it
 * then timed out and was retried, which is most of what made concurrent research slow. Keeping a
 * small working set active costs some CPU and removes that whole failure mode.
 */
private const val MaxActiveGeckoTabs = 4

internal data class NeckoDocument(
    val url: String,
    val html: String,
    val truncated: Boolean = false,
)

/**
 * A fetched document with its text already extracted.
 *
 * Parsing used to happen in the caller's loop after every fetch had finished, so N pages cost
 * N parses in series behind the slowest download. Doing it inside the fetch coroutine overlaps
 * each parse with the other requests still in flight.
 */
internal data class ParsedNeckoDocument(
    val requestedUrl: String,
    val url: String,
    val html: String,
    val title: String,
    val text: String,
    val truncated: Boolean = false,
    val fetchMs: Long = 0,
    val parseMs: Long = 0,
)

/**
 * Which charset is this body actually in?
 *
 * Decoding everything as UTF-8 turns any GB18030 or Big5 page into replacement characters — and a
 * good share of the Chinese sites the agent reads still serve GBK. Trust the Content-Type header
 * first, then the document's own <meta charset>, and fall back to UTF-8.
 */
internal fun charsetForHtmlBody(contentType: String, head: ByteArray): java.nio.charset.Charset {
    fun named(name: String): java.nio.charset.Charset? {
        val trimmed = name.trim().trim('"', '\'').ifBlank { return null }
        return runCatching { java.nio.charset.Charset.forName(trimmed) }.getOrNull()
    }
    Regex("(?i)charset\\s*=\\s*([A-Za-z0-9_:.+-]+)").find(contentType)
        ?.groupValues?.getOrNull(1)
        ?.let { named(it) }
        ?.let { return it }
    val sniff = String(head, 0, minOf(head.size, 2_048), Charsets.ISO_8859_1)
    Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_:.+-]+)").find(sniff)
        ?.groupValues?.getOrNull(1)
        ?.let { named(it) }
        ?.let { return it }
    return Charsets.UTF_8
}

/**
 * Process-wide Gecko host. Construction is the expensive part (libxul).
 * Never call [get] from Application.onCreate or the first composition path.
 */
object AetherBrowserRuntime {
    data class ChromeState(
        val isActive: Boolean = false,
        val url: String = "",
        val title: String = "",
        val status: String = "Browser is stopped.",
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile
    private var components: Components? = null
    private val prefsRef = AtomicReference(BrowserPreferences())
    private val agentSessionRef = AtomicBoolean(false)
    @Volatile
    private var appContext: Context? = null
    @Volatile
    var loginVault: BrowserLoginVault? = null
    @Volatile
    var historyStore: BrowserHistoryStore? = null

    private val chromeState = AtomicReference(ChromeState())
    private val chromeStateMutable = MutableStateFlow(ChromeState())
    val chromeStateFlow: StateFlow<ChromeState> = chromeStateMutable.asStateFlow()
    val networkLog = ArrayDeque<JSONObject>()
    private val networkLock = Any()
    private val engineViewRef = AtomicReference<WeakReference<EngineView>?>(null)

    fun attachEngineView(view: EngineView) {
        engineViewRef.set(WeakReference(view))
    }

    fun detachEngineView(view: EngineView) {
        if (attachedEngineView() === view) {
            engineViewRef.set(null)
        }
        runCatching { view.release() }
    }

    fun attachedEngineView(): EngineView? = engineViewRef.get()?.get()

    fun selectTab(id: String): Boolean = components?.selectTab(id) == true

    fun isExtensionReady(tabId: String): Boolean = components?.isExtensionReady(tabId) == true

    fun hasAnyChannel(tabId: String): Boolean = components?.hasAnyChannel(tabId) == true

    fun preparePageChannel(context: Context, tabId: String) {
        getBlocking(context).ensureSessionRendered(tabId)
    }

    fun activateTab(context: Context, tabId: String): Boolean =
        getBlocking(context).activateTab(tabId)

    fun takeEngineViewForCompose(context: Context): EngineView {
        val existing = attachedEngineView()
        if (existing != null) {
            val androidView = existing.asView()
            (androidView.parent as? ViewGroup)?.removeView(androidView)
            androidView.alpha = 1f
            androidView.translationX = 0f
            androidView.translationY = 0f
            return existing
        }
        return get(context).createEngineView(context)
    }

    fun parkEngineView(view: EngineView) {
        val activity = BrowserEngineSurface.hostActivity()
        if (activity == null) {
            detachEngineView(view)
            return
        }
        val attach: () -> Unit = {
            placeStandbyEngineView(activity, view)
            components?.reRenderSelected(view)
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach()
        } else {
            mainHandler.post { attach() }
        }
    }

    private fun placeStandbyEngineView(activity: Activity, view: EngineView) {
        val androidView = view.asView()
        (androidView.parent as? ViewGroup)?.removeView(androidView)
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(360)
        val height = (metrics.heightPixels / 2).coerceAtLeast(640)
        androidView.layoutParams = ViewGroup.LayoutParams(width, height)
        androidView.visibility = View.VISIBLE
        androidView.alpha = 1f
        androidView.translationX = 0f
        androidView.translationY = 0f
        androidView.elevation = 0f
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (androidView.parent == null) {
            if (root.childCount > 0) root.addView(androidView, 0) else root.addView(androidView)
        }
        attachEngineView(view)
    }

    fun warmAsync(context: Context) {
        if (components != null) return
        val app = context.applicationContext
        mainHandler.post { runCatching { get(app) } }
    }

    fun peekSelectedTabId(): String = components?.selectedTabId().orEmpty()

    internal fun ensureTopicTab(context: Context, target: BrowserTopicTarget, sessionId: String = ""): String {
        val host = getBlocking(context)
        return host.ensureTopicTab(target, sessionId)
    }

    internal fun navigateInTopic(context: Context, url: String, topicId: String, sessionId: String = "") {
        val trimmed = url.trim()
        if (!looksLikeHttpUrl(trimmed)) return
        val host = getBlocking(context)
        val resolvedTopic = topicId.trim().ifBlank { BrowserTopicGraph.lastTopicId(sessionId) }
        val target = BrowserTopicTarget(
            topicId = resolvedTopic,
            tabId = BrowserTopicGraph.primaryTabId(resolvedTopic, sessionId),
            asImageTab = false,
        )
        val tabId = host.ensureTopicTab(target, sessionId)
        if (tabId.isNotBlank()) host.selectTab(tabId)
        host.navigate(trimmed, tabId)
    }

    fun preopenTopicWorkspaces(sessionId: String) {
        val host = components ?: return
        BrowserTopicGraph.snapshot(sessionId).forEach { tab ->
            if (tab.primaryTabId.isBlank()) {
                host.ensureTopicTab(
                    BrowserTopicTarget(topicId = tab.topicId, tabId = "", asImageTab = false),
                    sessionId,
                )
            }
        }
    }

    fun isWarm(): Boolean = components != null

    fun peek(): Components? = components

    fun destroyTopicTabs(sessionId: String = "") {
        val ids = if (sessionId.isBlank()) {
            BrowserTopicGraph.takeGeckoTabIdsAndClear()
        } else {
            BrowserTopicGraph.takeGeckoTabIdsAndClear(sessionId)
        }
        if (ids.isEmpty()) return
        val closer = {
            val host = components
            if (host != null) {
                ids.forEach { id -> runCatching { host.closeTab(id) } }
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closer()
        } else {
            mainHandler.post(closer)
        }
    }

    /**
     * Hand the next turn a clean browser: close the previous turn's tab groups and stop their
     * pages from running, keeping only the page this turn was asked to stay on.
     */
    fun retireTurnTabs(sessionId: String, keepUrl: String = "") {
        val host = components ?: return
        val keepId = if (looksLikeHttpUrl(keepUrl)) host.findTabIdByUrl(keepUrl) else ""
        val ids = BrowserTopicGraph.retireTurn(sessionId, keepId)
        if (ids.isEmpty()) return
        host.deactivateTabs(ids)
        val closer = {
            ids.forEach { id -> runCatching { host.closeTab(id) } }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) closer() else mainHandler.post(closer)
    }

    fun currentChrome(): ChromeState = chromeState.get()

    fun updatePreferences(prefs: BrowserPreferences, agentSession: Boolean? = null) {
        prefsRef.set(prefs)
        if (agentSession != null) agentSessionRef.set(agentSession)
        components?.applyPreferences(prefs, agentSessionRef.get())
    }

    fun currentPreferences(): BrowserPreferences = prefsRef.get()

    internal fun publishChrome(state: ChromeState) {
        chromeState.set(state)
        chromeStateMutable.value = state
    }

    fun get(context: Context): Components {
        components?.let { return it }
        synchronized(lock) {
            components?.let { return it }
            val app = context.applicationContext
            appContext = app
            if (loginVault == null) loginVault = BrowserLoginVault(app)
            if (historyStore == null) historyStore = BrowserHistoryStore(app)
            val created = Components(app, prefsRef.get(), loginVault!!)
            components = created
            publishChrome(ChromeState(isActive = true, status = "Browser is running."))
            return created
        }
    }

    fun getBlocking(context: Context): Components {
        components?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return get(context)
        }
        var created: Components? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                created = get(context)
            } catch (throwable: Throwable) {
                error = throwable
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            error("Timed out starting GeckoView.")
        }
        error?.let { throw it }
        return created ?: error("GeckoView failed to start.")
    }

    fun rememberNetwork(
        url: String,
        method: String,
        isNav: Boolean,
        type: String = "",
        status: Int = 0,
    ) {
        val kind = classifyResource(url, type)
        val item = JSONObject()
            .put("url", url.take(300))
            .put("method", method.ifBlank { "GET" })
            .put("navigation", isNav)
            .put("type", kind)
            .put("status", status)
            .put("at", System.currentTimeMillis())
        synchronized(networkLock) {
            networkLog.addLast(item)
            while (networkLog.size > 120) networkLog.removeFirst()
        }
        if (isNav) historyStore?.record(url)
    }

    fun recentNetwork(type: String = "", query: String = ""): List<JSONObject> = synchronized(networkLock) {
        val typeFilter = type.trim().lowercase()
        val needle = query.trim().lowercase()
        networkLog.filter { item ->
            val matchesType = typeFilter.isBlank() ||
                item.optString("type").equals(typeFilter, ignoreCase = true) ||
                (typeFilter == "xhr" && item.optString("type") == "xmlhttprequest")
            val matchesQuery = needle.isBlank() || item.optString("url").contains(needle, ignoreCase = true)
            matchesType && matchesQuery
        }
    }

    fun recordVisit(url: String, title: String = "") {
        historyStore?.record(url, title)
    }

    fun updateVisitTitle(url: String, title: String) {
        historyStore?.updateTitle(url, title)
    }

    class Components(
        private val appContext: Context,
        initialPrefs: BrowserPreferences,
        val vault: BrowserLoginVault,
    ) {
        private val interceptor = object : RequestInterceptor {
            override fun onLoadRequest(
                engineSession: EngineSession,
                uri: String,
                lastUri: String?,
                hasUserGesture: Boolean,
                isSameDomain: Boolean,
                isRedirect: Boolean,
                isDirectNavigation: Boolean,
                isSubframeRequest: Boolean,
            ): RequestInterceptor.InterceptionResponse? {
                if (!isSubframeRequest) {
                    rememberNetwork(
                        uri,
                        "GET",
                        isDirectNavigation || hasUserGesture,
                        type = if (isDirectNavigation) "document" else "subdocument",
                    )
                    val rewritten = internationalizeBingUrl(uri)
                    if (rewritten.isNotBlank() && rewritten != uri) {
                        return RequestInterceptor.InterceptionResponse.Url(rewritten)
                    }
                }
                return null
            }
        }

        private val runtime: GeckoRuntime = GeckoRuntime.create(
            appContext,
            GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .consoleOutput(false)
                .debugLogging(false)
                .javaScriptEnabled(initialPrefs.javascriptEnabled)
                .loginAutofillEnabled(initialPrefs.autofillPasswords)
                .contentBlocking(
                    ContentBlocking.Settings.Builder()
                        .cookieBehavior(cookieBehavior(initialPrefs))
                        .build(),
                )
                .build(),
        )

        private val webExecutor: GeckoWebExecutor by lazy {
            GeckoWebExecutor(runtime).also {
                // BrowserFreshness deliberately owns no HTTP client; it asks whoever can reach the
                // network through the same session the page was read on. That is this executor.
                BrowserPageValidators.probe = { url, cached -> revalidate(url, cached) }
            }
        }

        val engine: Engine = GeckoEngine(
            appContext,
            DefaultSettings(
                javascriptEnabled = initialPrefs.javascriptEnabled,
                trackingProtectionPolicy = trackingPolicy(initialPrefs),
                requestInterceptor = interceptor,
                javaScriptCanOpenWindowsAutomatically = !initialPrefs.popupsBlocked,
                // GeckoView 153 has no trusted CDP/BiDi Input for Android. Leave this off:
                // remote debugging would expose the engine without giving the model a
                // reliable click path. Drive the page with the WebMCP extension
                // (synthetic DOM events) and page_screenshot as a last-resort visual fallback.
                remoteDebuggingEnabled = false,
            ),
            runtime,
        )

        val store: BrowserStore = BrowserStore(
            middleware = EngineMiddleware.create(engine),
        )

        val sessionUseCases: SessionUseCases = SessionUseCases(store)
        val tabsUseCases: TabsUseCases = TabsUseCases(store)

        private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        private val mutexByTab = ConcurrentHashMap<String, Mutex>()
        private val channels = CopyOnWriteArrayList<ContentChannel>()
        private val contentHandlerSessions = ConcurrentHashMap<EngineSession, Boolean>()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var activeTabId: String = ""

        /** Recently activated tabs, oldest first. Main thread only. */
        private val activeTabIds = LinkedHashSet<String>()

        @Volatile
        private var activationWarned: Boolean = false

        @Volatile
        private var activationConfirmed: Boolean = false

        @Volatile
        private var geckoSessionGetter: java.lang.reflect.Method? = null

        @Volatile
        private var renderedSession: EngineSession? = null
        @Volatile
        private var renderedView: WeakReference<EngineView>? = null
        @Volatile
        private var extensionPort: Port? = null

        /**
         * One live content script. [docId] is minted once per script evaluation, so it survives
         * bfcache restores and SPA route changes but dies with the document; [href] tracks which
         * page that script is currently looking at. Readiness needs both: a port object on its own
         * outlives the document it was opened for.
         */
        private class ContentChannel(val port: Port) {
            @Volatile
            var tabId: String = ""

            @Volatile
            var docId: String = ""

            @Volatile
            var href: String = ""

            @Volatile
            var readyState: String = ""

            @Volatile
            var helloAt: Long = 0L

            val ready: Boolean get() = docId.isNotBlank()
        }
        @Volatile
        private var installedExtension: WebExtension? = null

        private val messageHandler = object : MessageHandler {
            override fun onPortConnected(port: Port) {
                val session = port.engineSession
                if (session == null) {
                    extensionPort = port
                    android.util.Log.i("AetherBrowserRuntime", "WebMCP background port connected")
                    return
                }
                val channel = ContentChannel(port)
                channel.tabId = tabIdForSession(session)
                channels.add(channel)
                android.util.Log.i(
                    "AetherBrowserRuntime",
                    "WebMCP content port opened tab=" + channel.tabId.ifBlank { "?" } + ", awaiting hello",
                )
            }

            override fun onPortDisconnected(port: Port) {
                if (extensionPort === port) {
                    extensionPort = null
                    android.util.Log.i("AetherBrowserRuntime", "WebMCP background port disconnected")
                }
                dropChannels(channels.filter { it.port === port })
                android.util.Log.i(
                    "AetherBrowserRuntime",
                    "WebMCP content port closed, live channels: " + channels.size,
                )
            }

            override fun onPortMessage(message: Any, port: Port) {
                val json = message as? JSONObject
                    ?: runCatching { JSONObject(message.toString()) }.getOrNull()
                    ?: return
                when (json.optString("kind")) {
                    "hello" -> {
                        noteHello(port, json)
                        return
                    }
                    "bye" -> {
                        noteBye(port, json)
                        return
                    }
                }
                ingestExtensionMessage(json)
            }

            override fun onMessage(message: Any, source: EngineSession?): Any? {
                val json = message as? JSONObject
                    ?: runCatching { JSONObject(message.toString()) }.getOrNull()
                if (json != null) ingestExtensionMessage(json)
                return Unit
            }
        }

        init {
            bindLoginStorage()
            installWebMcpExtension()
            val restore = if (initialPrefs.restoreTabs) {
                initialPrefs.lastTabUrls.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            if (restore.isEmpty()) {
                tabsUseCases.addTab(
                    url = initialPrefs.homepage.ifBlank { DefaultBrowserHomepage },
                    selectTab = true,
                )
            } else {
                restore.forEachIndexed { index, url ->
                    tabsUseCases.addTab(url = url, selectTab = index == 0)
                }
            }
            applyPreferences(initialPrefs)
        }

        fun applyPreferences(prefs: BrowserPreferences, agentSession: Boolean = false) {
            prefsRef.set(prefs)
            runCatching {
                engine.settings.javascriptEnabled = prefs.javascriptEnabled
                engine.settings.trackingProtectionPolicy = trackingPolicy(prefs, agentSession)
                engine.settings.javaScriptCanOpenWindowsAutomatically = !prefs.popupsBlocked
            }
            val selected = store.state.selectedTabId
            if (selected != null) {
                runCatching {
                    sessionUseCases.requestDesktopSite.invoke(prefs.desktopMode, selected)
                }
            }
        }

        fun createEngineView(context: Context): EngineView {
            val view = engine.createView(context)
            attachEngineView(view)
            return view
        }

        fun reRenderSelected(view: EngineView) {
            val session = store.state.selectedTab?.engineState?.engineSession ?: return
            runCatching { view.render(session) }
            renderedSession = session
            renderedView = WeakReference(view)
        }

        private fun sessionFor(tabId: String): EngineSession? =
            store.state.tabs.firstOrNull { it.id == tabId }?.engineState?.engineSession

        /**
         * `GeckoEngineSession.geckoSession` is `internal` in mozilla-components, so it is reached
         * by name: the public backing field first, then the mangled accessor.
         */
        private fun geckoSessionOf(session: EngineSession): GeckoSession? {
            runCatching {
                val field = session.javaClass.getField("geckoSession")
                (field.get(session) as? GeckoSession)?.let { return it }
            }
            geckoSessionGetter?.let { cached ->
                runCatching { cached.invoke(session) as? GeckoSession }.getOrNull()?.let { return it }
            }
            val method = session.javaClass.methods.firstOrNull { candidate ->
                candidate.parameterTypes.isEmpty() &&
                    candidate.name.startsWith("getGeckoSession") &&
                    GeckoSession::class.java.isAssignableFrom(candidate.returnType)
            } ?: return null
            geckoSessionGetter = method
            return runCatching { method.invoke(session) as? GeckoSession }.getOrNull()
        }

        /**
         * Make this tab the foreground one for Gecko. An inactive session has its timers throttled
         * and its rAF callbacks suspended, so a client-rendered page in a non-activated tab never
         * mounts and snapshots it as an empty shell. Exactly one tab stays active, as in a browser.
         */
        fun activateTab(tabId: String): Boolean {
            val id = tabId.trim()
            if (id.isBlank()) return false
            val done = AtomicBoolean(false)
            val work = work@{
                val target = sessionFor(id) ?: return@work
                val gecko = geckoSessionOf(target)
                if (gecko == null) {
                    if (!activationWarned) {
                        activationWarned = true
                        android.util.Log.e(
                            "AetherBrowserRuntime",
                            "Cannot reach GeckoSession: tabs stay inactive and JS-rendered pages will not mount",
                        )
                    }
                    return@work
                }
                runCatching { gecko.setActive(true) }.onFailure { return@work }
                noteActivated(id)
                done.set(true)
                if (!activationConfirmed) {
                    activationConfirmed = true
                    android.util.Log.i("AetherBrowserRuntime", "Gecko session activation is live (tab=$id)")
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                work()
                return done.get()
            }
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    work()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            return done.get()
        }

        /**
         * Record a tab as active and retire the oldest once the working set is full. Main thread.
         */
        private fun noteActivated(tabId: String) {
            val id = tabId.trim()
            if (id.isBlank()) return
            activeTabIds.remove(id)
            activeTabIds.add(id)
            activeTabId = id
            while (activeTabIds.size > MaxActiveGeckoTabs) {
                val oldest = activeTabIds.first()
                activeTabIds.remove(oldest)
                if (oldest == id) continue
                sessionFor(oldest)?.let { stale ->
                    runCatching { geckoSessionOf(stale)?.setActive(false) }
                }
            }
        }

        /**
         * Put these tabs back to sleep. Called when a turn ends so the finished turn's pages stop
         * running timers behind the next one.
         */
        fun deactivateTabs(tabIds: Collection<String>) {
            val ids = tabIds.map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) return
            val work = {
                ids.forEach { id ->
                    activeTabIds.remove(id)
                    if (activeTabId == id) activeTabId = ""
                    sessionFor(id)?.let { stale ->
                        runCatching { geckoSessionOf(stale)?.setActive(false) }
                    }
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) work() else mainHandler.post { work() }
        }

        /**
         * Event-driven load wait: the store emits on every content change, so this returns as soon
         * as the tab settles instead of burning a fixed polling budget.
         */
        suspend fun awaitLoadSettled(tabId: String, targetUrl: String, timeoutMs: Long): Boolean {
            val id = tabId.trim()
            if (id.isBlank()) return false
            val want = normalizeBrowsedUrl(targetUrl)
            val startUrl = normalizeBrowsedUrl(tabUrl(id))
            fun arrived(): Boolean {
                val tab = store.state.tabs.firstOrNull { it.id == id } ?: return false
                if (tab.content.loading) return false
                val url = tab.content.url
                if (url.isBlank() || isSeedBrowserUrl(url)) return false
                if (!looksLikeHttpUrl(url)) return isBrowserErrorUrl(url)
                if (want.isBlank()) return true
                // "Not loading" is not arrival: right after a navigation is requested the tab is
                // still showing the previous document and has not started loading yet. Wait until
                // the tab is actually on the requested URL, or has left the one it started on
                // (which covers redirects).
                val now = normalizeBrowsedUrl(url)
                return now == want || now != startUrl
            }
            if (arrived()) return true
            return withTimeoutOrNull(timeoutMs) {
                store.flow().first { arrived() }
                true
            } ?: arrived()
        }

        fun ensureSessionRendered(tabId: String) {
            val run = {
                val session = store.state.tabs
                    .firstOrNull { it.id == tabId }
                    ?.engineState
                    ?.engineSession
                    ?: store.state.selectedTab?.engineState?.engineSession
                var view = attachedEngineView()
                if (view == null) {
                    val activity = BrowserEngineSurface.hostActivity()
                    if (activity != null) {
                        view = createEngineView(activity)
                        val androidView = view.asView()
                        if (androidView.parent == null) {
                            placeStandbyEngineView(activity, view)
                        }
                    }
                }
                if (view != null && session != null) {
                    val already = renderedSession === session && renderedView?.get() === view
                    if (!already) {
                        runCatching { view.render(session) }
                        renderedSession = session
                        renderedView = WeakReference(view)
                    }
                }
                ensureContentHandler(tabId)
                if (tabId.isNotBlank() && session != null) {
                    runCatching { geckoSessionOf(session)?.setActive(true) }
                    noteActivated(tabId)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                run()
                return
            }
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    run()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
        }

        fun nativePrompts(): List<PromptRequest> =
            store.state.selectedTab?.content?.promptRequests.orEmpty()

        fun consumePrompt(prompt: PromptRequest) {
            val tabId = store.state.selectedTabId ?: return
            store.dispatch(ContentAction.ConsumePromptRequestAction(tabId, prompt))
        }

        suspend fun captureVisibleBitmap(timeoutMs: Long = 4_000): Bitmap? {
            val view = attachedEngineView() ?: return null
            val deferred = CompletableDeferred<Bitmap?>()
            mainHandler.post {
                try {
                    view.captureThumbnail { bitmap ->
                        if (!deferred.isCompleted) deferred.complete(bitmap)
                    }
                } catch (throwable: Throwable) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }
            return runCatching { withTimeout(timeoutMs) { deferred.await() } }.getOrNull()
        }

        fun selectedUrl(): String = store.state.selectedTab?.content?.url.orEmpty()

        fun selectedTitle(): String = store.state.selectedTab?.content?.title.orEmpty()

        fun selectedLoading(): Boolean = store.state.selectedTab?.content?.loading == true

        fun selectedTabId(): String = store.state.selectedTabId.orEmpty()

        fun tabUrl(tabId: String): String =
            store.state.tabs.firstOrNull { it.id == tabId }?.content?.url.orEmpty()

        fun tabTitle(tabId: String): String =
            store.state.tabs.firstOrNull { it.id == tabId }?.content?.title.orEmpty()

        fun tabLoading(tabId: String): Boolean =
            store.state.tabs.firstOrNull { it.id == tabId }?.content?.loading == true

        fun hasTab(tabId: String): Boolean =
            tabId.isNotBlank() && store.state.tabs.any { it.id == tabId }

        fun firstHttpTabId(preferred: String = ""): String {
            val prefer = preferred.trim()
            if (prefer.isNotBlank() && hasTab(prefer) && isLiveHttpTab(prefer)) return prefer
            return store.state.tabs.firstOrNull { tab -> isLiveHttpTab(tab.id) }?.id.orEmpty()
        }

        fun isLiveHttpTab(tabId: String): Boolean {
            if (!hasTab(tabId)) return false
            val url = tabUrl(tabId)
            return looksLikeHttpUrl(url) && !isSeedBrowserUrl(url)
        }

        fun findTabIdByUrl(url: String): String {
            val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
            if (key.isBlank()) return ""
            return store.state.tabs.firstOrNull { tab ->
                val candidate = normalizeBrowsedUrl(tab.content.url).ifBlank { tab.content.url.trim() }
                candidate == key
            }?.id.orEmpty()
        }

        fun tabList(): JSONArray {
            val array = JSONArray()
            store.state.tabs.forEach { tab ->
                array.put(
                    JSONObject()
                        .put("id", tab.id)
                        .put("url", tab.content.url)
                        .put("title", tab.content.title)
                        .put("selected", tab.id == store.state.selectedTabId),
                )
            }
            return array
        }

        fun navigate(url: String, tabId: String = "") {
            // DNS and the TLS handshake are the first few hundred milliseconds of any load, and
            // Necko shares its connection pool with the content process. Starting them before the
            // navigation is dispatched takes that off the critical path.
            runCatching { speculativeConnect(url) }
            val target = tabId.trim()
            if (target.isNotBlank() && hasTab(target)) {
                sessionUseCases.loadUrl.invoke(url, target)
            } else if (target.isBlank()) {
                val selected = store.state.selectedTabId
                if (selected == null) {
                    tabsUseCases.addTab(url = url, selectTab = true)
                } else {
                    sessionUseCases.loadUrl(url)
                }
            } else {
                val existing = findTabIdByUrl(url)
                if (existing.isNotBlank()) {
                    selectTab(existing)
                    sessionUseCases.loadUrl.invoke(url, existing)
                } else {
                    tabsUseCases.addTab(url = url, selectTab = false)
                }
            }
            recordVisit(url)
            publishChrome(
                ChromeState(isActive = true, url = url, status = "Loading $url"),
            )
        }

        fun openTab(url: String, selectTab: Boolean = true, topicQuery: String = ""): String {
            val id = tabsUseCases.addTab(url = url, selectTab = selectTab)
            recordVisit(url)
            val opened = id.ifBlank { findTabIdByUrl(url) }
            if (topicQuery.isNotBlank() && opened.isNotBlank()) {
                BrowserTopicGraph.attachGeckoTab(opened, topicQuery)
            }
            return opened
        }

        internal fun ensureTopicTab(target: BrowserTopicTarget, sessionId: String = ""): String {
            val existing = target.tabId.trim()
            if (existing.isNotBlank() && hasTab(existing)) return existing
            // Tools that carry no url or query resolve to a blank topic. Minting
            // "about:blank#aether-0" for them and selecting it drops the agent onto an empty tab
            // with no content script, which reads back as "extension is not connected". Stay in
            // the page the agent is already working in instead.
            if (target.topicId.isBlank() && !target.asImageTab) {
                // Prefer this session's own last tab. Reaching for the globally selected tab hands
                // the caller whatever tab another agent opened most recently.
                val own = BrowserTopicGraph.lastPrimaryTabId(sessionId)
                if (own.isNotBlank() && hasTab(own)) return own
                val live = firstHttpTabId(store.state.selectedTabId.orEmpty())
                if (live.isNotBlank()) return live
            }
            val bound = if (target.asImageTab) {
                BrowserTopicGraph.imageTabId(target.topicId, sessionId)
            } else {
                BrowserTopicGraph.primaryTabId(target.topicId, sessionId)
            }
            if (bound.isNotBlank() && hasTab(bound)) return bound
            val seed = if (target.asImageTab) {
                "about:blank#aether-img-${target.topicId.hashCode().toUInt()}"
            } else {
                "about:blank#aether-${target.topicId.hashCode().toUInt()}"
            }
            val reused = findTabIdByUrl(seed)
            if (reused.isNotBlank() && hasTab(reused)) {
                if (target.topicId.isNotBlank()) {
                    BrowserTopicGraph.bindGeckoTab(
                        topicId = target.topicId,
                        tabId = reused,
                        asImageTab = target.asImageTab,
                        sessionId = sessionId,
                    )
                }
                return reused
            }
            val created = tabsUseCases.addTab(
                url = seed,
                selectTab = true,
            )
            if (target.topicId.isNotBlank() && created.isNotBlank()) {
                BrowserTopicGraph.bindGeckoTab(
                    topicId = target.topicId,
                    tabId = created,
                    asImageTab = target.asImageTab,
                    sessionId = sessionId,
                )
            }
            return created
        }

        /**
         * Fetch several documents at once through Necko. GeckoWebExecutor is built for concurrent
         * requests, so N candidates cost about as much wall time as the slowest one instead of
         * their sum — which is what makes verifying a handful of candidate URLs affordable.
         */
        internal suspend fun fetchDocuments(
            urls: List<String>,
            timeoutMs: Long = 8_000L,
            concurrency: Int = 5,
        ): Map<String, NeckoDocument?> = coroutineScope {
            val targets = urls
                .map { it.trim() }
                .filter { looksLikeHttpUrl(it) }
                .distinct()
                .take(12)
            if (targets.isEmpty()) return@coroutineScope emptyMap()
            targets.forEach { runCatching { speculativeConnect(it) } }
            val gate = Semaphore(concurrency.coerceIn(1, 8))
            targets.map { url ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        url to runCatching { fetchDocument(url, timeoutMs) }.getOrNull()
                    }
                }
            }.awaitAll().toMap()
        }

        /**
         * Fetch and parse several documents at once. The parse runs inside each request's
         * coroutine, so extracting the text of one page overlaps the download of the next
         * instead of queueing behind all of them.
         */
        internal suspend fun fetchParsedDocuments(
            urls: List<String>,
            timeoutMs: Long = 8_000L,
            concurrency: Int = 6,
            maxChars: Int = 60_000,
            sessionId: String = "",
            onPage: (String, ParsedNeckoDocument?) -> Unit = { _, _ -> },
        ): Map<String, ParsedNeckoDocument?> = coroutineScope {
            val targets = urls
                .map { it.trim() }
                .filter { looksLikeHttpUrl(it) }
                .distinct()
                .take(12)
            if (targets.isEmpty()) return@coroutineScope emptyMap()
            targets.forEach { runCatching { speculativeConnect(it) } }
            val gate = Semaphore(concurrency.coerceIn(1, 8))
            targets.map { url ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val started = System.nanoTime()
                        val doc = runCatching { fetchDocument(url, timeoutMs) }.getOrNull()
                        val fetched = System.nanoTime()
                        val parsed = doc?.let { BrowserParseLimits.withPermit {
                            val title = titleFromHtml(it.html)
                            val text = readableTextFromHtml(it.html, maxChars)
                            ParsedNeckoDocument(
                                requestedUrl = url,
                                url = it.url.ifBlank { url },
                                html = it.html,
                                title = title,
                                text = text,
                                truncated = it.truncated,
                                fetchMs = TimeUnit.NANOSECONDS.toMillis(fetched - started),
                                parseMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fetched),
                            )
                        } }
                        parsed?.let { page ->
                            BrowserPageLedger.ingest(
                                url = page.url,
                                title = page.title,
                                text = page.text,
                                source = "necko-fetch",
                                sessionId = sessionId,
                            )
                        }
                        onPage(url, parsed)
                        url to parsed
                    }
                }
            }.awaitAll().toMap()
        }

        /** Necko speculative connection: DNS + TCP/TLS only. */
        fun speculativeConnect(uri: String) {
            if (uri.isBlank()) return
            webExecutor.speculativeConnect(uri)
        }

        /**
         * Fetch a document through Gecko's HTTP cache/cookie jar.
         * Does not open a tab or run page JS.
         */
        internal fun fetchDocument(url: String, timeoutMs: Long = 8_000L): NeckoDocument? {
            val start = System.nanoTime()
            return BrowserHttpLimits.withPermit(url, timeoutMs) {
                val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                if (remaining <= 0) null else fetchDocumentWithPermit(url, remaining)
            }
        }

        private fun fetchDocumentWithPermit(url: String, timeoutMs: Long): NeckoDocument? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
            val request = WebRequest.Builder(url)
                .method("GET")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .build()
            val response = runCatching { webExecutor.fetch(request).poll(timeoutMs) }.getOrNull()
                ?: return null
            val bodyTimeout = BrowserBodyTimeouts.schedule({
                runCatching { response.body?.close() }
            }, (deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            return try {
                if (response.statusCode !in 200..299) return null
                val stream = response.body ?: return null
                val body = readBrowserBody(stream, MaxNeckoBodyBytes, deadline)
                val bytes = body.bytes
                if (bytes.isEmpty()) return null
                val clipped = bytes // Already bounded while reading, before allocation can grow.
                val contentType = response.headers["Content-Type"]
                    ?: response.headers["content-type"].orEmpty()
                // Keep what the server gave us to check with later. This is the only place a
                // validator is ever observed - Gecko reads through a tab hand us text, not headers -
                // so a page opened in a tab simply has no validator and falls back to age.
                BrowserPageValidators.remember(
                    url = response.uri.orEmpty().ifBlank { url },
                    validator = PageValidator(
                        etag = headerOf(response, "ETag"),
                        lastModified = headerOf(response, "Last-Modified"),
                    ),
                )
                NeckoDocument(
                    url = response.uri.orEmpty().ifBlank { url },
                    html = String(clipped, charsetForHtmlBody(contentType, clipped)),
                    truncated = body.truncated,
                )
            } finally {
                bodyTimeout.cancel(false)
                runCatching { response.body?.close() }
            }
        }

        private fun headerOf(response: WebResponse, name: String): String =
            response.headers[name] ?: response.headers[name.lowercase()].orEmpty()

        /**
         * Ask an origin whether a page changed, through Gecko's own stack.
         *
         * A conditional GET that comes back 304 costs a round trip and no body - the cheap read
         * Continuity makes before it trusts a cache. Going through Gecko rather than a second HTTP
         * client is the point: same cookie jar, same session, so the answer is about the page the
         * agent actually read and not about its signed-out twin.
         *
         * Anything other than a clean 304 or a comparable validator is [PageFreshness.Unknown], so
         * a failure here can only ever cost a re-read, never serve stale text.
         */
        internal fun revalidate(url: String, cached: PageValidator, timeoutMs: Long = 5_000L): PageFreshness {
            val builder = WebRequest.Builder(url).method("GET")
            if (cached.etag.isNotBlank()) builder.header("If-None-Match", cached.etag)
            if (cached.lastModified.isNotBlank()) builder.header("If-Modified-Since", cached.lastModified)
            val response = runCatching { webExecutor.fetch(builder.build()).poll(timeoutMs) }.getOrNull()
                ?: return PageFreshness.Unknown
            return try {
                when {
                    response.statusCode == 304 -> PageFreshness.Fresh
                    response.statusCode !in 200..299 -> PageFreshness.Unknown
                    else -> freshnessByValidator(
                        cached,
                        PageValidator(
                            etag = headerOf(response, "ETag"),
                            lastModified = headerOf(response, "Last-Modified"),
                        ),
                    )
                }
            } finally {
                runCatching { response.body?.close() }
            }
        }

        fun selectTab(id: String): Boolean {
            if (store.state.tabs.none { it.id == id }) return false
            tabsUseCases.selectTab(id)
            return true
        }

        fun closeTab(id: String): Boolean {
            if (store.state.tabs.none { it.id == id }) return false
            tabsUseCases.removeTab(id)
            return true
        }

        fun goBack(tabId: String = "") {
            val id = tabId.ifBlank { selectedTabId() }
            if (id.isNotBlank()) sessionUseCases.goBack.invoke(id) else sessionUseCases.goBack()
        }

        fun goForward(tabId: String = "") {
            val id = tabId.ifBlank { selectedTabId() }
            if (id.isNotBlank()) sessionUseCases.goForward.invoke(id) else sessionUseCases.goForward()
        }

        fun reload(tabId: String = "") {
            val id = tabId.ifBlank { selectedTabId() }
            if (id.isNotBlank()) sessionUseCases.reload.invoke(id) else sessionUseCases.reload()
        }

        fun persistTabs(prefs: BrowserPreferences): BrowserPreferences {
            val urls = store.state.tabs
                .filter { tab -> shouldPersistBrowserTab(tab.id, tab.content.url) }
                .map { it.content.url }
            return prefs.copy(lastTabUrls = urls.take(8))
        }

        suspend fun pageCommand(
            op: String,
            args: JSONObject = JSONObject(),
            timeoutMs: Long = 10_000,
            tabId: String = "",
        ): JSONObject {
            val target = tabId.trim()
            if (target.isBlank()) {
                return JSONObject().put("ok", false).put("code", "no_tab")
                    .put("errmsg", "No topic tab.")
            }
            return mutexFor(target).withLock {
                sendPageCommandLocked(op, args, timeoutMs.coerceIn(1_000, 20_000), target)
            }
        }

        private fun mutexFor(tabId: String): Mutex =
            mutexByTab.getOrPut(tabId.ifBlank { "_none" }) { Mutex() }

        private suspend fun sendPageCommandLocked(
            op: String,
            args: JSONObject,
            timeoutMs: Long,
            tabId: String,
        ): JSONObject {
            ensureSessionRendered(tabId)
            ensureContentHandler(tabId)
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JSONObject>()
            pending[id] = deferred
            val payload = JSONObject()
                .put("id", id)
                .put("op", op)
                .put("tabId", tabId)
                .put("url", tabUrl(tabId))
            args.keys().forEach { key ->
                val value = args.opt(key)
                if (value != null && value != JSONObject.NULL) payload.put(key, value)
            }
            var channel = liveChannel(tabId)
            if (channel == null) {
                // No port for this tab and nothing loading: one nudge, a short grace period, then
                // give up. The old unconditional 6s wait was pure loss in exactly this case.
                val hopeless = !hasAnyChannel(tabId) && !tabLoading(tabId)
                val budget = if (hopeless) 800L else 6_000L
                val deadline = System.currentTimeMillis() + budget
                ensureSessionRendered(tabId)
                ensureContentHandler(tabId)
                var nudgedAt = System.currentTimeMillis()
                while (channel == null && System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (!hopeless && now - nudgedAt >= 600L) {
                        nudgedAt = now
                        ensureSessionRendered(tabId)
                        ensureContentHandler(tabId)
                    }
                    delay(50)
                    channel = liveChannel(tabId)
                }
            }
            // Last resort: a live script parked on another URL still beats posting into the void.
            if (channel == null) channel = liveChannel(tabId, requireFresh = false)
            if (channel == null) {
                pending.remove(id)
                return JSONObject().put("ok", false).put("code", "extension_not_ready")
                    .put("errmsg", "WebMCP extension is not connected yet.")
            }
            payload.put("docId", channel.docId)
            val posted = runCatching { postToExtension(channel.port, payload) }
            if (posted.isFailure) {
                pending.remove(id)
                return JSONObject().put("ok", false).put("errmsg", posted.exceptionOrNull()?.message ?: "port failed")
            }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (_: Throwable) {
                pending.remove(id)
                JSONObject().put("ok", false).put("code", "timeout")
                    .put("errmsg", "Page command timed out: $op")
            }
        }

        internal fun isExtensionReady(tabId: String): Boolean = liveChannel(tabId) != null

        /**
         * Is there any content-script port for this tab at all, ready or not? When there is none
         * and the tab is not loading, waiting cannot help: nothing is on its way. Callers use this
         * to fail in seconds instead of burning the whole tool budget.
         */
        internal fun hasAnyChannel(tabId: String): Boolean {
            val id = tabId.trim()
            if (id.isBlank()) return false
            return channels.any { it.tabId == id }
        }

        internal fun channelReadyState(tabId: String): String = liveChannel(tabId)?.readyState.orEmpty()

        private fun dropChannels(dead: List<ContentChannel>) {
            if (dead.isNotEmpty()) channels.removeAll(dead)
        }

        private fun noteHello(port: Port, json: JSONObject) {
            val channel = channels.firstOrNull { it.port === port }
                ?: ContentChannel(port).also { channels.add(it) }
            if (channel.tabId.isBlank()) {
                channel.tabId = port.engineSession?.let { tabIdForSession(it) }.orEmpty()
            }
            channel.docId = json.optString("docId")
            channel.href = json.optString("href")
            channel.readyState = json.optString("readyState")
            channel.helloAt = System.currentTimeMillis()
            if (channel.tabId.isNotBlank()) {
                dropChannels(
                    channels.filter { other ->
                        other !== channel && other.tabId == channel.tabId && other.docId != channel.docId
                    },
                )
            }
            android.util.Log.i(
                "AetherBrowserRuntime",
                "WebMCP hello tab=" + channel.tabId + " doc=" + channel.docId +
                    " state=" + channel.readyState + " via=" + json.optString("reason"),
            )
        }

        private fun noteBye(port: Port, json: JSONObject) {
            val docId = json.optString("docId")
            dropChannels(
                channels.filter { it.port === port || (docId.isNotBlank() && it.docId == docId) },
            )
        }

        /**
         * A channel is usable only when its content script has said hello (so the document is known
         * to be alive) and, unless [requireFresh] is relaxed, when that script is sitting on the URL
         * this tab is supposed to be showing.
         */
        private fun liveChannel(tabId: String, requireFresh: Boolean = true): ContentChannel? {
            val id = tabId.trim()
            if (id.isBlank()) return null
            channels.forEach { channel ->
                if (channel.tabId.isBlank()) {
                    channel.tabId = channel.port.engineSession?.let { tabIdForSession(it) }.orEmpty()
                }
            }
            val ready = channels.filter { it.tabId == id && it.ready }
            if (ready.isEmpty()) return null
            val newest = ready.maxByOrNull { it.helloAt } ?: return null
            if (!requireFresh) return newest
            val current = normalizeBrowsedUrl(tabUrl(id))
            if (current.isBlank()) return newest
            ready.filter { normalizeBrowsedUrl(it.href) == current }
                .maxByOrNull { it.helloAt }
                ?.let { return it }
            // A single-page app rewrites its own URL and the store lags behind it, so exact
            // equality rejects a perfectly live content script. Same origin is the honest test.
            val origin = originOf(tabUrl(id))
            if (origin.isNotBlank()) {
                ready.filter { originOf(it.href) == origin }
                    .maxByOrNull { it.helloAt }
                    ?.let { return it }
            }
            return if (!tabLoading(id)) newest else null
        }

        /** Diagnostics for a not-ready error: what we actually hold for this tab. */
        internal fun channelReport(tabId: String): JSONObject {
            val id = tabId.trim()
            val list = JSONArray()
            channels.forEach { channel ->
                list.put(
                    JSONObject()
                        .put("tab", channel.tabId)
                        .put("doc", channel.docId)
                        .put("href", channel.href)
                        .put("ready_state", channel.readyState)
                        .put("age_ms", if (channel.helloAt > 0) System.currentTimeMillis() - channel.helloAt else -1),
                )
            }
            return JSONObject()
                .put("tab_id", id)
                .put("tab_url", tabUrl(id))
                .put("tab_loading", tabLoading(id))
                .put("channels", list)
        }

        private fun portFor(tabId: String): Port? = liveChannel(tabId)?.port

        private fun tabIdForSession(session: EngineSession): String =
            store.state.tabs.firstOrNull { it.engineState.engineSession === session }?.id.orEmpty()

        private fun postToExtension(port: Port, payload: JSONObject) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                port.postMessage(payload)
                return
            }
            var error: Throwable? = null
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    port.postMessage(payload)
                } catch (throwable: Throwable) {
                    error = throwable
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(2, TimeUnit.SECONDS)) error("Timed out posting to the browser extension.")
            error?.let { throw it }
        }

        fun ingestExtensionMessage(message: JSONObject) {
            val kind = message.optString("kind")
            if (kind == "resource") {
                rememberNetwork(
                    url = message.optString("url"),
                    method = message.optString("method").ifBlank { "GET" },
                    isNav = message.optString("type") == "main_frame",
                    type = message.optString("type"),
                    status = message.optInt("status"),
                )
                return
            }
            completeCommand(message)
        }

        fun completeCommand(message: JSONObject) {
            val id = message.optString("id")
            if (id.isBlank()) return
            val deferred = pending.remove(id) ?: return
            deferred.complete(message.optJSONObject("result") ?: message)
        }

        private fun bindLoginStorage() {
            runCatching {
                runtime.setAutocompleteStorageDelegate(
                    object : Autocomplete.StorageDelegate {
                        override fun onLoginFetch(
                            domain: String,
                        ): GeckoResult<Array<Autocomplete.LoginEntry>> {
                            if (!prefsRef.get().autofillPasswords && !prefsRef.get().savePasswords) {
                                return GeckoResult.fromValue(emptyArray())
                            }
                            val entries = vault.find(domain).map { it.toLoginEntry() }.toTypedArray()
                            return GeckoResult.fromValue(entries)
                        }

                        override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> {
                            if (!prefsRef.get().autofillPasswords && !prefsRef.get().savePasswords) {
                                return GeckoResult.fromValue(emptyArray())
                            }
                            val entries = vault.list().map { it.toLoginEntry() }.toTypedArray()
                            return GeckoResult.fromValue(entries)
                        }

                        override fun onLoginSave(login: Autocomplete.LoginEntry) {
                            if (!prefsRef.get().savePasswords) return
                            val origin = login.origin.orEmpty()
                            val username = login.username.orEmpty()
                            val password = login.password.orEmpty()
                            if (origin.isBlank() || password.isBlank()) return
                            vault.save(origin, username, password)
                        }
                    },
                )
            }
        }

        private fun SavedBrowserLogin.toLoginEntry(): Autocomplete.LoginEntry {
            val originUrl = loginOriginUrl(origin)
            return Autocomplete.LoginEntry.Builder()
                .guid(id)
                .origin(originUrl)
                .formActionOrigin(originUrl)
                .username(username)
                .password(password)
                .build()
        }

        fun clearCookiesAndCache() {
            runCatching {
                engine.clearData(
                    Engine.BrowsingData.select(
                        Engine.BrowsingData.COOKIES,
                        Engine.BrowsingData.DOM_STORAGES,
                        Engine.BrowsingData.ALL_CACHES,
                    ),
                )
            }
        }

        private fun installWebMcpExtension() {
            engine.installBuiltInWebExtension(
                id = "webmcp@aether",
                url = "resource://android/assets/extensions/webmcp/",
                onSuccess = { extension ->
                    android.util.Log.i("AetherBrowserRuntime", "WebMCP extension installed: ${extension.id}")
                    bindExtensionMessages(extension)
                },
                onError = { error ->
                    android.util.Log.e("AetherBrowserRuntime", "WebMCP extension install failed: $error")
                },
            )
        }

        private fun bindExtensionMessages(extension: WebExtension) {
            installedExtension = extension
            runCatching { extension.registerBackgroundMessageHandler("aether", messageHandler) }
            ensureContentHandler()
        }

        private fun ensureContentHandler(tabId: String = "") {
            val extension = installedExtension ?: return
            val sessions = if (tabId.isNotBlank()) {
                listOfNotNull(store.state.tabs.firstOrNull { it.id == tabId }?.engineState?.engineSession)
            } else {
                store.state.tabs.mapNotNull { it.engineState?.engineSession }
            }
            sessions.forEach { session ->
                if (contentHandlerSessions.putIfAbsent(session, true) != null) return@forEach
                runCatching {
                    extension.registerContentMessageHandler(session, "aether", messageHandler)
                }.onFailure {
                    // If registration fails (e.g. session not ready yet), remove the mark
                    // so we can retry on the next pageCommand instead of skipping forever.
                    contentHandlerSessions.remove(session)
                }
            }
        }
    }

    private fun trackingPolicy(
        prefs: BrowserPreferences,
        agentSession: Boolean = false,
    ): EngineSession.TrackingProtectionPolicy = when {
        agentSession && !prefs.agentTrackingProtection -> EngineSession.TrackingProtectionPolicy.none()
        prefs.trackingProtection -> EngineSession.TrackingProtectionPolicy.recommended()
        else -> EngineSession.TrackingProtectionPolicy.none()
    }

    private fun cookieBehavior(prefs: BrowserPreferences): Int = when {
        !prefs.acceptCookies -> ContentBlocking.CookieBehavior.ACCEPT_NONE
        prefs.blockThirdPartyCookies -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
        else -> ContentBlocking.CookieBehavior.ACCEPT_ALL
    }

    private fun loginOriginUrl(raw: String): String {
        val host = BrowserLoginVault.originHost(raw).ifBlank { raw.trim() }
        return if (raw.trim().startsWith("http://")) "http://$host" else "https://$host"
    }
}
