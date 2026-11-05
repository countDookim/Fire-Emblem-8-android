plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":gba:core"))
        }
        jvmTest.dependencies {
            implementation(project(":gba:testkit"))
        }
    }
}
