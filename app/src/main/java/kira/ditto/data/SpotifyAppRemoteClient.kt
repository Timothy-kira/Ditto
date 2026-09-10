package kira.ditto.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.ListItems
import com.spotify.protocol.types.PlayerState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object SpotifyAppRemoteClient {
    const val RedirectUri = "com.kira.ditto://spotify-auth"
    const val SpotifyPackage = "com.spotify.music"
    private const val Tag = "SpotifyMCP"

    @Volatile
    private var remote: SpotifyAppRemote? = null
    private val connectLock = Any()
    private val pendingLatch = AtomicReference<CountDownLatch?>()
    private val pendingRemote = AtomicReference<SpotifyAppRemote?>()
    private val pendingError = AtomicReference<Throwable?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spotify-player").apply { isDaemon = true }
    }
    private val playerListeners = CopyOnWriteArrayList<(PlayerState) -> Unit>()
    private var playerStateSubscription: Subscription<PlayerState>? = null

    fun isConnected(): Boolean = remote?.isConnected == true

    fun disconnect() {
        synchronized(connectLock) {
            runCatching { playerStateSubscription?.cancel() }
            playerStateSubscription = null
            val current = remote
            remote = null
            if (current != null) {
                runCatching { SpotifyAppRemote.disconnect(current) }
            }
        }
    }

    fun completeConnect(spotifyAppRemote: SpotifyAppRemote?, error: Throwable?) {
        if (spotifyAppRemote != null) {
            remote = spotifyAppRemote
            pendingRemote.set(spotifyAppRemote)
            pendingError.set(null)
            ensurePlayerStateSubscriptionLocked()
        } else {
            pendingError.set(error)
        }
        pendingLatch.get()?.countDown()
    }

    fun ensureConnected(context: Context, showAuth: Boolean): SpotifyAppRemote {
        synchronized(connectLock) {
            val existing = remote
            if (existing?.isConnected == true) return existing
            if (Looper.myLooper() == Looper.getMainLooper()) {
                error("Spotify App Remote 不能在主线程上等待连接。")
            }
            val clientId = SpotifyAuth.bundledClientId()
            check(clientId.isNotBlank()) { "Spotify Client ID 未配置。" }
            wakeSpotifyApp(context, bringToFront = true, waitForProcess = true)
            val latch = CountDownLatch(1)
            pendingLatch.set(latch)
            pendingRemote.set(null)
            pendingError.set(null)
            Handler(Looper.getMainLooper()).post {
                SpotifyConnectActivity.launch(context)
            }
            if (!latch.await(120, TimeUnit.SECONDS)) {
                error("连接本机 Spotify 超时。请保持 Spotify 在前台并完成授权。")
            }
            pendingError.get()?.let { error(userMessage(it)) }
            val next = pendingRemote.get() ?: remote
                ?: error("未能连接本机 Spotify。请确认已安装并登录 Spotify。")
            remote = next
            return next
        }
    }

    fun wakeSpotifyApp(
        context: Context,
        bringToFront: Boolean = true,
        waitForProcess: Boolean = false,
    ) {
        val app = context.applicationContext
        if (!SpotifyAppRemote.isSpotifyInstalled(app)) {
            error("没有找到 Spotify 应用。请先安装官方 Spotify。")
        }
        val intent = spotifyLaunchIntent(app, bringToFront)
            ?: error("无法打开 Spotify。")
        startSpotifyActivity(app, intent)
        if (waitForProcess) {
            Thread.sleep(1_200)
        }
    }

    private fun spotifyLaunchIntent(context: Context, bringToFront: Boolean): Intent? {
        val launch = context.packageManager.getLaunchIntentForPackage(SpotifyPackage)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).setPackage(SpotifyPackage)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (bringToFront) {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
        }
        return launch
    }

    private fun startSpotifyActivity(context: Context, intent: Intent) {
        val start = {
            context.startActivity(intent)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            start()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        mainHandler.post {
            runCatching(start).onFailure { failure.set(it) }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            error("打开 Spotify 超时。")
        }
        failure.get()?.let { error(userMessage(it)) }
    }

    fun playerState(): PlayerState =
        awaitRemote { it.playerApi.playerState }

    fun pausePlayback() = runPlayer { it.playerApi.pause() }

    fun resumePlayback() = runPlayer { it.playerApi.resume() }

    fun skipToNext() = runPlayer { it.playerApi.skipNext() }

    fun skipToPrevious() = skipToPreviousTrack()

    fun skipToPreviousTrack() {
        runPlayerSequence {
            runCatching { awaitRemoteDone { it.playerApi.seekTo(0) } }
            awaitRemoteDone { it.playerApi.skipPrevious() }
        }
    }

    fun requestPlayerState(onResult: (PlayerState) -> Unit) {
        val deliver: (PlayerState) -> Unit = { state ->
            mainHandler.post { onResult(state) }
        }
        val start = Runnable {
            val live = remote
            if (live == null || !live.isConnected) return@Runnable
            live.playerApi.playerState
                .setResultCallback(deliver)
                .setErrorCallback { error ->
                    Log.e(Tag, "playerState request failed", error)
                }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start.run() else mainHandler.post(start)
    }

    fun subscribeToPlayerState(onEvent: (PlayerState) -> Unit): () -> Unit {
        synchronized(connectLock) {
            playerListeners.add(onEvent)
            ensurePlayerStateSubscriptionLocked()
        }
        requestPlayerState(onEvent)
        return {
            synchronized(connectLock) {
                playerListeners.remove(onEvent)
                if (playerListeners.isEmpty()) {
                    runCatching { playerStateSubscription?.cancel() }
                    playerStateSubscription = null
                }
            }
        }
    }

    fun resolveCoverPath(context: Context, imageUriRaw: String): String? {
        val raw = imageUriRaw.trim()
        if (raw.isBlank()) return null
        val file = coverCacheFile(context, raw)
        if (file.exists() && file.length() > 0L) return file.absolutePath
        val bitmap = runCatching {
            awaitRemote { it.imagesApi.getImage(ImageUri(raw), Image.Dimension.MEDIUM) }
        }.getOrNull() ?: return null
        runCatching {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                    "Failed to write Spotify cover"
                }
            }
        }.onFailure {
            file.delete()
            return null
        }
        return file.absolutePath.takeIf { file.exists() && file.length() > 0L }
    }

    fun browseRecommended(
        remote: SpotifyAppRemote,
        type: String = ContentApi.ContentType.DEFAULT,
        childLimit: Int = 20,
    ): List<ListItem> {
        val page = awaitRemote {
            it.contentApi.getRecommendedContentItems(type.ifBlank { ContentApi.ContentType.DEFAULT })
        }
        val roots = page.items?.toList().orEmpty()
        val out = ArrayList<ListItem>()
        roots.forEach { item ->
            out.add(item)
            if (item.hasChildren) {
                val children = runCatching {
                    awaitRemote { live -> live.contentApi.getChildrenOfItem(item, childLimit, 0) }
                }.getOrNull()?.items?.toList().orEmpty()
                out.addAll(children)
            }
        }
        return out
    }

    fun childrenOf(
        remote: SpotifyAppRemote,
        item: ListItem,
        limit: Int = 50,
        offset: Int = 0,
    ): ListItems = awaitRemote { it.contentApi.getChildrenOfItem(item, limit, offset) }

    fun awaitRemoteDone(factory: (SpotifyAppRemote) -> CallResult<*>) {
        @Suppress("UNCHECKED_CAST")
        awaitRemote { remote -> factory(remote) as CallResult<Any> }
    }

    fun <T> awaitRemote(factory: (SpotifyAppRemote) -> CallResult<T>): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            error("Spotify App Remote 不能在主线程上等待结果。")
        }
        val latch = CountDownLatch(1)
        val value = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        mainHandler.post {
            val live = remote
            if (live == null || !live.isConnected) {
                failure.set(IllegalStateException("Spotify 连接已断开。"))
                latch.countDown()
                return@post
            }
            factory(live)
                .setResultCallback { result ->
                    value.set(result)
                    latch.countDown()
                }
                .setErrorCallback { error ->
                    failure.set(error)
                    latch.countDown()
                }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            error("Spotify 操作超时。")
        }
        failure.get()?.let { error(userMessage(it)) }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }

    fun <T> awaitCall(call: CallResult<T>, timeoutMs: Long = 20_000L): T {
        val latch = CountDownLatch(1)
        val value = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        call.setResultCallback { result ->
            value.set(result)
            latch.countDown()
        }.setErrorCallback { error ->
            failure.set(error)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            error("Spotify 操作超时。")
        }
        failure.get()?.let { error(userMessage(it)) }
        @Suppress("UNCHECKED_CAST")
        return value.get() as T
    }

    private fun <T> runPlayer(factory: (SpotifyAppRemote) -> CallResult<T>) {
        if (remote?.isConnected != true) return
        playerExecutor.execute {
            runCatching { awaitRemoteDone(factory) }.onFailure { error ->
                Log.e(Tag, "Spotify player command failed", error)
            }
        }
    }

    private fun runPlayerSequence(block: () -> Unit) {
        if (remote?.isConnected != true) return
        playerExecutor.execute {
            runCatching(block).onFailure { error ->
                Log.e(Tag, "Spotify player sequence failed", error)
            }
        }
    }

    private fun ensurePlayerStateSubscriptionLocked() {
        if (playerStateSubscription != null || playerListeners.isEmpty()) return
        val current = remote
        if (current == null || !current.isConnected) return
        val start = {
            if (playerStateSubscription == null && playerListeners.isNotEmpty()) {
                val live = remote
                if (live != null && live.isConnected) {
                    val subscription = runCatching {
                        live.playerApi.subscribeToPlayerState()
                    }.getOrNull()
                    if (subscription != null) {
                        subscription.setEventCallback(object : Subscription.EventCallback<PlayerState> {
                            override fun onEvent(data: PlayerState) {
                                val listeners = playerListeners.toList()
                                mainHandler.post {
                                    listeners.forEach { listener -> listener(data) }
                                }
                            }
                        })
                        playerStateSubscription = subscription
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post(start)
    }

    private fun coverCacheFile(context: Context, imageUriRaw: String): File {
        val dir = File(context.applicationContext.cacheDir, "spotify-covers")
        dir.mkdirs()
        val safe = buildString {
            imageUriRaw.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') append(ch)
            }
        }.take(96).ifBlank { imageUriRaw.hashCode().toUInt().toString(16) }
        return File(dir, "$safe.jpg")
    }

    internal fun userMessage(error: Throwable): String {
        val raw = (error.message ?: error.toString()).trim()
        Log.e(Tag, "Spotify error ${error.javaClass.simpleName}: $raw", error)
        return when (error) {
            is UserNotAuthorizedException ->
                "Spotify 未授权控制播放。请再点连接，并在授权页允许。"
            is CouldNotFindSpotifyApp ->
                "没有找到 Spotify 应用。"
            is NotLoggedInException ->
                "请先在 Spotify 应用里登录账号。"
            is AuthenticationFailedException ->
                "Spotify 校验失败。请确认 Dashboard 的包名和 SHA1。"
            else -> {
                val lower = raw.lowercase()
                when {
                    "not installed" in lower -> "手机上没有安装 Spotify，请先安装官方 Spotify 应用。"
                    "offline" in lower -> "Spotify 处于离线模式。"
                    else -> raw.ifBlank { "无法连接本机 Spotify。" }
                }
            }
        }
    }
}
