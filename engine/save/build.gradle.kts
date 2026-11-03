plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":engine:core"))
    api(project(":gba:vfs"))
}
