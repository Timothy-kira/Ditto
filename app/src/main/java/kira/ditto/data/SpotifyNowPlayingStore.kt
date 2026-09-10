package kira.ditto.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.spotify.protocol.types.PlayerState
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object SpotifyNowPlayingStore {
    private const val Tag = "SpotifyNowPlaying"
    private const val SpotifyMusicPackage = "com.spotify.music"
    private const val FallbackPollMs = 900L

    private val _state = MutableStateFlow(SpotifyNowPlaying())
    val state: StateFlow<SpotifyNowPlaying> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val coverExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spotify-now-playing-cover").apply { isDaemon = true }
    }
    private val observers = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var fallbackPollPosted = false
    private var promptedNotificationAccess = false
    private var loadingCoverToken: String = ""

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            applyMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            applyPlaybackState(state)
        }

        override fun onSessionDestroyed() {
            detachController()
            refreshSessions()
        }
    }

    private val fallbackPoll = object : Runnable {
        override fun run() {
            fallbackPollPosted = false
            if (!shouldPollFallback()) return
            SpotifyAppRemoteClient.requestPlayerState(::applyRemotePlayerState)
            scheduleFallbackPoll()
        }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
        refreshAccess()
        refreshSessions()
        syncFallbackPolling()
    }

    fun onListenerConnected(service: SpotifyMediaSessionListener) {
        appContext = service.applicationContext
        refreshAccess()
        refreshSessions(listenerService = service)
        syncFallbackPolling()
    }

    fun onListenerDisconnected() {
        detachController()
        refreshAccess()
        syncFallbackPolling()
    }

    fun addUiObserver() {
        if (observers.incrementAndGet() == 1) {
            syncFallbackPolling()
        }
    }

    fun removeUiObserver() {
        if (observers.updateAndGet { current -> (current - 1).coerceAtLeast(0) } == 0) {
            stopFallbackPoll()
        }
    }

    fun seedIfEmpty(
        title: String,
        artist: String,
        isPlaying: Boolean,
        imageUri: String,
        coverPath: String,
    ) {
        val current = _state.value
        if (current.title.isNotBlank() || current.hasActiveSession) return
        val cover = decodeCoverFile(coverPath)
        _state.update { previous ->
            previous.copy(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                cover = cover ?: previous.cover,
                coverToken = coverToken(title, artist, imageUri, cover),
                imageUri = imageUri.ifBlank { previous.imageUri },
            )
        }
        if (cover == null && imageUri.isNotBlank()) {
            loadRemoteCover(imageUri, title, artist)
        }
    }

    fun requestNotificationAccess(context: Context, force: Boolean = false) {
        refreshAccess()
        if (_state.value.hasNotificationAccess) return
        if (promptedNotificationAccess && !force) return
        promptedNotificationAccess = true
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error ->
            Log.w(Tag, "open notification listener settings failed", error)
        }
    }

    fun togglePlayPause() {
        val playing = _state.value.isPlaying
        _state.update { it.copy(isPlaying = !playing) }
        if (SpotifyAppRemoteClient.isConnected()) {
            if (playing) SpotifyAppRemoteClient.pausePlayback() else SpotifyAppRemoteClient.resumePlayback()
            return
        }
        val controls = controller?.transportControls ?: return
        if (playing) controls.pause() else controls.play()
    }

    fun skipToNext() {
        if (SpotifyAppRemoteClient.isConnected()) {
            SpotifyAppRemoteClient.skipToNext()
            return
        }
        controller?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        if (SpotifyAppRemoteClient.isConnected()) {
            SpotifyAppRemoteClient.skipToPreviousTrack()
            return
        }
        val controls = controller?.transportControls ?: return
        if ((controller?.playbackState?.position ?: 0L) > 2_000L) {
            runCatching { controls.seekTo(0L) }
        }
        controls.skipToPrevious()
    }

    fun refreshAccess() {
        val context = appContext ?: return
        val enabled = hasNotificationAccess(context)
        _state.update { current ->
            if (current.hasNotificationAccess == enabled) current
            else current.copy(hasNotificationAccess = enabled)
        }
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val component = listenerComponent(context)
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabledPackages.contains(context.packageName)) return true
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return flat.contains(component.flattenToString()) ||
            flat.contains(component.flattenToShortString())
    }

    private fun refreshSessions(listenerService: SpotifyMediaSessionListener? = null) {
        val context = listenerService ?: appContext ?: return
        val app = context.applicationContext
        if (!hasNotificationAccess(app)) {
            detachController()
            _state.update { it.copy(hasActiveSession = false, hasNotificationAccess = false) }
            return
        }
        val manager = app.getSystemService(MediaSessionManager::class.java) ?: return
        val component = listenerComponent(app)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrElse { error ->
            Log.w(Tag, "getActiveSessions failed", error)
            emptyList()
        }
        bindSpotifyController(sessions)
        ensureSessionsListener(manager, component)
    }

    private fun ensureSessionsListener(
        manager: MediaSessionManager,
        component: ComponentName,
    ) {
        if (sessionsListener != null) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindSpotifyController(controllers.orEmpty())
            syncFallbackPolling()
        }
        val attached = runCatching {
            manager.addOnActiveSessionsChangedListener(listener, component, mainHandler)
        }.isSuccess
        if (attached) {
            sessionsListener = listener
        }
    }

    private fun bindSpotifyController(controllers: List<MediaController>) {
        val next = controllers.firstOrNull { it.packageName == SpotifyMusicPackage }
            ?: controllers.firstOrNull { it.packageName.startsWith("com.spotify.") }
        if (next == null) {
            detachController()
            _state.update { it.copy(hasActiveSession = false) }
            return
        }
        if (controller?.sessionToken == next.sessionToken) {
            applyMetadata(next.metadata)
            applyPlaybackState(next.playbackState)
            _state.update { it.copy(hasActiveSession = true, hasNotificationAccess = true) }
            return
        }
        detachController()
        controller = next
        next.registerCallback(controllerCallback, mainHandler)
        applyMetadata(next.metadata)
        applyPlaybackState(next.playbackState)
        _state.update { it.copy(hasActiveSession = true, hasNotificationAccess = true) }
        stopFallbackPoll()
    }

    private fun detachController() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun applyMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.string(MediaMetadata.METADATA_KEY_TITLE)
            .ifBlank { metadata.string(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
        val artist = metadata.string(MediaMetadata.METADATA_KEY_ARTIST)
            .ifBlank { metadata.string(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }
            .ifBlank { metadata.string(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) }
        val imageUri = metadata.string(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .ifBlank { metadata.string(MediaMetadata.METADATA_KEY_ART_URI) }
            .ifBlank { metadata.string(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) }
        val bitmap = metadata.bitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.bitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val copied = bitmap?.copyArgb()
        _state.update { current ->
            current.copy(
                title = title.ifBlank { current.title },
                artist = artist.ifBlank { current.artist },
                cover = copied ?: current.cover,
                coverToken = coverToken(
                    title = title.ifBlank { current.title },
                    artist = artist.ifBlank { current.artist },
                    imageUri = imageUri.ifBlank { current.imageUri },
                    cover = copied ?: current.cover,
                ),
                imageUri = imageUri.ifBlank { current.imageUri },
                hasActiveSession = true,
            )
        }
        if (copied == null && imageUri.isNotBlank()) {
            loadRemoteCover(
                imageUri = imageUri,
                title = title.ifBlank { _state.value.title },
                artist = artist.ifBlank { _state.value.artist },
            )
        }
    }

    private fun applyPlaybackState(state: PlaybackState?) {
        if (state == null) return
        val playing = when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            -> true
            else -> false
        }
        _state.update { current ->
            if (current.isPlaying == playing) current else current.copy(isPlaying = playing)
        }
    }

    private fun applyRemotePlayerState(state: PlayerState) {
        if (!shouldPollFallback() && _state.value.hasActiveSession) {
            val imageUri = state.track?.imageUri?.raw.orEmpty()
            if (_state.value.cover == null && imageUri.isNotBlank()) {
                loadRemoteCover(imageUri, _state.value.title, _state.value.artist)
            }
            return
        }
        val track = state.track
        val title = track?.name.orEmpty()
        val artist = track?.artists
            ?.mapNotNull { person -> person?.name?.takeIf { it.isNotBlank() } }
            ?.joinToString(", ")
            .orEmpty()
            .ifBlank { track?.artist?.name.orEmpty() }
        val imageUri = track?.imageUri?.raw.orEmpty()
        _state.update { current ->
            current.copy(
                title = title.ifBlank { current.title },
                artist = artist.ifBlank { current.artist },
                isPlaying = !state.isPaused,
                imageUri = imageUri.ifBlank { current.imageUri },
            )
        }
        if (imageUri.isNotBlank() && imageUri != _state.value.coverToken) {
            loadRemoteCover(imageUri, title, artist)
        }
    }

    private fun loadRemoteCover(imageUri: String, title: String, artist: String) {
        val context = appContext ?: return
        if (imageUri.isBlank() || loadingCoverToken == imageUri) return
        loadingCoverToken = imageUri
        coverExecutor.execute {
            val path = SpotifyAppRemoteClient.resolveCoverPath(context, imageUri)
            val bitmap = decodeCoverFile(path.orEmpty())
            mainHandler.post {
                if (bitmap == null) {
                    if (loadingCoverToken == imageUri) loadingCoverToken = ""
                    return@post
                }
                _state.update { current ->
                    current.copy(
                        cover = bitmap,
                        coverToken = coverToken(title.ifBlank { current.title }, artist.ifBlank { current.artist }, imageUri, bitmap),
                        imageUri = imageUri,
                    )
                }
                if (loadingCoverToken == imageUri) loadingCoverToken = ""
            }
        }
    }

    private fun shouldPollFallback(): Boolean {
        if (observers.get() <= 0) return false
        val current = _state.value
        return !current.hasNotificationAccess || !current.hasActiveSession || controller == null
    }

    private fun syncFallbackPolling() {
        if (shouldPollFallback()) {
            SpotifyAppRemoteClient.requestPlayerState(::applyRemotePlayerState)
            scheduleFallbackPoll()
        } else {
            stopFallbackPoll()
        }
    }

    private fun scheduleFallbackPoll() {
        if (fallbackPollPosted || !shouldPollFallback()) return
        fallbackPollPosted = true
        mainHandler.postDelayed(fallbackPoll, FallbackPollMs)
    }

    private fun stopFallbackPoll() {
        if (!fallbackPollPosted) return
        mainHandler.removeCallbacks(fallbackPoll)
        fallbackPollPosted = false
    }

    private fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, SpotifyMediaSessionListener::class.java)

    private fun MediaMetadata.string(key: String): String =
        runCatching { getString(key).orEmpty().trim() }.getOrDefault("")

    private fun MediaMetadata.bitmap(key: String): Bitmap? =
        runCatching { getBitmap(key) }.getOrNull()

    private fun Bitmap.copyArgb(): Bitmap? = runCatching {
        if (isRecycled) null else copy(Bitmap.Config.ARGB_8888, false)
    }.getOrNull()

    private fun decodeCoverFile(path: String): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    private fun coverToken(title: String, artist: String, imageUri: String, cover: Bitmap?): String {
        val coverPart = cover?.let { "${it.width}x${it.height}:${it.byteCount}" }.orEmpty()
        return listOf(title, artist, imageUri, coverPart).joinToString("|")
    }
}
