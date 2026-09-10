package kira.ditto.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kira.ditto.R
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherTheme

internal class SpotifyConnectActivity : AppCompatActivity() {
    private var settled = false
    private var connecting = false
    private var awaitingLogin = false
    private var didAskLogin = false
    private var status by mutableStateOf("")

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        awaitingLogin = false
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        Log.i(Tag, "Spotify login result type=${response.type} error=${response.error}")
        when (response.type) {
            AuthorizationResponse.Type.TOKEN,
            AuthorizationResponse.Type.CODE -> connectRemote(showAuth = false)
            AuthorizationResponse.Type.ERROR ->
                fail(IllegalStateException(response.error.ifBlank { "Spotify 授权失败" }))
            else -> fail(IllegalStateException(getString(R.string.settings_spotify_mcp_cancelled)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = getString(R.string.settings_spotify_mcp_connecting)
        setContent {
            AetherTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AetherSurface)
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = AetherPrimary,
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(text = status, color = AetherOnSurface)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = getString(R.string.settings_spotify_mcp_connecting_hint),
                        color = AetherOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    TextButton(onClick = ::cancelConnect) {
                        Text(
                            text = getString(R.string.settings_spotify_mcp_cancel),
                            color = AetherOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!settled && !awaitingLogin && !connecting) {
            connectRemote(showAuth = false)
        }
    }

    private fun connectRemote(showAuth: Boolean) {
        if (settled || connecting || awaitingLogin) return
        val clientId = SpotifyAuth.bundledClientId()
        if (clientId.isBlank()) {
            fail(IllegalStateException("Spotify Client ID 未配置。"))
            return
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            fail(IllegalStateException("没有找到 Spotify 应用。"))
            return
        }
        connecting = true
        status = getString(R.string.settings_spotify_mcp_connecting)
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(SpotifyAppRemoteClient.RedirectUri)
            .showAuthView(showAuth)
            .build()
        Log.i(Tag, "App Remote connect showAuth=$showAuth")
        SpotifyAppRemote.connect(
            applicationContext,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                    Log.i(Tag, "App Remote connected")
                    connecting = false
                    settle(spotifyAppRemote, null)
                }

                override fun onFailure(throwable: Throwable) {
                    connecting = false
                    Log.e(Tag, "App Remote failed: ${throwable.javaClass.name} ${throwable.message}", throwable)
                    when (throwable) {
                        is UserNotAuthorizedException -> startLogin()
                        is NotLoggedInException ->
                            fail(IllegalStateException("请先在 Spotify 应用里登录账号。"))
                        is CouldNotFindSpotifyApp ->
                            fail(IllegalStateException("没有找到 Spotify 应用。"))
                        is AuthenticationFailedException ->
                            fail(IllegalStateException("Spotify 校验失败。请确认 Dashboard 里的包名和 SHA1。"))
                        else -> fail(throwable)
                    }
                }
            },
        )
    }

    private fun startLogin() {
        if (settled || awaitingLogin) return
        if (didAskLogin) {
            fail(IllegalStateException("Spotify 未授权控制播放。请在弹出的页面里允许后重试。"))
            return
        }
        didAskLogin = true
        awaitingLogin = true
        status = getString(R.string.settings_spotify_mcp_waiting_auth)
        val clientId = SpotifyAuth.bundledClientId()
        val request = AuthorizationRequest.Builder(
            clientId,
            AuthorizationResponse.Type.TOKEN,
            SpotifyAppRemoteClient.RedirectUri,
        )
            .setScopes(
                arrayOf(
                    "app-remote-control",
                    "user-read-playback-state",
                    "user-modify-playback-state",
                    "user-library-read",
                    "user-library-modify",
                ),
            )
            .build()
        loginLauncher.launch(AuthorizationClient.createLoginActivityIntent(this, request))
    }

    private fun cancelConnect() {
        fail(IllegalStateException(getString(R.string.settings_spotify_mcp_cancelled)))
    }

    private fun fail(error: Throwable) {
        settle(null, error)
    }

    private fun settle(remote: SpotifyAppRemote?, error: Throwable?) {
        if (settled) return
        settled = true
        SpotifyAppRemoteClient.completeConnect(remote, error)
        finish()
    }

    companion object {
        private const val Tag = "SpotifyMCP"

        fun launch(context: Context) {
            val intent = Intent(context, SpotifyConnectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
