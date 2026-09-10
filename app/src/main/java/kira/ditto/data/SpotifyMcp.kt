package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotify MCP on the loopback gateway.
 * Backed by the official Android App Remote SDK (not Spotify Web API).
 * Tool names keep the Playback / Search / Queue / GetInfo / Playlist set
 * plus Library for UserApi.addToLibrary.
 */
internal object SpotifyMcp {
    const val PluginId = "aether-spotify"
    const val ServerName = "spotify"

    val ToolNames: List<String> = listOf(
        "SpotifyPlayback",
        "SpotifySearch",
        "SpotifyQueue",
        "SpotifyGetInfo",
        "SpotifyPlaylist",
        "SpotifyLibrary",
    )

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        actionLabel = "Spotify",
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

    fun isShippedServerId(serverId: String): Boolean = serverId == PluginId

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put("tools", toolsArray())

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        if (n.contains("spotify") && ToolNames.any { tool ->
                val t = tool.lowercase()
                n == t || n.endsWith(t) || n.endsWith("_$t") || n.contains("_$t")
            }
        ) {
            return true
        }
        return ToolNames.any { tool ->
            val t = tool.lowercase()
            n == t || n.endsWith("_$t") || n.endsWith("__$t")
        }
    }

    fun canonicalToolName(name: String): String {
        val n = name.trim().lowercase().replace('-', '_')
        return ToolNames.firstOrNull { tool ->
            val t = tool.lowercase()
            n == t || n.endsWith("_$t") || n.endsWith("__$t")
        }.orEmpty()
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val visible = parsed?.toString() ?: rawOutput
        val code = parsed?.optString("code").orEmpty()
        val isError = parsed?.optBoolean("ok", true) == false && code != "input_required"
        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", visible),
                ),
            )
            .put("structuredContent", parsed ?: JSONObject().put("raw", rawOutput))
            .put("isError", isError)
        if (code == "input_required") {
            result.put("_meta", JSONObject().put("input_required", true))
        }
        return result
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(
            tool(
                "SpotifyPlayback",
                "Control the Spotify app on this phone via App Remote. " +
                    "action: get (now playing, shuffle/repeat, like state), " +
                    "start (resume, or play spotify_uri), pause, " +
                    "skip (next; num_skips), previous, " +
                    "shuffle (shuffle=true/false or omit to toggle), " +
                    "repeat (repeat=off|one|all or omit to toggle), " +
                    "seek (position_ms), volume (volume 0-100, or quieter/louder), " +
                    "podcast_speed (speed 50-300 percent), switch_local, " +
                    "open (wake and bring the Spotify app to the foreground). " +
                    "start/open launch Spotify if it is in the background. " +
                    "Cannot search the global catalog. Never open a browser.",
                extra = JSONObject()
                    .put(
                        "action",
                        stringProp(
                            "get, start, pause, skip, previous, shuffle, repeat, " +
                                "seek, volume, podcast_speed, switch_local, or open.",
                        ),
                    )
                    .put(
                        "spotify_uri",
                        stringProp("Spotify URI/URL to play for start. Omit to resume."),
                    )
                    .put("num_skips", intProp("Tracks to skip forward. Default 1."))
                    .put("shuffle", boolProp("For shuffle: true on, false off. Omit to toggle."))
                    .put("repeat", stringProp("For repeat: off, one, or all. Omit to toggle."))
                    .put("position_ms", intProp("Absolute seek position in milliseconds."))
                    .put("volume", intProp("Target volume 0-100, or 0.0-1.0."))
                    .put("quieter", boolProp("For volume: decrease one step."))
                    .put("louder", boolProp("For volume: increase one step."))
                    .put("speed", intProp("Podcast speed percent: 50, 80, 100, 120, 150, 200, 300.")),
            ),
        )
        put(
            tool(
                "SpotifySearch",
                "Find items on the Spotify app home/recommended shelves " +
                    "(daily mix, radios, liked, albums currently shown). " +
                    "This is NOT a global catalog search. Empty items means the query " +
                    "is not on the home shelves — tell the user, do not open a browser " +
                    "or open.spotify.com. If the user has a URI, play it with SpotifyPlayback start.",
                extra = JSONObject()
                    .put("query", stringProp("Search term to match against home shelf titles."))
                    .put(
                        "qtype",
                        stringProp(
                            "Optional filter: track, album, artist, playlist. " +
                                "Omit to match any shelf item.",
                        ),
                    )
                    .put("limit", intProp("Max items. Default 10, max 20."))
                    .put(
                        "shelf",
                        stringProp(
                            "Home shelf: default, automotive, navigation, fitness, wake, sleep. " +
                                "Default default.",
                        ),
                    ),
            ),
        )
        put(
            tool(
                "SpotifyQueue",
                "App Remote queue. action: add (requires track_id). " +
                    "get returns only the current track — the SDK cannot list the full queue.",
                extra = JSONObject()
                    .put("action", stringProp("get or add."))
                    .put("track_id", stringProp("Track id or spotify:track URI for add.")),
            ),
        )
        put(
            tool(
                "SpotifyGetInfo",
                "App Remote details for a URI: type, whether it is now playing, " +
                    "liked state, and a home-shelf title if that URI is on the shelves. " +
                    "Does not return artist top tracks or full album catalogs.",
                extra = JSONObject()
                    .put("item_uri", stringProp("Spotify URI or open.spotify.com URL.")),
            ),
        )
        put(
            tool(
                "SpotifyPlaylist",
                "Read home-shelf playlists via App Remote. action: get (shelves that look " +
                    "like playlists), get_tracks (children of one shelf playlist; limit/offset). " +
                    "Cannot create, edit, or add/remove tracks. To like a song use SpotifyLibrary.",
                extra = JSONObject()
                    .put("action", stringProp("get or get_tracks."))
                    .put("playlist_id", stringProp("Playlist id or URI for get_tracks."))
                    .put("limit", intProp("Children page size for get_tracks. Default 50, max 50."))
                    .put("offset", intProp("Children page offset for get_tracks. Default 0.")),
            ),
        )
        put(
            tool(
                "SpotifyLibrary",
                "Liked Songs / library via App Remote UserApi. action: get (is this URI liked), " +
                    "add (like), remove (unlike). Omit uri to use the currently playing track. " +
                    "This is how to 收藏 / save the current song. Never open a browser.",
                extra = JSONObject()
                    .put("action", stringProp("get, add, or remove."))
                    .put(
                        "uri",
                        stringProp("Spotify URI to like/unlike. Omit for the current track."),
                    ),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        extra: JSONObject = JSONObject(),
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", extra)
                .put("required", JSONArray())
                .put("additionalProperties", false),
        )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)
}

internal fun isShippedMcpServerId(serverId: String): Boolean =
    GmailMcp.isShippedServerId(serverId) ||
        SpotifyMcp.isShippedServerId(serverId) ||
        AmapMcp.isShippedServerId(serverId) ||
        GithubMcp.isShippedServerId(serverId) ||
        HuggingFaceMcp.isShippedServerId(serverId)

internal fun shippedMcpServerConfig(serverId: String): McpServerConfig? = when {
    GmailMcp.isShippedServerId(serverId) -> GmailMcp.mcpServerConfig()
    SpotifyMcp.isShippedServerId(serverId) -> SpotifyMcp.mcpServerConfig()
    AmapMcp.isShippedServerId(serverId) -> AmapMcp.mcpServerConfig()
    GithubMcp.isShippedServerId(serverId) -> GithubMcp.mcpServerConfig()
    HuggingFaceMcp.isShippedServerId(serverId) -> HuggingFaceMcp.mcpServerConfig()
    else -> null
}

internal fun spotifyPlayUri(raw: String): String {
    val value = raw.trim()
    if (value.startsWith("spotify:", ignoreCase = true)) return value
    val web = Regex("open\\.spotify\\.com/([a-z]+)/([A-Za-z0-9]+)").find(value)
    if (web != null) return "spotify:${web.groupValues[1]}:${web.groupValues[2]}"
    if (value.isNotBlank() && !value.contains(':') && !value.contains('/')) {
        return "spotify:track:$value"
    }
    error("Need a Spotify URI or open.spotify.com URL")
}

internal fun spotifyHomeQueryTokens(query: String): List<String> =
    query.lowercase()
        .split(Regex("[\\s,，、/|+]+"))
        .map { it.trim() }
        .filter { token ->
            when {
                token.isEmpty() -> false
                token.any { it.code > 127 } -> token.length >= 2
                else -> token.length >= 3
            }
        }

internal fun spotifyHomeItemMatches(
    query: String,
    title: String,
    subtitle: String,
    uri: String,
): Boolean {
    val hay = "$title $subtitle $uri".lowercase()
    val needle = query.lowercase().trim()
    if (needle.isNotEmpty() && needle in hay) return true
    val tokens = spotifyHomeQueryTokens(query)
    if (tokens.isEmpty()) return false
    val hits = tokens.count { it in hay }
    if (tokens.size == 1) return hits == 1
    val cjkHit = tokens.filter { token -> token.any { it.code > 127 } }.any { it in hay }
    val longNameHit = tokens.any { it.length >= 8 && it in hay }
    return hits >= 2 || cjkHit || longNameHit
}

internal fun spotifyHomeTypeMatches(qtype: String, uri: String): Boolean {
    val wanted = qtype.lowercase().split(',').map { it.trim() }.filter { it.isNotBlank() }
    if (wanted.isEmpty()) return true
    val kind = uri.substringAfter("spotify:", missingDelimiterValue = "")
        .substringBefore(':')
        .lowercase()
    return wanted.any { type ->
        when (type) {
            "track" -> kind == "track"
            "album" -> kind == "album"
            "artist" -> kind == "artist"
            "playlist" -> kind == "playlist"
            else -> kind == type
        }
    }
}

internal const val SpotifyHomeEmptyNote =
    "App Remote can only match the Spotify home/recommended shelves, not the global catalog. " +
        "Empty items means this query is not on the shelves. Do not open a browser or " +
        "open.spotify.com. If the user has a Spotify URI, call SpotifyPlayback start with it."
