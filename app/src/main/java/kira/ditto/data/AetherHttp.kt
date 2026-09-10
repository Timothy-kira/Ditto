package kira.ditto.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A single connection pool, dispatcher and route database for the whole app.
 *
 * Subsystems used to each construct their own [OkHttpClient], which meant several
 * independent thread pools and connection pools were stood up during startup even
 * though most of them never issue a request. Deriving with [OkHttpClient.newBuilder]
 * keeps per-caller timeouts while sharing all of that state.
 */
object AetherHttp {
    val shared: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun derive(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
        shared.newBuilder().apply(configure).build()
}
