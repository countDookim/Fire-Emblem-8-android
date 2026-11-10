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

// ROM-extracted test output (build dir only) that tools/tests/test_schemas.py validates.
tasks.named<Test>("test") {
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
