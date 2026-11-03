// ROM -> assets extraction core (ADR 0011): file-free, runs from the ROM bytes and the bundled
// manifest; used by :tools:extract (CLI) and later :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // api: IndexedPng exposes gbafe.data.IndexedImage.
    api(project(":engine:data"))
    implementation(project(":engine:text"))
}
