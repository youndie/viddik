// No `org.jetbrains.kotlin.android`: AGP 9 has Kotlin support built in and refuses the plugin by
// name if you add it anyway.
plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.youndie.viddik.samples.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.youndie.viddik.samples"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Unsigned debug only: this is a sample, and nothing here is meant to be published.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // The fixtures module, for the registry `showroomTargets` generated from its commonMain. Note
    // that this is an ordinary dependency on an ordinary module: no test source set is involved, which
    // is exactly why the registry has to come from commonMain to reach a phone.
    implementation(projects.fixtures)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
