package kira.ditto.agentmode

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference

/**
 * Keep the host Aether window from becoming an IME target while the virtual
 * phone has focus. Taps on a VD EditText otherwise pop the keyboard inside
 * Aether on Huawei. Chat composer focus temporarily lifts the isolation.
 */
object AgentModeImeGuard {
    private var hostActivity: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        hostActivity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (hostActivity?.get() === activity) {
            hostActivity = null
        }
    }

    fun apply(context: Context, isolateIme: Boolean) {
        val activity = resolveActivity(context) ?: return
        val window = activity.window ?: return
        if (isolateIme) {
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
            hide(activity)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun hide(context: Context) {
        val activity = resolveActivity(context) ?: return
        val token = activity.window?.decorView?.windowToken ?: return
        val imm = activity.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(token, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.window?.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        }
    }

    private fun resolveActivity(context: Context): Activity? =
        context.findActivity() ?: hostActivity?.get()
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
