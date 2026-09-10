package kira.ditto.data

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import java.lang.ref.WeakReference

fun interface HuaweiHealthAuthHost {
    fun requestAuthorization(intent: Intent, onResult: (ActivityResult) -> Unit)
}

object HuaweiHealthAuthBridge {
    @Volatile
    var host: HuaweiHealthAuthHost? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun attach(activity: Activity, host: HuaweiHealthAuthHost) {
        this.host = host
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            if (host === activity) host = null
        }
    }

    fun activity(): Activity? = activityRef?.get()
}
