pluginManagement {
    // No Gradle plugin portal: its artifact CDN is blocked in cloud sessions. Every plugin id is mapped to
    // its implementation module on Google Maven / Maven Central instead (versions from the catalog).
    repositories {
        // Cloud sessions set FE8_MAVEN_MIRROR (tools/maven_mirror.py: retries the proxy's spurious 404/429s);
        // they never build :app, so only Maven Central (+ AndroidX/AGP groups, unused there) go via the mirror.
        // CI never sets it and uses the standard repositories.
        val mirror = System.getenv("FE8_MAVEN_MIRROR")
        if (mirror.isNullOrBlank()) {
            google()
            mavenCentral()
        } else {
            maven("$mirror/central") { isAllowInsecureProtocol = true }
            maven("$mirror/google") { isAllowInsecureProtocol = true; content { includeGroupByRegex("androidx\\..*|com\\.android(\\..*)?") } }
        }
    }
    val versions = file("gradle/libs.versions.toml").readLines()
        .mapNotNull { Regex("""^([\w-]+)\s*=\s*"([^"]+)"""").find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
    resolutionStrategy {
        eachPlugin {
            val id = requested.id.id
            when {
                id == "org.jetbrains.kotlin.plugin.compose" -> useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${versions["kotlin"]}")
                id == "org.jetbrains.kotlin.plugin.serialization" -> useModule("org.jetbrains.kotlin:kotlin-serialization:${versions["kotlin"]}")
                id.startsWith("org.jetbrains.kotlin.") -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${versions["kotlin"]}")
                id == "org.jetbrains.dokka" -> useModule("org.jetbrains.dokka:dokka-gradle-plugin:${versions["dokka"]}")
                id.startsWith("com.android.") -> useModule("com.android.tools.build:gradle:${versions["agp"]}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        // Cloud sessions set FE8_MAVEN_MIRROR (tools/maven_mirror.py: retries the proxy's spurious 404/429s);
        // they never build :app, so only Maven Central (+ AndroidX/AGP groups, unused there) go via the mirror.
        // CI never sets it and uses the standard repositories.
        val mirror = System.getenv("FE8_MAVEN_MIRROR")
        if (mirror.isNullOrBlank()) {
            google()
            mavenCentral()
        } else {
            maven("$mirror/central") { isAllowInsecureProtocol = true }
            maven("$mirror/google") { isAllowInsecureProtocol = true; content { includeGroupByRegex("androidx\\..*|com\\.android(\\..*)?") } }
        }
    }
}
rootProject.name = "fe8-android"

// Pure Kotlin/JVM modules: testable in a cloud session in seconds, no Android SDK.
include(
    ":gba:vfs", ":gba:data", ":gba:core", ":engine:core", ":engine:data", ":engine:input", ":engine:render",
    ":engine:audio", ":engine:script", ":engine:text", ":engine:extract", ":engine:save",
    ":game", ":testing", ":tools:extract",
)

// The thin Android shell is only included when an SDK is available (CI). Sessions never install one.
val localProps = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasAndroidSdk) include(":app") else logger.lifecycle("No Android SDK found: :app excluded (JVM modules only)")

// Heavy build-only plugins are put on the classpath only where needed, so sessions resolve a minimal set:
//   fe8.android -> AGP + Compose compiler plugin (only with an SDK, i.e. CI)
//   fe8.docs    -> Dokka (CI, or -Pfe8.docs=true)
(gradle as ExtensionAware).extensions.extraProperties["fe8.android"] = hasAndroidSdk
(gradle as ExtensionAware).extensions.extraProperties["fe8.docs"] =
    System.getenv("CI") != null || providers.gradleProperty("fe8.docs").orNull == "true"
