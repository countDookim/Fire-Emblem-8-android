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
    val extracted = layout.buildDirectory.dir("extracted")
    systemProperty("fe8.extractedOut", extracted.get().asFile.absolutePath)
    outputs.dir(extracted)
}
