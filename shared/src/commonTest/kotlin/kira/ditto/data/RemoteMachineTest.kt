package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteMachineTest {
    @Test
    fun parsesKimiWebBannerUrl() {
        val parsed = parseRemoteMachineInput("http://192.168.1.8:58627/#token=abc123")
        assertNotNull(parsed)
        assertEquals("http://192.168.1.8:58627", parsed.baseUrl)
        assertEquals("abc123", parsed.token)
        assertEquals("192.168.1.8", parsed.suggestedName)
    }

    @Test
    fun parsesQueryTokenAndWsScheme() {
        val parsed = parseRemoteMachineInput("ws://100.64.0.2:58627/api/v1/ws?token=secret")
        assertNotNull(parsed)
        assertEquals("http://100.64.0.2:58627", parsed.baseUrl)
        assertEquals("secret", parsed.token)
    }

    @Test
    fun parsesOfficialRemoteControlUrl() {
        val url = "https://code-rc.kimi.com/devices/abc123/?rc=1&from=kimi_code_cli"
        assertTrue(isOfficialRemoteControlUrl(url))
        assertEquals(url, extractOfficialRemoteControlUrl("Open $url now"))
        assertNull(extractOfficialRemoteControlUrl("http://192.168.1.8:58627/#token=abc"))
        assertTrue(isLegacyLanKimiWebUrl("http://192.168.1.8:58627/#token=abc"))
        assertFalse(isLegacyLanKimiWebUrl(url))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(parseRemoteMachineInput("   "))
    }

    @Test
    fun websocketUrlUsesSameHost() {
        assertEquals(
            "ws://192.168.1.8:58627/api/v1/ws",
            remoteWebSocketUrl("http://192.168.1.8:58627"),
        )
        assertTrue(isRemoteAgentPath(remoteAgentPath("machine-1")))
        assertEquals("machine-1", parseRemoteMachineIdFromAgentPath("remote://machine-1"))
        assertEquals(
            "C:\\proj",
            parseRemoteCwdFromAgentPath(remoteAgentPath("machine-1", "C:\\proj")),
        )
    }

    @Test
    fun remoteModelsUseComputerCatalogKeys() {
        val options = listOf(
            RemoteKimiModel(
                id = "kimi-code/k3-256k",
                provider = "managed:kimi-code",
                displayName = "K3-256k",
            ),
        ).toProviderModelOptions("pc-1")
        assertEquals("remote:pc-1::kimi-code/k3-256k", options.single().key)
        assertEquals("K3-256k", options.single().chatLabel)
        assertEquals("kimi-code/k3-256k", options.single().modelId)
    }

    @Test
    fun mapsComputerPermissionModesToUi() {
        assertEquals("default", uiPermissionModeFromRemote("manual"))
        assertEquals("auto", uiPermissionModeFromRemote("auto"))
        assertEquals("yolo", uiPermissionModeFromRemote("yolo"))
        assertEquals("plan", uiPermissionModeFromRemote("auto", planMode = true))
        assertEquals("yolo", uiPermissionModeFromRemote("", yolo = true))
        assertEquals("auto", uiPermissionModeFromRemote(""))
        assertEquals("auto", normalizeRemoteUiPermissionMode(null))
    }

    @Test
    fun mapsUiPermissionModesToKimiWeb() {
        assertEquals(RemotePermissionWire("manual", planMode = false, yolo = false), remotePermissionWireFromUi("default"))
        assertEquals(RemotePermissionWire("auto", planMode = false, yolo = false), remotePermissionWireFromUi("auto"))
        assertEquals(RemotePermissionWire("yolo", planMode = false, yolo = true), remotePermissionWireFromUi("yolo"))
        assertEquals(RemotePermissionWire("auto", planMode = true, yolo = false), remotePermissionWireFromUi("plan"))
    }
}
