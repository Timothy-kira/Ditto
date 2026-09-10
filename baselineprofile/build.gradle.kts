import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "kira.ditto.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Profile generation needs API 33+ (or root). The physical MNA-AL00 is API 31,
        // so generation runs on the managed emulator below and the resulting profile is
        // consumed on device via ProfileInstaller (API 28+).
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp-atd"
        }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    managedDevices += "api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
