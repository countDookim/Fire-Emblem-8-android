// Verification module: mGBA reference-data access, differential tests, allocation meter, frame benchmark.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":game"))
    api(project(":gba:testkit"))
    // Round-trip tests of the extraction path (IndexedPng); test scope only, the app never ships it.
    testImplementation(project(":tools:extract"))
}

/** JVM frame-time benchmark of the game loop; fails on regression. CI runs it on every push. */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs the JVM frame-time benchmark and writes build/reports/benchmark/frame_time.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fe8.testing.FrameTimeBenchmarkKt")
    args(layout.buildDirectory.file("reports/benchmark/frame_time.json").get().asFile.absolutePath)
    jvmArgs("-XX:+UseSerialGC")
}

// Classes that meter per-thread allocation (AllocationMeter) are load-sensitive (JIT/OSR timing), so
// they run alone in `serialTest`; everything else runs in `test` on parallel forks. Detected from the
// sources, so a new class that uses AllocationMeter lands in serialTest automatically.
val serialTestClasses: List<String> = fileTree("src/test/kotlin") { include("**/*.kt") }
    .filter { it.readText().contains("AllocationMeter") }
    .map { f ->
        val text = f.readText()
        val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        (if (pkg != null) "$pkg." else "") + f.nameWithoutExtension
    }
    .sorted()

// A targeted run (`--tests X`) keeps the old behaviour: one fork, every class available, no serialTest.
val targeted = gradle.startParameter.taskRequests.any { "--tests" in it.args }

// ROM-extracted test output (build dir only) that tools/tests/test_schemas.py validates.
tasks.named<Test>("test") {
    if (!targeted) {
        // Forks are separate JVMs with default heap each (10 GB machine shared with other work).
        maxParallelForks = 3
        // These classes run in serialTest (below), exactly once, after this task.
        for (c in serialTestClasses) {
            filter.excludeTestsMatching(c)
            filter.excludeTestsMatching("$c$*")
        }
        // Runs after every `test`, even a failing one.
        finalizedBy("serialTest")
    }
    // These runtime C references affect results, including whether tests can run at all.
    val decomp = System.getenv("FE8U_DECOMP")?.let(::File)
        ?: generateSequence(rootProject.projectDir.absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, ".assets/decomp"), File(it, "fireemblem8u")) }
            .firstOrNull { File(it, "src/worldmap_node_data.c").isFile }
        ?: File(System.getenv("FE8_ASSETS") ?: rootProject.file(".assets").path, "decomp")
    systemProperty("fe8.decomp", decomp.absolutePath)
    inputs.files(listOf(
        File(decomp, "src/worldmap_node_data.c"),
        File(decomp, "include/constants/chapters.h"),
        File(decomp, "include/worldmap.h"),
    )).withPathSensitivity(PathSensitivity.RELATIVE)
    val extracted = layout.buildDirectory.dir("extracted")
    systemProperty("fe8.extractedOut", extracted.get().asFile.absolutePath)
    outputs.dir(extracted)
}

/** Load-sensitive tests (see [serialTestClasses]): one fork, nothing else running in this task. */
tasks.register<Test>("serialTest") {
    group = "verification"
    description = "Runs the allocation-metering tests alone; `test` excludes them and finalizes with this"
    val main = tasks.named<Test>("test")
    testClassesDirs = main.get().testClassesDirs
    classpath = main.get().classpath
    maxParallelForks = 1
    for (c in serialTestClasses) {
        filter.includeTestsMatching(c)
        filter.includeTestsMatching("$c$*")
    }
    // Same runtime references as `test`.
    systemProperty("fe8.decomp", main.get().systemProperties.getValue("fe8.decomp"))
    systemProperty("fe8.extractedOut", main.get().systemProperties.getValue("fe8.extractedOut"))
    onlyIf { !targeted }
}
