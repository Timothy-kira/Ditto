package kira.ditto

import android.app.Application
import kira.ditto.runtime.AlpineRuntime
import kira.ditto.runtime.AndroidAlpineFileManagerRuntime

class KimiApplication : Application() {
    val runtime: AndroidAlpineFileManagerRuntime by lazy {
        AndroidAlpineFileManagerRuntime(AlpineRuntime(this))
    }
}
