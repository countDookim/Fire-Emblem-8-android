import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val docs = (gradle as ExtensionAware).extensions.extraProperties["fe8.docs"] == true
val androidSdk = (gradle as ExtensionAware).extensions.extraProperties["fe8.android"] == true
val jvmModules = listOf(
    ":gba:vfs", ":gba:data", ":gba:core", ":gba:render", ":gba:input", ":gba:audio", ":gba:extract", ":gba:testkit", ":engine:core", ":engine:data", ":engine:input", ":engine:render",
    ":engine:script", ":engine:text", ":engine:extract", ":engine:save", ":game", ":testing",
    ":tools:extract",
)
/** Modules converted to Kotlin Multiplatform (their JVM tasks are `jvmTest`/`jvmTestClasses`). */
val kmpModules = setOf(":gba:data", ":gba:core", ":gba:input", ":gba:render", ":gba:audio", ":gba:vfs", ":gba:extract", ":engine:core", ":engine:data", ":engine:input", ":engine:render", ":engine:save", ":engine:script", ":engine:text", ":game")
val jdk = providers.gradleProperty("fe8.jdk").orElse("21").get().toInt()
val catalog = libs

/** Test-task settings shared by the JVM and multiplatform conventions. */
fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Differential tests read mGBA reference data from here; they are SKIPPED (never passed) when absent.
        val assetsDir = System.getenv("FE8_ASSETS") ?: rootProject.file(".assets").absolutePath
        systemProperty("fe8.assets", assetsDir)
        // The reference data must be a declared input: otherwise the build cache happily restores
        // results from a run without assets (all differential tests skipped) — this happened in CI.
        val manifest = File(assetsDir, "MANIFEST.sha1")
        val refOut = File(assetsDir, "reference/out")
        inputs.property("fe8.assets.manifest", if (manifest.isFile) manifest.readText() else "absent")
        inputs.property("fe8.assets.extracted", refOut.isDirectory)
        // Committed decomp reference data (function list, RAM symbols) for differential tests.
        systemProperty("fe8.reference", rootProject.file("reference").absolutePath)
        inputs.dir(rootProject.file("reference")).withPathSensitivity(PathSensitivity.RELATIVE)
        jvmArgs("-XX:+UseSerialGC")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

fun Project.applyDokka() {
    if (docs) {
        apply(plugin = "org.jetbrains.dokka")
        configureDokkaLint()
    }
}

// Convention for every pure-JVM module (kept here rather than in a build-logic plugin because the
// kotlin-dsl plugin that needs is only on the Gradle plugin portal, which sessions cannot reach).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        applyDokka()
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
        configureTests()
    }
    // Kotlin Multiplatform modules: jvm everywhere, plus android where AGP is on the classpath (CI).
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        applyDokka()
        if (androidSdk) configureAndroidTarget()
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(jdk)
            explicitApi()
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    freeCompilerArgs.add("-Xjdk-release=17")
                }
            }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        dependencies {
            "jvmTestImplementation"(platform(catalog.junit.bom))
            "jvmTestImplementation"(catalog.junit.jupiter)
            "jvmTestRuntimeOnly"(catalog.junit.platform.launcher)
        }
        configureTests()
    }
}

/**
 * Adds the `android` target (decision 0013) with AGP's KMP library plugin. Only called when AGP is on the
 * classpath, and AGP's types are not on this script's compile classpath otherwise, so the target is created
 * by applying the plugin by id and configured through Groovy property access (like Dokka below) rather than
 * through the `androidLibrary {}` accessor. Android has no tests of its own: the plugin adds no host/device
 * test compilations unless asked, and all tests stay in `jvmTest`.
 */
fun Project.configureAndroidTarget() {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    val kmp = extensions.getByType<KotlinMultiplatformExtension>()
    // The extension name is the one AGP's own `kotlin { androidLibrary { } }` accessor uses.
    (kmp as ExtensionAware).extensions.getByName("androidLibrary").withGroovyBuilder {
        // `:gba:vfs` -> io.github.countdookim.fe8.gba.vfs (unique; the `:app` namespace is the bare prefix).
        setProperty("namespace", "io.github.countdookim.fe8" + path.replace(':', '.'))
        setProperty("compileSdk", 36)
        setProperty("minSdk", 26)
    }
    // Platform code lives in `src/jvmMain` (java.io.File, java.util.zip, Class.getResource: all valid on
    // Android) and is compiled twice, once per target. Adding the directories to `androidMain` instead of an
    // intermediate jvm+android source set keeps the layout and the default hierarchy template untouched.
    kmp.sourceSets.matching { it.name == "androidMain" }.configureEach {
        this.kotlin.srcDir("src/jvmMain/kotlin")
        resources.srcDir("src/jvmMain/resources")
    }
    // Same bytecode level as the jvm target (and :app); the android compile has no -Xjdk-release because AGP
    // puts android.jar on its bootclasspath.
    tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
}

/**
 * KDoc lint (brief: "KDoc on every public class and function"): every module reports undocumented public
 * declarations and any Dokka warning fails the build. Dokka is only on the classpath for docs builds, so
 * its extension is configured through Groovy property access instead of its (absent) Kotlin types.
 */
fun Project.configureDokkaLint() {
    fun Any.prop(name: String): Any = withGroovyBuilder { getProperty(name) }!!
    val dokka = extensions.getByName("dokka")
    @Suppress("UNCHECKED_CAST")
    (dokka.prop("dokkaSourceSets") as DomainObjectCollection<Any>).configureEach {
        (prop("reportUndocumented") as Property<Boolean>).set(true)
        // `androidMain` compiles the same `src/jvmMain` files as `jvmMain` (see configureAndroidTarget): skip
        // it so nothing is analysed or reported twice, and Dokka needs no Android classpath.
        if (prop("name") == "androidMain") (prop("suppress") as Property<Boolean>).set(true)
    }
    @Suppress("UNCHECKED_CAST")
    (dokka.prop("dokkaPublications") as DomainObjectCollection<Any>).configureEach {
        (prop("failOnWarning") as Property<Boolean>).set(true)
    }
}

// Aggregated Dokka site (docs builds only): ./gradlew dokkaGenerate -Pfe8.docs=true -> build/dokka/html
if (docs) {
    apply(plugin = "org.jetbrains.dokka")
    dependencies { jvmModules.forEach { "dokka"(project(it)) } }
    configureDokkaLint()
}

/** Compiles all pure-JVM modules incl. tests (tools/setup.sh uses it to warm Gradle). */
tasks.register("jvmClasses") { dependsOn(jvmModules.map { "$it:${if (it in kmpModules) "jvmTestClasses" else "testClasses"}" }) }

/** Runs every JVM test (unit, differential, allocation). Sessions run this; CI runs it plus :app. */
tasks.register("jvmTest") { dependsOn(jvmModules.map { "$it:${if (it in kmpModules) "jvmTest" else "test"}" }) }
