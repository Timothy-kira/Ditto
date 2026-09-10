package kira.ditto.data.pi

import kira.ditto.runtime.MultiplatformLocalRuntime

internal actual fun createPlatformBrowserBackend(
    runtime: MultiplatformLocalRuntime,
): SharedBrowserBackend? = null
