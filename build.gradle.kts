import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Conditional classpath (see settings.gradle.kts): AGP/Compose only with an Android SDK, Dokka only for
// docs builds. Cloud sessions therefore resolve just the Kotlin plugin + test libraries.
buildscript {
    val extra = (gradle as ExtensionAware).extensions.extraProperties
    val v = file("gradle/libs.versions.toml").readLines()
        .mapNotNull { Regex("""^([\w-]+)\s*=\s*"([^"]+)"""").find(it)?.destructured?.let { (k, x) -> k to x } }.toMap()
    dependencies {
        if (extra["fe8.android"] == true) {
            classpath("com.android.tools.build:gradle:${v["agp"]}")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${v["kotlin"]}")
        }
        if (extra["fe8.docs"] == true) classpath("org.jetbrains.dokka:dokka-gradle-plugin:${v["dokka"]}")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val docs = (gradle as ExtensionAware).extensions.extraProperties["fe8.docs"] == true
val jvmModules = listOf(
    ":engine:core", ":engine:vfs", ":engine:data", ":engine:input", ":engine:render",
    ":engine:audio", ":engine:script", ":engine:text", ":engine:save", ":game", ":testing",
)
val jdk = providers.gradleProperty("fe8.jdk").orElse("21").get().toInt()
val catalog = libs

// Convention for every pure-JVM module (kept here rather than in a build-logic plugin because the
// kotlin-dsl plugin that needs is only on the Gradle plugin portal, which sessions cannot reach).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (docs) apply(plugin = "org.jetbrains.dokka")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(jdk)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17) // consumed by Android (minSdk 26) as JVM 17 bytecode
                freeCompilerArgs.add("-Xjdk-release=17")
            }
            explicitApi() // every public declaration is deliberate (and documented)
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        dependencies {
            "testImplementation"(platform(catalog.junit.bom))
            "testImplementation"(catalog.junit.jupiter)
            "testRuntimeOnly"(catalog.junit.platform.launcher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Differential tests read mGBA reference data from here; they are SKIPPED (never passed) when absent.
            systemProperty("fe8.assets", System.getenv("FE8_ASSETS") ?: rootProject.file(".assets").absolutePath)
            jvmArgs("-XX:+UseSerialGC")
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

// Aggregated Dokka site (docs builds only): ./gradlew dokkaGenerate -Pfe8.docs=true -> build/dokka/html
if (docs) {
    apply(plugin = "org.jetbrains.dokka")
    dependencies { jvmModules.forEach { "dokka"(project(it)) } }
}

/** Compiles all pure-JVM modules incl. tests (tools/setup.sh uses it to warm Gradle). */
tasks.register("jvmClasses") { dependsOn(jvmModules.map { "$it:testClasses" }) }

/** Runs every JVM test (unit, differential, allocation). Sessions run this; CI runs it plus :app. */
tasks.register("jvmTest") { dependsOn(jvmModules.map { "$it:test" }) }
