plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    listOf("core", "vfs", "data", "input", "render", "audio", "script", "text", "save").forEach { api(project(":engine:$it")) }
}
