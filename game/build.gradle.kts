plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":gba:vfs"))
    listOf("core", "data", "input", "render", "audio", "script", "text", "save").forEach { api(project(":engine:$it")) }
}
