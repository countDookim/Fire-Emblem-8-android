// ROM -> assets extraction core (ADR 0011): file-free, runs from the ROM bytes and the bundled
// manifest; used by :tools:extract (CLI) and :app.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api: IndexedPng (now :gba:extract) exposes gba.data.IndexedImage.
            api(project(":gba:extract"))
            api(project(":engine:data"))
            // api: Fe8ScriptConstants implements gbafe.script.ScriptConstants.
            api(project(":engine:script"))
            implementation(project(":engine:text"))
        }
    }
}
