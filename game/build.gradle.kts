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
            // Fe8ScriptConstants (EVFLAG_* names) lives with the extractor.
            api(project(":engine:extract"))
        }
        jvmTest.dependencies {
            implementation(project(":gba:testkit"))
        }
    }
}
