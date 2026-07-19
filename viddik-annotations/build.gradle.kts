plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    id("viddik.publishing")
}

group = "ru.workinprogress"

kotlin {
    android {
        namespace = "ru.workinprogress.viddik.annotations"
        compileSdk = 36
        minSdk = 24
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
