package kira.ditto.data

import android.content.Context
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlaybackSpeed
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import com.spotify.protocol.types.Track
import org.json.JSONArray
import org.json.JSONObject

internal object SpotifyMcpHost {
    fun execute(context: Context, name: String, arguments: JSONObject): String {
        val tool = SpotifyMcp.canonicalToolName(name)
        if (tool.isBlank()) {
            return errorJson("unknown_tool", "Unknown Spotify tool: $name")
        }
        val store = HostSecretStore(context.applicationContext)
        if (SpotifyAppRemoteClient.isConnected() && shouldBringSpotifyToFront(tool, arguments)) {
            runCatching {
                SpotifyAppRemoteClient.wakeSpotifyApp(context.applicationContext, bringToFront = true)
            }
        }
        val connected = runCatching {
            SpotifyAppRemoteClient.ensureConnected(context, showAuth = false)
        }
        val remote = connected.getOrNull()
        if (remote == null || !remote.isConnected) {
            runCatching { SpotifyAuth.connectWithBrowser(context, store) }
            val retry = runCatching {
                SpotifyAppRemoteClient.ensureConnected(context, showAuth = true)
            }.getOrNull()
            if (retry == null || !retry.isConnected) {
                return JSONObject()
                    .put("ok", false)
                    .put("code", "input_required")
                    .put(
                        "reason",
                        connected.exceptionOrNull()?.message
                            ?: "Connect Spotify in Settings and authorize in the Spotify app.",
                    )
                    .toString()
            }
            return runCatching {
                dispatch(context.applicationContext, retry, tool, arguments).put("ok", true).toString()
            }.getOrElse { error -> errorJson("spotify_app", error.message ?: "Spotify failed") }
        }
        return runCatching {
            dispatch(context.applicationContext, remote, tool, arguments).put("ok", true).toString()
        }.getOrElse { error -> errorJson("spotify_app", error.message ?: "Spotify failed") }
    }

    internal fun dispatch(
        context: Context,
        remote: SpotifyAppRemote,
        tool: String,
        arguments: JSONObject,
    ): JSONObject = when (tool) {
        "SpotifyPlayback" -> playback(context, remote, arguments)
        "SpotifySearch" -> search(remote, arguments)
        "SpotifyQueue" -> queue(context, arguments)
        "SpotifyGetInfo" -> getInfo(context, remote, arguments)
        "SpotifyPlaylist" -> playlist(remote, arguments)
        "SpotifyLibrary" -> library(arguments)
        else -> error("Unknown Spotify tool: $tool")
    }

    internal fun parseSpotifyRef(raw: String): Pair<String, String> {
        val uri = spotifyPlayUri(raw)
        val parts = uri.removePrefix("spotify:").split(':')
        if (parts.size >= 2) return parts[0] to parts.drop(1).joinToString(":")
        error("Need a Spotify URI or open.spotify.com URL")
    }

    private fun playback(
        context: Context,
        remote: SpotifyAppRemote,
        arguments: JSONObject,
    ): JSONObject {
        val action = spotifyStringArg(arguments, "action").lowercase().ifBlank { "get" }
        return when (action) {
            "get" -> currentPlayback(context)
            "open", "wake", "launch" -> {
                SpotifyAppRemoteClient.wakeSpotifyApp(context, bringToFront = true)
                currentPlayback(context).put("status", "opened")
            }
            "pause" -> {
                SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.pause() }
                currentPlayback(context).put("status", "paused")
            }
            "skip", "next" -> {
                val skips = spotifyIntArg(arguments, 1, 10, "num_skips", "numSkips")
                repeat(skips) {
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.skipNext() }
                }
                currentPlayback(context).put("status", "skipped").put("num_skips", skips)
            }
            "previous", "prev" -> {
                runCatching { SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.seekTo(0) } }
                SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.skipPrevious() }
                currentPlayback(context).put("status", "previous")
            }
            "start", "play" -> {
                runCatching {
                    SpotifyAppRemoteClient.wakeSpotifyApp(context, bringToFront = true)
                }
                val uri = spotifyStringArg(arguments, "spotify_uri", "spotifyUri", "uri")
                if (uri.isBlank()) {
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.resume() }
                } else {
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.play(spotifyPlayUri(uri)) }
                }
                currentPlayback(context).put("status", "playing")
            }
            "shuffle" -> {
                if (arguments.has("shuffle") && !arguments.isNull("shuffle")) {
                    val on = spotifyBoolArg(arguments, true, "shuffle")
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.setShuffle(on) }
                    currentPlayback(context).put("status", if (on) "shuffle_on" else "shuffle_off")
                } else {
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.toggleShuffle() }
                    currentPlayback(context).put("status", "shuffle_toggled")
                }
            }
            "repeat" -> {
                val mode = spotifyStringArg(arguments, "repeat", "repeat_mode", "repeatMode")
                    .lowercase()
                if (mode.isBlank()) {
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.toggleRepeat() }
                    currentPlayback(context).put("status", "repeat_toggled")
                } else {
                    val value = when (mode) {
                        "off", "none", "0" -> Repeat.OFF
                        "one", "track", "1" -> Repeat.ONE
                        "all", "context", "playlist", "2" -> Repeat.ALL
                        else -> error("repeat must be off, one, or all")
                    }
                    SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.setRepeat(value) }
                    currentPlayback(context).put("status", "repeat_$mode")
                }
            }
            "seek" -> {
                val position = spotifyNumberArg(
                    arguments,
                    0,
                    0,
                    Int.MAX_VALUE,
                    "position_ms",
                    "positionMs",
                    "position",
                )
                SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.seekTo(position.toLong()) }
                currentPlayback(context).put("status", "seeked").put("position_ms", position)
            }
            "volume" -> {
                when {
                    spotifyBoolArg(arguments, false, "quieter", "down") ->
                        SpotifyAppRemoteClient.awaitRemoteDone { it.connectApi.connectDecreaseVolume() }
                    spotifyBoolArg(arguments, false, "louder", "up") ->
                        SpotifyAppRemoteClient.awaitRemoteDone { it.connectApi.connectIncreaseVolume() }
                    else -> {
                        val level = volumeLevel(arguments)
                        SpotifyAppRemoteClient.awaitRemoteDone { it.connectApi.connectSetVolume(level) }
                    }
                }
                currentPlayback(context).put("status", "volume")
            }
            "podcast_speed", "podcastspeed" -> {
                val speed = spotifyNumberArg(arguments, 100, 50, 300, "speed", "podcast_speed")
                val mapped = podcastSpeed(speed)
                SpotifyAppRemoteClient.awaitRemoteDone {
                    it.playerApi.setPodcastPlaybackSpeed(mapped)
                }
                currentPlayback(context).put("status", "podcast_speed").put("speed", mapped.getValue())
            }
            "switch_local", "local" -> {
                SpotifyAppRemoteClient.awaitRemoteDone { it.connectApi.connectSwitchToLocalDevice() }
                currentPlayback(context).put("status", "local_device")
            }
            else -> error("Unknown playback action: $action")
        }
    }

    private fun search(remote: SpotifyAppRemote, arguments: JSONObject): JSONObject {
        val query = spotifyStringArg(arguments, "query")
        check(query.isNotBlank()) { "query is required" }
        if (query.contains("spotify:") || query.contains("open.spotify.com")) {
            val uri = spotifyPlayUri(query)
            val (kind, id) = parseSpotifyRef(uri)
            return JSONObject()
                .put("query", query)
                .put("uri", uri)
                .put("type", kind)
                .put("id", id)
        }
        val limit = spotifyIntArg(arguments, 10, 20, "limit")
        val qtype = spotifyStringArg(arguments, "qtype", "type")
        val shelf = spotifyStringArg(arguments, "shelf", "content_type", "contentType")
        val matches = JSONArray()
        SpotifyAppRemoteClient.browseRecommended(remote, contentType(shelf)).forEach { item ->
            if (!spotifyHomeItemMatches(query, item.title.orEmpty(), item.subtitle.orEmpty(), item.uri.orEmpty())) {
                return@forEach
            }
            if (!spotifyHomeTypeMatches(qtype, item.uri.orEmpty())) return@forEach
            if (matches.length() >= limit) return@forEach
            matches.put(compactListItem(item))
        }
        val out = JSONObject()
            .put("query", query)
            .put("source", "spotify_app_remote_home")
            .put("items", matches)
        if (matches.length() == 0) {
            out.put("note", SpotifyHomeEmptyNote)
        }
        return out
    }

    private fun queue(
        context: Context,
        arguments: JSONObject,
    ): JSONObject {
        val action = spotifyStringArg(arguments, "action").lowercase().ifBlank { "get" }
        return when (action) {
            "get" -> currentPlayback(context)
                .put("note", "App Remote 只能读正在播放的曲目，不能列出完整队列。")
            "add" -> {
                val trackId = spotifyStringArg(arguments, "track_id", "trackId")
                check(trackId.isNotBlank()) { "track_id is required for add" }
                val uri = spotifyPlayUri(trackId)
                SpotifyAppRemoteClient.awaitRemoteDone { it.playerApi.queue(uri) }
                JSONObject().put("status", "queued").put("uri", uri)
            }
            else -> error("Unknown queue action: $action")
        }
    }

    private fun getInfo(
        context: Context,
        remote: SpotifyAppRemote,
        arguments: JSONObject,
    ): JSONObject {
        val raw = spotifyStringArg(arguments, "item_uri", "itemUri", "uri")
        check(raw.isNotBlank()) { "item_uri is required" }
        val canonical = spotifyPlayUri(raw)
        val (kind, id) = parseSpotifyRef(canonical)
        val state = runCatching { SpotifyAppRemoteClient.playerState() }.getOrNull()
        val playingUri = state?.track?.uri.orEmpty()
        val out = JSONObject()
            .put("type", kind)
            .put("id", id)
            .put("uri", canonical)
        if (playingUri == canonical && state != null) {
            out.put("now_playing", compactPlayback(context, state))
        }
        val library = runCatching { libraryStateJson(canonical) }.getOrNull()
        if (library != null) out.put("library", library)
        val hit = SpotifyAppRemoteClient.browseRecommended(remote).firstOrNull { item ->
            item.uri == canonical || item.uri.contains(id)
        }
        if (hit != null) {
            out.put("title", hit.title)
            out.put("subtitle", hit.subtitle)
            out.put("playable", hit.playable)
        }
        return out
    }

    private fun playlist(remote: SpotifyAppRemote, arguments: JSONObject): JSONObject {
        val action = spotifyStringArg(arguments, "action").lowercase().ifBlank { "get" }
        val playlistId = spotifyStringArg(arguments, "playlist_id", "playlistId")
        return when (action) {
            "get" -> {
                val list = JSONArray()
                SpotifyAppRemoteClient.browseRecommended(remote)
                    .filter { it.uri.contains(":playlist:") || it.hasChildren }
                    .forEach { list.put(compactListItem(it)) }
                JSONObject()
                    .put("playlists", list)
                    .put("note", "These are home-shelf playlists from App Remote, not the full account list.")
            }
            "get_tracks" -> {
                check(playlistId.isNotBlank()) { "playlist_id is required" }
                val needle = runCatching { spotifyPlayUri(playlistId) }.getOrDefault(playlistId)
                val item = SpotifyAppRemoteClient.browseRecommended(remote).firstOrNull { candidate ->
                    candidate.uri == needle || candidate.uri.contains(playlistId) ||
                        candidate.id == playlistId
                } ?: error("App Remote 首页里找不到这个歌单，请先在 Spotify 里打开它。")
                val limit = spotifyNumberArg(arguments, 50, 1, 50, "limit")
                val offset = spotifyNumberArg(arguments, 0, 0, 500, "offset")
                val children = SpotifyAppRemoteClient.childrenOf(remote, item, limit, offset)
                JSONObject()
                    .put(
                        "items",
                        JSONArray().apply {
                            children.items?.forEach { put(compactListItem(it)) }
                        },
                    )
                    .put("limit", children.limit)
                    .put("offset", children.offset)
                    .put("total", children.total)
            }
            "add_tracks", "remove_tracks", "change_details", "create" ->
                error("App Remote 不能增删改建歌单。收藏请用 SpotifyLibrary add。")
            else -> error("Unknown playlist action: $action")
        }
    }

    private fun library(arguments: JSONObject): JSONObject {
        val action = spotifyStringArg(arguments, "action").lowercase().ifBlank { "get" }
        val raw = spotifyStringArg(arguments, "uri", "item_uri", "itemUri", "track_id", "trackId")
        val uri = if (raw.isNotBlank()) {
            spotifyPlayUri(raw)
        } else {
            val playing = SpotifyAppRemoteClient.playerState().track?.uri.orEmpty()
            check(playing.isNotBlank()) { "没有正在播放的曲目，请传入 uri。" }
            playing
        }
        return when (action) {
            "get", "check" -> libraryStateJson(uri)
            "add", "like", "save" -> {
                SpotifyAppRemoteClient.awaitRemoteDone { it.userApi.addToLibrary(uri) }
                libraryStateJson(uri).put("status", "liked")
            }
            "remove", "unlike", "unsave" -> {
                SpotifyAppRemoteClient.awaitRemoteDone { it.userApi.removeFromLibrary(uri) }
                libraryStateJson(uri).put("status", "unliked")
            }
            else -> error("Unknown library action: $action")
        }
    }

    private fun libraryStateJson(uri: String): JSONObject {
        val state = SpotifyAppRemoteClient.awaitRemote { it.userApi.getLibraryState(uri) }
        return JSONObject()
            .put("uri", state.uri.ifBlank { uri })
            .put("is_liked", state.isAdded)
            .put("can_like", state.canAdd)
    }

    private fun currentPlayback(context: Context): JSONObject {
        var state = SpotifyAppRemoteClient.playerState()
        if (state.track == null) {
            Thread.sleep(300)
            state = SpotifyAppRemoteClient.playerState()
        }
        return compactPlayback(context, state)
    }

    private fun compactPlayback(
        context: Context,
        state: PlayerState,
    ): JSONObject {
        val track = state.track
        val imageUri = track?.imageUri?.raw.orEmpty()
        val coverPath = runCatching {
            SpotifyAppRemoteClient.resolveCoverPath(context, imageUri)
        }.getOrNull().orEmpty()
        val trackJson = compactTrack(track)
        if (imageUri.isNotBlank()) trackJson.put("image_uri", imageUri)
        if (coverPath.isNotBlank()) trackJson.put("cover_path", coverPath)
        val options = state.playbackOptions
        val restrictions = state.playbackRestrictions
        val out = JSONObject()
            .put("is_playing", !state.isPaused)
            .put("progress_ms", state.playbackPosition)
            .put("duration_ms", track?.duration ?: 0L)
            .put("shuffle", options?.isShuffling == true)
            .put("repeat", repeatName(options?.repeatMode ?: Repeat.OFF))
            .put("track", trackJson)
        if (imageUri.isNotBlank()) out.put("image_uri", imageUri)
        if (coverPath.isNotBlank()) out.put("cover_path", coverPath)
        if (restrictions != null) {
            out.put(
                "restrictions",
                JSONObject()
                    .put("can_skip_next", restrictions.canSkipNext)
                    .put("can_skip_prev", restrictions.canSkipPrev)
                    .put("can_repeat_track", restrictions.canRepeatTrack)
                    .put("can_repeat_context", restrictions.canRepeatContext)
                    .put("can_shuffle", restrictions.canToggleShuffle)
                    .put("can_seek", restrictions.canSeek),
            )
        }
        val playingUri = track?.uri.orEmpty()
        if (playingUri.isNotBlank()) {
            runCatching { libraryStateJson(playingUri) }.getOrNull()?.let { liked ->
                out.put("library", liked)
            }
        }
        runCatching {
            SpotifyAppRemoteClient.awaitRemote { it.userApi.capabilities }
        }.getOrNull()?.let { caps ->
            out.put("can_play_on_demand", caps.canPlayOnDemand)
        }
        return out
    }

    private fun compactTrack(track: Track?): JSONObject {
        if (track == null) return JSONObject()
        val artists = track.artists
            ?.mapNotNull { artist -> artist?.name?.takeIf { it.isNotBlank() } }
            ?.joinToString(", ")
            .orEmpty()
            .ifBlank { track.artist?.name.orEmpty() }
        return JSONObject()
            .put("id", track.uri.orEmpty().substringAfterLast(':'))
            .put("name", track.name.orEmpty())
            .put("uri", track.uri.orEmpty())
            .put("artists", artists)
            .put("album", track.album?.name.orEmpty())
            .put("is_podcast", track.isPodcast)
            .put("is_episode", track.isEpisode)
    }

    private fun compactListItem(item: ListItem): JSONObject = JSONObject()
        .put("id", item.id.orEmpty().ifBlank { item.uri.substringAfterLast(':') })
        .put("name", item.title)
        .put("subtitle", item.subtitle)
        .put("uri", item.uri)
        .put("playable", item.playable)
        .put("has_children", item.hasChildren)

    private fun contentType(raw: String): String = when (raw.trim().lowercase()) {
        "", "default", "home" -> ContentApi.ContentType.DEFAULT
        "automotive", "auto", "car" -> ContentApi.ContentType.AUTOMOTIVE
        "navigation", "nav" -> ContentApi.ContentType.NAVIGATION
        "fitness", "workout" -> ContentApi.ContentType.FITNESS
        "wake" -> ContentApi.ContentType.WAKE
        "sleep" -> ContentApi.ContentType.SLEEP
        else -> ContentApi.ContentType.DEFAULT
    }

    private fun volumeLevel(arguments: JSONObject): Float {
        val keys = listOf("volume", "level")
        keys.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) return@forEach
            val value = arguments.optDouble(key, Double.NaN)
            if (value.isNaN()) return@forEach
            return if (value <= 1.0) {
                value.toFloat().coerceIn(0f, 1f)
            } else {
                (value / 100.0).toFloat().coerceIn(0f, 1f)
            }
        }
        error("volume is required unless quieter/louder is set")
    }

    private fun podcastSpeed(percent: Int): PlaybackSpeed.PodcastPlaybackSpeed {
        val choices = listOf(
            50 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_50,
            80 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_80,
            100 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_100,
            120 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_120,
            150 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_150,
            200 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_200,
            300 to PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_300,
        )
        return choices.minBy { kotlin.math.abs(it.first - percent) }.second
    }

    private fun repeatName(mode: Int): String = when (mode) {
        Repeat.ONE -> "one"
        Repeat.ALL -> "all"
        else -> "off"
    }

    private fun shouldBringSpotifyToFront(tool: String, arguments: JSONObject): Boolean {
        if (tool != "SpotifyPlayback") return false
        val action = spotifyStringArg(arguments, "action").lowercase().ifBlank { "get" }
        return action in setOf("start", "play", "open", "wake", "launch")
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()
}
