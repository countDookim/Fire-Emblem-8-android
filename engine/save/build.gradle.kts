plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine:core"))
            api(project(":gba:vfs"))
        }
        jvmTest.dependencies {
            implementation(project(":gba:testkit"))
        }
    }
}
