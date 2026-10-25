// ROM -> moddable asset extractor (Milestone 2). A JVM CLI; it never runs in the app.
// Output goes to the ROM repo only (it refuses to write inside this code repo, see ExtractCli).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    // api: IndexedPng exposes gbafe.data.IndexedImage.
    api(project(":engine:data"))
}

application {
    mainClass.set("gbafe.extract.ExtractCliKt")
}

tasks.named<JavaExec>("run") {
    // Relative --out paths resolve against the directory Gradle was started from, not tools/extract/.
    workingDir = rootProject.projectDir
    // The code-repo guard needs to know where the repo is even when the CLI runs from a jar elsewhere.
    systemProperty("extract.codeRepo", rootProject.projectDir.absolutePath)
}
