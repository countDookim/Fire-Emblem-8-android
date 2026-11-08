// Thin Android shell: Activity, GL surface, AudioTrack, controller + on-screen GBA button input sources, Compose menus.
// All game logic lives in the pure-JVM modules. Only built where an Android SDK exists (CI).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// No APK bundles game data, debug builds included: players import their own ROM (decision 0011,
// guarded by tools/check_no_game_content.py).

android {
    namespace = "io.github.countdookim.fe8"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.countdookim.fe8"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
    }
    signingConfigs {
        // Committed debug keystore (private repo): every CI APK has the same signature, so the owner can
        // install each new `latest` APK over the previous one without uninstalling (keeps saves).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":game"))
    implementation(project(":engine:extract"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
}
