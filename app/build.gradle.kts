import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import com.posthog.android.PostHogCliExecTask
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.posthog.android)
    alias(libs.plugins.baselineprofile)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

fun localOrEnv(
    localName: String,
    envName: String,
    defaultValue: String = "",
): String = (System.getenv(envName) ?: localProperties.getProperty(localName, defaultValue)).trim()

val posthogCliHost = localOrEnv(
    localName = "posthog.cliHost",
    envName = "POSTHOG_CLI_HOST",
    defaultValue = localProperties.getProperty("posthog.host", "https://us.posthog.com")
        .replace(".i.posthog.com", ".posthog.com"),
)
val posthogProjectId = localOrEnv("posthog.projectId", "POSTHOG_PROJECT_ID")
val posthogCliApiKey = localOrEnv("posthog.cliApiKey", "POSTHOG_CLI_API_KEY")
val posthogExecutable = localOrEnv("posthog.executable", "POSTHOG_EXECUTABLE")
val nightlyKeystoreFile = localOrEnv("nightly.storeFile", "NIGHTLY_KEYSTORE_FILE")
val nightlyKeystorePassword = localOrEnv("nightly.storePassword", "NIGHTLY_KEYSTORE_PASSWORD")
val nightlyKeyAlias = localOrEnv("nightly.keyAlias", "NIGHTLY_KEY_ALIAS")
val nightlyKeyPassword = localOrEnv("nightly.keyPassword", "NIGHTLY_KEY_PASSWORD")
val appVersionName = providers.gradleProperty("aether.versionName")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "3.0.0-alpha.2"
val piBridgeProjectDir = rootProject.layout.projectDirectory.dir("pi-bridge")
val piBridgeGeneratedAssetsDir = layout.buildDirectory.dir("generated/assets/piBridge")
val preinstalledExtensionsDir = rootProject.layout.projectDirectory.dir("extensions")
val preinstalledExtensionsGeneratedAssetsDir = layout.buildDirectory.dir("generated/assets/preinstalledExtensions")
val piProviderIconsGeneratedResDir = layout.buildDirectory.dir("generated/res/piProviderIcons")
// Make shared Compose resources available to Android resource APIs.
val sharedComposeResourcesDir = rootProject.project(":shared").projectDir.resolve(
    "src/commonMain/composeResources",
)
val sharedComposeResourcesGeneratedResDir = layout.buildDirectory.dir("generated/res/sharedComposeResources")
val piProviderIconFiles = mapOf(
    "provider_amazon_bedrock.png" to "bedrock-color.png",
    "provider_ant_ling.png" to "antgroup-color.png",
    "provider_anthropic.png" to "anthropic.png",
    "provider_azure_openai_responses.png" to "azure-color.png",
    "provider_cerebras.png" to "cerebras-color.png",
    "provider_cloudflare_ai_gateway.png" to "cloudflare-color.png",
    "provider_cloudflare_workers_ai.png" to "workersai-color.png",
    "provider_deepseek.png" to "deepseek-color.png",
    "provider_fireworks.png" to "fireworks-color.png",
    "provider_github_copilot.png" to "githubcopilot.png",
    "provider_google.png" to "google-color.png",
    "provider_google_vertex.png" to "vertexai-color.png",
    "provider_groq.png" to "groq.png",
    "provider_huggingface.png" to "huggingface-color.png",
    "provider_kimi_coding.png" to "moonshot.png",
    "provider_minimax.png" to "minimax-color.png",
    "provider_minimax_cn.png" to "minimax-color.png",
    "provider_mistral.png" to "mistral-color.png",
    "provider_moonshotai.png" to "moonshot.png",
    "provider_moonshotai_cn.png" to "moonshot.png",
    "provider_nvidia.png" to "nvidia-color.png",
    "provider_openai.png" to "openai.png",
    "provider_openai_codex.png" to "codex-color.png",
    "provider_openai_compatible.png" to "openai.png",
    "provider_opencode.png" to "opencode.png",
    "provider_opencode_go.png" to "opencode.png",
    "provider_openrouter.png" to "openrouter.png",
    "provider_together.png" to "together-color.png",
    "provider_vercel_ai_gateway.png" to "vercel.png",
    "provider_xai.png" to "xai.png",
    "provider_xiaomi.png" to "xiaomimimo.png",
    "provider_xiaomi_token_plan_ams.png" to "xiaomimimo.png",
    "provider_xiaomi_token_plan_cn.png" to "xiaomimimo.png",
    "provider_xiaomi_token_plan_sgp.png" to "xiaomimimo.png",
    "provider_zai.png" to "zai.png",
    "provider_zai_coding_cn.png" to "zai.png",
)

fun npmExecutable(): String =
    if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

abstract class SyncGeneratedSourceDirectory : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        into(outputDirectory)
    }
}

abstract class DownloadShizukuApk : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val dest = outputDirectory.get().asFile.resolve("shizuku.apk")
        if (dest.isFile && dest.length() > 1_000_000L) return
        dest.parentFile.mkdirs()
        val urls = listOf(
            "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk",
            "https://ghfast.top/https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk",
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                URI.create(url).toURL().openStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.isFile && dest.length() > 1_000_000L) return
                dest.delete()
            } catch (error: Exception) {
                lastError = error
                dest.delete()
            }
        }
        throw GradleException("Failed to download bundled Shizuku APK: ${lastError?.message}")
    }
}

/**
 * Compiles app AIDL without aidl.exe `-d` depfiles. AGP's built-in AIDL task
 * writes those files in the system ANSI encoding; the Chinese path segment
 * then fails UTF-8 parsing (MalformedInputException). GeckoView AAR aidl
 * dirs are also excluded so they never enter the include path.
 */
abstract class CompileAetherAidl : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val aidlExecutable: RegularFileProperty

    @get:InputFile
    abstract val frameworkAidl: RegularFileProperty

    @get:InputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compile() {
        val out = outputDirectory.get().asFile
        out.mkdirs()
        val source = sourceDirectory.file("kira/ditto/agentmode/IAetherAgentModeService.aidl").get().asFile
        execOperations.exec {
            commandLine(
                aidlExecutable.get().asFile.absolutePath,
                "-p${frameworkAidl.get().asFile.absolutePath}",
                "-o${out.absolutePath}",
                "-I${sourceDirectory.get().asFile.absolutePath}",
                source.absolutePath,
            )
        }
        out.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val cleaned = file.readText(StandardCharsets.ISO_8859_1)
                    .lineSequence()
                    .filterNot { it.contains("Using:") }
                    .joinToString("\n")
                    .trimEnd() + "\n"
                file.writeText(cleaned, StandardCharsets.UTF_8)
            }
    }
}

fun androidSdkDirectory(): File {
    val fromProps = localProperties.getProperty("sdk.dir")?.trim().orEmpty()
    val fromEnv = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")).orEmpty()
    val path = fromProps.ifBlank { fromEnv }
    if (path.isBlank()) throw GradleException("Android SDK directory is not configured.")
    return File(path)
}

fun aidlCompilerFile(): File {
    val sdk = androidSdkDirectory()
    val tools = sdk.resolve("build-tools")
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val binary = if (windows) "aidl.exe" else "aidl"
    val dirs = tools.listFiles()?.filter { it.isDirectory && it.resolve(binary).isFile }.orEmpty()
    val chosen = dirs.firstOrNull { it.name.startsWith("35.") }
        ?: dirs.maxWithOrNull(compareBy { it.name })
        ?: throw GradleException("aidl compiler not found under ${tools.absolutePath}")
    return chosen.resolve(binary)
}

/**
 * The migration tests read every historical schema out of androidTest assets, and those schemas sit
 * in two directories for a historical reason: versions 1-3 were exported while the database still
 * lived in :app, everything from 4 on by :shared after it moved. Merging them into one directory is
 * what lets a test open a database at version 1 and walk it all the way forward. The overlapping
 * versions are byte-identical, and :shared is copied last so it wins if that ever stops being true.
 */
val roomSchemaAssetsDir = layout.buildDirectory.dir("roomSchemaAssets")

val mergedRoomSchemas = tasks.register<Sync>("mergeRoomSchemaAssets") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from("$projectDir/schemas")
    from("${rootProject.projectDir}/shared/schemas")
    into(roomSchemaAssetsDir)
}

// srcDir records where to look; it does not make anything appear there. Without this the asset
// merge simply finds an empty directory and every migration test fails on a missing schema file -
// which is exactly how they failed before, silently, for as long as androidTest did not compile.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(mergedRoomSchemas) }

android {
    namespace = "kira.ditto"
    compileSdk = 36
    ndkVersion = (project.findProperty("ndkVersion") as? String) ?: "28.2.13676358"

    defaultConfig {
        applicationId = "com.kira.ditto"
        minSdk = 26
        // Alpine/Termux-style local runtimes install executable ELF files into app-private
        // storage. Android blocks execve() from that location for targetSdk >= 29.
        targetSdk = 28
        versionCode = 12
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val huaweiAppId = localOrEnv("huawei.appId", "HUAWEI_APP_ID")
        val gmailOAuthClientId = localOrEnv("gmail.oauth.clientId", "GMAIL_OAUTH_CLIENT_ID")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val gmailOAuthClientSecret = localOrEnv("gmail.oauth.clientSecret", "GMAIL_OAUTH_CLIENT_SECRET")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val spotifyOAuthClientId = localOrEnv("spotify.oauth.clientId", "SPOTIFY_OAUTH_CLIENT_ID")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val spotifyOAuthClientSecret = localOrEnv("spotify.oauth.clientSecret", "SPOTIFY_OAUTH_CLIENT_SECRET")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "POSTHOG_API_KEY", "\"${localProperties.getProperty("posthog.apiKey", "")}\"")
        buildConfigField("String", "POSTHOG_HOST", "\"${localProperties.getProperty("posthog.host", "https://us.i.posthog.com")}\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
        buildConfigField("String", "HUAWEI_APP_ID", "\"$huaweiAppId\"")
        buildConfigField("String", "GMAIL_OAUTH_CLIENT_ID", "\"$gmailOAuthClientId\"")
        buildConfigField("String", "GMAIL_OAUTH_CLIENT_SECRET", "\"$gmailOAuthClientSecret\"")
        buildConfigField("String", "SPOTIFY_OAUTH_CLIENT_ID", "\"$spotifyOAuthClientId\"")
        buildConfigField("String", "SPOTIFY_OAUTH_CLIENT_SECRET", "\"$spotifyOAuthClientSecret\"")
        manifestPlaceholders["huaweiAppId"] = if (huaweiAppId.isBlank()) {
            "appid=000000000"
        } else {
            "appid=$huaweiAppId"
        }
        manifestPlaceholders["redirectSchemeName"] = "com.kira.ditto"
        manifestPlaceholders["redirectHostName"] = "spotify-auth"
    }

    signingConfigs {
        create("nightly") {
            if (
                nightlyKeystoreFile.isNotBlank() &&
                nightlyKeystorePassword.isNotBlank() &&
                nightlyKeyAlias.isNotBlank() &&
                nightlyKeyPassword.isNotBlank()
            ) {
                storeFile = file(nightlyKeystoreFile)
                storePassword = nightlyKeystorePassword
                keyAlias = nightlyKeyAlias
                keyPassword = nightlyKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }

        create("nightly") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".nightly"
            matchingFallbacks += listOf("debug")
            resValue("string", "nightly_app_name", "Ditto Nightly")
            buildConfigField("String", "UPDATE_CHANNEL", "\"nightly\"")
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_nightly"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_nightly_round"
            manifestPlaceholders["appLabel"] = "@string/nightly_app_name"
            signingConfig = if (nightlyKeystoreFile.isNotBlank()) {
                signingConfigs.getByName("nightly")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // Same R8 pipeline as release, but locally installable and attachable by
        // profilers/macrobenchmark. This is the variant to use for perf measurements;
        // debug builds are too far from shipped behaviour to be meaningful.
        create("profileable") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profileable"
            matchingFallbacks += listOf("release")
            isProfileable = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "profileable_app_name", "Ditto Perf")
            manifestPlaceholders["appLabel"] = "@string/profileable_app_name"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = false
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    androidResources {
        // The packed embedding artifact is 144 MB of int8 weights that do not compress, and it is
        // read straight off the asset stream at install time. Storing it uncompressed keeps the
        // install from paying an inflate pass over the whole thing.
        noCompress += "bin"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    lint {
        // targetSdk is intentionally capped at API 28 for local runtime execution.
        disable += "ExpiredTargetSdkVersion"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir(roomSchemaAssetsDir)
    }
}


composeCompiler {
    // Strong skipping (on by default since Kotlin 2.0.20) already memoizes the lambdas
    // the wide screens receive. What it cannot do is compare their collection
    // parameters by value, which is what the stability file below adds.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose-stability.conf"),
    )
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

baselineProfile {
    // Regeneration is an explicit, opt-in step; day-to-day builds must not spin up an
    // emulator. The checked-in profile under src/main/generated is what ships.
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":terminal-view"))
    implementation(libs.kotlinx.serialization.json)
    // Image results in the browser's own search page load remotely.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.google.material)
    implementation(libs.squareup.okhttp)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation(libs.flexmark.html2md.converter)
    implementation(libs.snakeyaml)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.android.app.process)
    implementation(libs.posthog.android)
    implementation(libs.sora.editor)
    implementation("com.huawei.hms:hwid:6.12.0.300")
    implementation("com.huawei.hms:health:6.11.0.300")
    implementation("com.huawei.hms:ml-computer-voice-asr:3.12.0.301")
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.spotify.android:auth:2.1.1")
    implementation(libs.chrisbanes.haze)
    // GeckoView is only constructed on first BrowserActivity / webmcp call.
    implementation(libs.mozilla.concept.engine)
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.browser.state)
    implementation(libs.mozilla.feature.session)
    implementation(libs.mozilla.feature.tabs)
    implementation(libs.mozilla.support.ktx)
    implementation(libs.mozilla.lib.state)
    compileOnly("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.squareup.okhttp.mockwebserver)
    testImplementation(libs.json)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    // ChatRepositoryCheckpointInstrumentedTest drives the database through BundledSQLiteDriver;
    // without this the whole androidTest source set fails to compile, taking the migration tests
    // with it - which is why 9->10 shipped last round with only its static schema check.
    androidTestImplementation(libs.androidx.sqlite.bundled)
    // Bridges a driver-based SQLiteConnection back to SupportSQLiteDatabase, so the migration tests
    // can run on the same bundled SQLite the app ships while keeping their existing query style.
    androidTestImplementation(libs.androidx.room.sqlite.wrapper)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    baselineProfile(project(":baselineprofile"))
}

tasks.withType<PostHogCliExecTask>().configureEach {
    onlyIf {
        posthogProjectId.isNotBlank() && posthogCliApiKey.isNotBlank()
    }
    postHogHost.set(posthogCliHost)
    if (posthogProjectId.isNotBlank()) {
        postHogProjectId.set(posthogProjectId)
    }
    if (posthogCliApiKey.isNotBlank()) {
        postHogApiKey.set(posthogCliApiKey)
    }
    if (posthogExecutable.isNotBlank()) {
        postHogExecutable.set(posthogExecutable)
    }
}

val copySharedComposeResources = tasks.register<SyncGeneratedSourceDirectory>("copySharedComposeResources") {
    outputDirectory.set(sharedComposeResourcesGeneratedResDir)
    from(sharedComposeResourcesDir) {
        include("values*/**")
    }
    // Escape apostrophes in shared i18n strings for Android's resource parser.
    filter { line ->
        if (line.trimStart().startsWith("<string ")) {
            line.replace("'", "\\'").replace("&apos;", "\\'")
        } else {
            line
        }
    }
    includeEmptyDirs = false
}

val copyProviderIcons = tasks.register<SyncGeneratedSourceDirectory>("copyProviderIcons") {
    outputDirectory.set(piProviderIconsGeneratedResDir)
    from(sharedComposeResourcesDir.resolve("drawable")) {
        include("provider_*.png")
        into("drawable-nodpi")
    }
    includeEmptyDirs = false
}

val downloadShizukuApk = tasks.register<DownloadShizukuApk>("downloadShizukuApk") {
    outputDirectory.set(layout.buildDirectory.dir("generated/assets/shizuku"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            copySharedComposeResources,
            SyncGeneratedSourceDirectory::outputDirectory,
        )
        variant.sources.res?.addGeneratedSourceDirectory(
            copyProviderIcons,
            SyncGeneratedSourceDirectory::outputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadShizukuApk,
            DownloadShizukuApk::outputDirectory,
        )
        val capName = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        val compileAidl = tasks.register<CompileAetherAidl>("compile${capName}AetherAidl") {
            aidlExecutable.set(aidlCompilerFile())
            frameworkAidl.set(androidSdkDirectory().resolve("platforms/android-36/framework.aidl"))
            sourceDirectory.set(layout.projectDirectory.dir("src/main/aidl"))
            outputDirectory.set(
                layout.buildDirectory.dir("generated/aidl_source_output_dir/${variant.name}/out"),
            )
        }
        variant.sources.java?.addGeneratedSourceDirectory(
            compileAidl,
            CompileAetherAidl::outputDirectory,
        )
    }
}
