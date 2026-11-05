plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(project(":gba:testkit"))
        }
    }
}
