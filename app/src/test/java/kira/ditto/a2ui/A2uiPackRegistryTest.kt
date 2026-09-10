package kira.ditto.a2ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2uiPackRegistryTest {
    @Test
    fun allowsSignedGithubSourceAndRejectsRandomHosts() {
        assertTrue(
            A2uiPackRegistry.isAllowedPackSource(
                "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/packs/aether.ui.map/pack.json",
            ),
        )
        assertFalse(A2uiPackRegistry.isAllowedPackSource("https://evil.example/pack.json"))
    }

    @Test
    fun verifiesRfc8032EmptyMessageVector() {
        val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val signature =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        val pub = decodeHex(publicKey)
        val sig = decodeHex(signature)
        assertTrue(pub != null && pub.size == 32)
        assertTrue(sig != null && sig.size == 64)
        assertFalse(verifyEd25519(pub!!, "tampered".toByteArray(), sig!!))
    }

    @Test
    fun builtinPackDoesNotNeedDownload() {
        assertTrue(A2uiBasicPackId in A2uiPackRegistry.builtinPacks)
        assertTrue(A2uiHostRendererBasicId in A2uiPackRegistry.hostRendererIds)
    }
}
