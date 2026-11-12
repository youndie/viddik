plugins {
    kotlin("multiplatform")
    alias(wip.plugins.composeMultiplatform)
    alias(wip.plugins.composeCompiler)
    alias(wip.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

// THE DEVICE HALF OF THE ANDROID MATRIX, and nothing else. It is only ever on the classpath of a
// consumer's `androidDeviceTest`, put there by `io.github.youndie.viddik.android-matrix`; the JVM
// capture path (`viddik-testing-core`) neither depends on it nor knows it exists.
//
// Android only, deliberately: what it does — capture through the device's own renderer — has no
// meaning on any other target, and a desktop variant would only invite the two paths to be mixed up.
kotlin {
    android {
        namespace = "io.github.youndie.viddik.android.capture"
        compileSdk = 37
        minSdk = 24
        // The file-name rule is the one thing here that can be judged without a device.
        withHostTest {}
    }

    sourceSets {
        androidMain.dependencies {
            // `api`: the generated test class names ViddikComponent and JUnit 4's @Test, and the runner
            // is what the device test is run with, so they have to reach the consumer's device test.
            api(projects.viddikAnnotations)
            api(libs.androidx.test.runner)
            api(libs.junit4)
            implementation(wip.compose.runtime)
            implementation(wip.compose.ui)
            implementation(wip.compose.foundation)
            implementation(wip.compose.ui.test)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.test)
            // ComponentActivity in the test APK's manifest, which is what `createComposeRule()` launches.
            implementation(libs.androidx.compose.ui.test.manifest)
        }

        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}
