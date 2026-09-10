package kira.ditto.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeDisplaySpecTest {
    @Test
    fun already1080pKeepsNativeSizeAndDpi() {
        val spec = scaleAgentModeDisplaySpec(1080, 2400, 480)
        assertEquals(1080, spec.width)
        assertEquals(2400, spec.height)
        assertEquals(480, spec.densityDpi)
    }

    @Test
    fun tallerThan1080ScalesShortEdgeAndDpi() {
        val spec = scaleAgentModeDisplaySpec(1220, 2712, 480)
        assertEquals(1080, spec.width)
        assertEquals(2400, spec.height)
        assertEquals(425, spec.densityDpi)
    }

    @Test
    fun narrowerThan1080DoesNotUpscale() {
        val spec = scaleAgentModeDisplaySpec(720, 1600, 320)
        assertEquals(720, spec.width)
        assertEquals(1600, spec.height)
        assertEquals(320, spec.densityDpi)
    }

    @Test
    fun qhdScalesTo1080p() {
        val spec = scaleAgentModeDisplaySpec(1440, 3200, 640)
        assertEquals(1080, spec.width)
        assertEquals(2400, spec.height)
        assertEquals(480, spec.densityDpi)
    }

    @Test
    fun landscapeKeepsOrientation() {
        val spec = scaleAgentModeDisplaySpec(2712, 1220, 480)
        assertEquals(2400, spec.width)
        assertEquals(1080, spec.height)
        assertEquals(425, spec.densityDpi)
    }

    @Test
    fun oddPhysicalSizeBecomesEven() {
        val spec = scaleAgentModeDisplaySpec(1081, 2341, 400)
        assertEquals(1080, spec.width)
        assertEquals(0, spec.width % 2)
        assertEquals(0, spec.height % 2)
    }
}

class AgentModeVirtualDisplayPolicyTest {
    @Test
    fun flagsKeepIsolationAndDropStealTopFocus() {
        val trusted = AgentModeVirtualDisplayPolicy.flags(trusted = true)
        val untrusted = AgentModeVirtualDisplayPolicy.flags(trusted = false)
        assertEquals(0, trusted and AgentModeVirtualDisplayPolicy.FlagStealTopFocusDisabled)
        assertEquals(
            AgentModeVirtualDisplayPolicy.FlagOwnContentOnly,
            trusted and AgentModeVirtualDisplayPolicy.FlagOwnContentOnly,
        )
        assertEquals(
            0,
            trusted and AgentModeVirtualDisplayPolicy.FlagAlwaysUnlocked,
        )
        assertEquals(
            AgentModeVirtualDisplayPolicy.FlagOwnFocus,
            trusted and AgentModeVirtualDisplayPolicy.FlagOwnFocus,
        )
        assertEquals(
            AgentModeVirtualDisplayPolicy.FlagTrusted,
            trusted and AgentModeVirtualDisplayPolicy.FlagTrusted,
        )
        assertEquals(0, untrusted and AgentModeVirtualDisplayPolicy.FlagTrusted)
        assertEquals(60f, AgentModeVirtualDisplayPolicy.RequestedRefreshHz, 0.01f)
    }

    @Test
    fun packageFromTaskNameHandlesProcessSuffix() {
        assertEquals(
            "com.tencent.mm",
            AgentModeVirtualDisplayPolicy.packageFromTaskName(
                "com.tencent.mm:tools/com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            ),
        )
        assertEquals(
            "com.eg.android.AlipayGphone",
            AgentModeVirtualDisplayPolicy.packageFromTaskName(
                "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.LauncherActivity",
            ),
        )
        assertEquals("", AgentModeVirtualDisplayPolicy.packageFromTaskName("   "))
    }

    @Test
    fun packageNameForUidMapsRootAndShellToShellPackage() {
        assertEquals(
            "com.android.shell",
            AgentModeVirtualDisplayPolicy.packageNameForUid(
                AgentModeVirtualDisplayPolicy.RootUid,
                "com.kira.ditto",
            ),
        )
        assertEquals(
            "com.android.shell",
            AgentModeVirtualDisplayPolicy.packageNameForUid(
                AgentModeVirtualDisplayPolicy.ShellUid,
                "com.kira.ditto",
            ),
        )
        assertEquals(
            "android",
            AgentModeVirtualDisplayPolicy.packageNameForUid(
                AgentModeVirtualDisplayPolicy.SystemUid,
                "com.kira.ditto",
            ),
        )
        assertEquals(
            "com.kira.ditto",
            AgentModeVirtualDisplayPolicy.packageNameForUid(10184, "com.kira.ditto"),
        )
    }

    @Test
    fun hostAndSystemPackagesAreProtected() {
        assertTrue(
            AgentModeVirtualDisplayPolicy.shouldProtectPackage("com.kira.ditto", "com.kira.ditto"),
        )
        assertTrue(
            AgentModeVirtualDisplayPolicy.shouldProtectPackage(
                "moe.shizuku.privileged.api",
                "com.kira.ditto",
            ),
        )
        assertTrue(
            AgentModeVirtualDisplayPolicy.shouldProtectPackage("com.android.systemui", "com.kira.ditto"),
        )
        assertFalse(
            AgentModeVirtualDisplayPolicy.shouldProtectPackage("com.tencent.mm", "com.kira.ditto"),
        )
    }
}
