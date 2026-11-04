plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":gba:vfs"))
            listOf("core", "data", "input", "render", "script", "text", "save").forEach { api(project(":engine:$it")) }
            api(project(":gba:audio"))
        }
    }
}
