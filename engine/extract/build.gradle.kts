// ROM -> assets extraction core (ADR 0011): file-free, runs from the ROM bytes and the bundled
// manifest; used by :tools:extract (CLI) and later :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // api: IndexedPng (now :gba:extract) exposes gba.data.IndexedImage.
    api(project(":gba:extract"))
    api(project(":engine:data"))
    implementation(project(":engine:text"))
}
