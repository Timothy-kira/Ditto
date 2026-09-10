package kira.ditto.agentmode

import android.os.Process
import android.system.Os

internal object AgentModeVirtualDisplayPolicy {
    const val RootUid = 0
    const val SystemUid = 1000
    const val ShellUid = 2000
    const val ShellPackageName = "com.android.shell"
    const val SystemPackageName = "android"
    const val FlagPublic = 1 shl 0
    const val FlagPresentation = 1 shl 1
    const val FlagOwnContentOnly = 1 shl 3
    const val FlagSupportsTouch = 1 shl 6
    const val FlagDestroyContentOnRemoval = 1 shl 8
    const val FlagShouldShowSystemDecorations = 1 shl 9
    const val FlagTrusted = 1 shl 10
    const val FlagAlwaysUnlocked = 1 shl 11
    const val FlagTouchFeedbackDisabled = 1 shl 13
    const val FlagOwnFocus = 1 shl 14
    const val FlagStealTopFocusDisabled = 1 shl 16
    const val RequestedRefreshHz = 60f

    fun flags(trusted: Boolean): Int {
        val flags =
            FlagPublic or
                FlagPresentation or
                FlagOwnContentOnly or
                FlagSupportsTouch or
                FlagDestroyContentOnRemoval or
                FlagShouldShowSystemDecorations or
                FlagTouchFeedbackDisabled or
                FlagOwnFocus
        return if (trusted) flags or FlagTrusted else flags
    }

    fun packageNameForUid(uid: Int, defaultPackageName: String): String =
        when (uid) {
            RootUid, ShellUid -> ShellPackageName
            SystemUid -> SystemPackageName
            else -> defaultPackageName
        }

    @Suppress("DEPRECATION")
    fun dropRootToShellIfNeeded(): Boolean {
        if (Process.myUid() != RootUid) return false
        return runCatching {
            Os.setgid(ShellUid)
            Os.setuid(ShellUid)
            Process.myUid() == ShellUid
        }.getOrDefault(false)
    }

    fun shouldProtectPackage(
        packageName: String,
        hostPackage: String,
    ): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg == hostPackage) return true
        if (pkg == SystemPackageName || pkg == ShellPackageName) return true
        if (pkg == "com.android.systemui") return true
        if (pkg.startsWith("moe.shizuku")) return true
        return false
    }

    fun packageFromTaskName(raw: String): String {
        val component = raw.trim().substringBefore(' ')
        if (component.isEmpty()) return ""
        return component.substringBefore('/').substringBefore(':')
    }
}
