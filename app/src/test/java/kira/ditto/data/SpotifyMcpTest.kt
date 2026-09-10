package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMcpTest {
    @Test
    fun acpServerIsLoopbackNamedSpotify() {
        val server = SpotifyMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("spotify", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-spotify"))
    }

    @Test
    fun listToolsMatchAppRemoteSurface() {
        val tools = SpotifyMcp.listToolsResult().getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(SpotifyMcp.ToolNames, names)
        assertEquals(6, names.size)
        assertTrue(names.contains("SpotifyLibrary"))
        val playback = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "SpotifyPlayback" }
            .getString("description")
        assertTrue(playback.contains("App Remote"))
        assertTrue(playback.contains("open"))
        val search = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "SpotifySearch" }
            .getString("description")
        assertTrue(search.contains("home"))
        assertTrue(search.contains("NOT a global catalog"))
        val playlist = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "SpotifyPlaylist" }
            .getString("description")
        assertTrue(playlist.contains("Cannot create"))
        val library = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "SpotifyLibrary" }
            .getString("description")
        assertTrue(library.contains("UserApi"))
    }

    @Test
    fun matchesPrefixedToolNames() {
        assertTrue(SpotifyMcp.matchesToolName("SpotifyPlayback"))
        assertTrue(SpotifyMcp.matchesToolName("mcp__spotify__SpotifySearch"))
        assertTrue(SpotifyMcp.matchesToolName("mcp__spotify__SpotifyLibrary"))
        assertEquals("SpotifyQueue", SpotifyMcp.canonicalToolName("mcp__spotify__SpotifyQueue"))
        assertEquals("SpotifyLibrary", SpotifyMcp.canonicalToolName("SpotifyLibrary"))
        assertFalse(SpotifyMcp.matchesToolName("search_threads"))
        assertFalse(SpotifyMcp.matchesToolName("tabs_navigate"))
    }

    @Test
    fun playUriAcceptsSpotifyAndWeb() {
        assertEquals("spotify:track:abc", spotifyPlayUri("spotify:track:abc"))
        assertEquals(
            "spotify:track:4qmFC3Jz5aQ0erlk2OSi2X",
            spotifyPlayUri("https://open.spotify.com/track/4qmFC3Jz5aQ0erlk2OSi2X"),
        )
        assertEquals("spotify:track:4qmFC3Jz5aQ0erlk2OSi2X", spotifyPlayUri("4qmFC3Jz5aQ0erlk2OSi2X"))
    }

    @Test
    fun homeSearchMatchesTokensAndCjk() {
        assertTrue(
            spotifyHomeItemMatches("七朵组合", "七朵组合 电台", "蒋雪儿", "spotify:playlist:x"),
        )
        assertTrue(
            spotifyHomeItemMatches("Ryuichi Sakamoto 坂本龙一", "This is Sakamoto", "", "spotify:artist:x"),
        )
        assertTrue(
            spotifyHomeItemMatches("坂本龙一", "Ryuichi Sakamoto", "坂本龙一", "spotify:artist:x"),
        )
        assertFalse(
            spotifyHomeItemMatches("Energy Flow", "Energy Booster: Pop", "Taylor Swift", "spotify:playlist:x"),
        )
        assertTrue(spotifyHomeTypeMatches("playlist", "spotify:playlist:abc"))
        assertFalse(spotifyHomeTypeMatches("track", "spotify:playlist:abc"))
        assertTrue(spotifyHomeTypeMatches("", "spotify:artist:abc"))
    }
}
