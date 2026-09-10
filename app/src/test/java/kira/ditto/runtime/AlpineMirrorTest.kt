package kira.ditto.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineMirrorTest {
    @Test
    fun keepsOfficialRepositoriesAsFallbackForChinaMirror() {
        val repositories = """
            https://dl-cdn.alpinelinux.org/alpine/v3.23/main
            https://dl-cdn.alpinelinux.org/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            chinaApkRepositories(repositories),
        )
    }

    @Test
    fun repairsLegacyMirrorOnlyRepositories() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            chinaApkRepositories(repositories),
        )
    }

    @Test
    fun usesOnlyOfficialRepositoriesForInternationalNetwork() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://dl-cdn.alpinelinux.org/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
            https://dl-cdn.alpinelinux.org/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            apkRepositories(repositories, ApkNetworkEnvironment.International),
        )
    }

    @Test
    fun usesBothSourcesWhenIpDetectionIsUnavailable() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
            """.trimIndent(),
            apkRepositories(repositories, ApkNetworkEnvironment.Unknown),
        )
    }

    @Test
    fun parsesCountryCodeFromCloudflareTrace() {
        assertEquals("CN", parseCloudflareCountryCode("ip=203.0.113.1\nloc=cn\ntls=TLSv1.3\n"))
        assertEquals(null, parseCloudflareCountryCode("ip=203.0.113.1\n"))
    }

    @Test
    fun nodeHeapScalesWithDeviceRam() {
        assertEquals(384, resolveKimiNodeHeapMb(4L * 1024 * 1024 * 1024))
        assertEquals(512, resolveKimiNodeHeapMb(8L * 1024 * 1024 * 1024))
        assertEquals(768, resolveKimiNodeHeapMb(12L * 1024 * 1024 * 1024))
        assertEquals(768, resolveKimiNodeHeapMb(16L * 1024 * 1024 * 1024))
    }

    @Test
    fun alpineHostAbiFolderPrefersPrimaryX86OnEmulatorsWithArmTranslation() {
        assertEquals(
            "x86_64",
            alpineHostAbiFolder(arrayOf("x86_64", "arm64-v8a")),
        )
        assertEquals(
            "x86_64",
            alpineHostAbiFolder(arrayOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "x86_64",
            alpineHostAbiFolder(arrayOf("x86", "x86_64")),
        )
    }

    @Test
    fun alpineHostAbiFolderKeepsArm64PhonesOnArmAssets() {
        assertEquals("arm64-v8a", alpineHostAbiFolder(arrayOf("arm64-v8a")))
        assertEquals(
            "arm64-v8a",
            alpineHostAbiFolder(arrayOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals("", alpineHostAbiFolder(arrayOf("armeabi-v7a")))
        assertEquals("", alpineHostAbiFolder(emptyArray()))
    }

    @Test
    fun reusesBoundKimiSessionWhenFingerprintMatches() {
        assertTrue(
            canBoundReuseKimiSession(
                canReuse = true,
                needsNewSession = false,
                isBound = true,
            ),
        )
        assertFalse(
            canBoundReuseKimiSession(
                canReuse = true,
                needsNewSession = false,
                isBound = false,
            ),
        )
        assertFalse(
            canBoundReuseKimiSession(
                canReuse = true,
                needsNewSession = true,
                isBound = true,
            ),
        )
    }

    @Test
    fun restoresMappedSessionAfterProcessRestartInsteadOfCreatingNew() {
        assertEquals(
            KimiSessionRestoreKind.BoundReuse,
            kimiSessionRestoreKind(
                canReuse = true,
                needsNewSession = false,
                isBound = true,
            ),
        )
        assertEquals(
            KimiSessionRestoreKind.LoadOrResume,
            kimiSessionRestoreKind(
                canReuse = true,
                needsNewSession = true,
                isBound = false,
            ),
        )
        assertEquals(
            KimiSessionRestoreKind.LoadOrResume,
            kimiSessionRestoreKind(
                canReuse = true,
                needsNewSession = false,
                isBound = false,
            ),
        )
        assertEquals(
            KimiSessionRestoreKind.New,
            kimiSessionRestoreKind(
                canReuse = false,
                needsNewSession = true,
                isBound = false,
            ),
        )
    }

    @Test
    fun unknownSessionErrorMatchesAcpInvalidParams() {
        assertTrue(
            isUnknownKimiSessionError(
                IllegalStateException(
                    "Invalid params: Unknown sessionId: session_c9ae4f28-ddc1-4527-ab4f-b29304976d6a",
                ),
            ),
        )
        assertFalse(isUnknownKimiSessionError(IllegalStateException("Kimi ACP request timed out")))
    }

    @Test
    fun alpineGuestShellUsesPlainSh() {
        assertEquals(
            listOf("/bin/sh", "-lc", "uname -m"),
            alpineGuestShellArgs("uname -m", interactive = false),
        )
        assertEquals(
            listOf("/bin/sh", "-i"),
            alpineGuestShellArgs(command = null, interactive = true),
        )
    }
}
