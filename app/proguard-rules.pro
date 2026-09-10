# Project-specific ProGuard rules can be added here when needed.

-keep class kira.ditto.audio.OboeMicCapture { *; }

# com.rosan.app_process intentionally references hidden Android framework
# classes that are present on-device but unavailable to R8's android.jar.
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.LoadedApk

-keep class com.huawei.** { *; }
-dontwarn com.huawei.**

# Flexmark's BitFieldSet reflects enum constant names at runtime.
# Keep its enum members stable in release builds.
-keepclassmembers enum com.vladsch.flexmark.** {
    *;
}

# --- Shizuku user service ---------------------------------------------------
# The service class is instantiated by name from the Shizuku server process and
# its AIDL stub is bound reflectively, so neither may be renamed or stripped.
-keep class kira.ditto.agentmode.AetherAgentModeShizukuService { *; }
-keep interface kira.ditto.agentmode.IAetherAgentModeService { *; }
-keep class kira.ditto.agentmode.IAetherAgentModeService$* { *; }
-keep class * implements android.os.IInterface { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class com.rosan.app_process.** { *; }
-dontwarn com.rosan.app_process.**

# Hidden-API surfaces reached via reflection for UiAutomation / ASR probing.
-dontwarn android.app.UiAutomation*
-dontwarn android.hardware.input.InputManager*
-dontwarn android.view.IWindowManager*

# --- kotlinx.serialization --------------------------------------------------
# @Serializable companions and generated serializers are looked up reflectively.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kira.ditto.**$$serializer { *; }
-keepclassmembers class kira.ditto.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class kira.ditto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room -------------------------------------------------------------------
# Entities/DAOs are wired by generated code that resolves them by name.
-keep class kira.ditto.data.chatdb.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- Sora editor ------------------------------------------------------------
# Language definitions and TextMate grammars are loaded reflectively from assets.
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**

# --- Reflection-heavy third parties ----------------------------------------
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
-keep class org.jsoup.** { *; }

# OkHttp/Okio ship consumer rules but still reference optional JVM-only APIs.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Compose keeps its own rules; this only silences the desktop-only tooling refs.
-dontwarn androidx.compose.ui.tooling.**

# Mozilla GeckoView / Android Components
-keep class org.mozilla.geckoview.** { *; }
-keep class mozilla.components.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn mozilla.components.**
-dontwarn mozilla.telemetry.**
-dontwarn mozilla.appservices.**

# WebMcpHost brings the agent's tab to the foreground through GeckoEngineSession.geckoSession.
# That member is `internal` in mozilla-components, so it is reached reflectively by name. Without
# these rules a minified build silently stops activating tabs, and JavaScript-rendered pages never
# mount — snapshots come back with dom=0 while readyState is complete.
-keepclassmembers class mozilla.components.browser.engine.gecko.GeckoEngineSession {
    org.mozilla.geckoview.GeckoSession geckoSession;
    org.mozilla.geckoview.GeckoSession getGeckoSession*();
}
-keepclassmembers class org.mozilla.geckoview.GeckoSession {
    public void setActive(boolean);
}
