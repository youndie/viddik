plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("com.google.devtools.ksp")
    id("io.github.youndie.viddik")
}

// Read from the main build's gradle.properties, which is the one place the version is stated. The
// `plugins { }` block has to come first in a Kotlin build script, so this cannot sit above it.
val viddikVersion: String =
    providers
        .fileContents(rootProject.layout.projectDirectory.file("../gradle.properties"))
        .asText
        .get()
        .lineSequence()
        .first { it.startsWith("version=") }
        .substringAfter('=')
        .trim()

kotlin {
    android {
        namespace = "io.github.youndie.viddik.samples"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    iosArm64()
    iosSimulatorArm64 {
        binaries.executable {
            // The sample iOS app is this executable plus an Info.plist — see `scripts/ios-showroom.sh`
            // and `ShowroomEntryPoint.kt`. No Xcode project: there is nothing in one that a component
            // browser needs, and a `.pbxproj` in a library repository is a file that rots unread.
            entryPoint = "io.github.youndie.viddik.samples.ios.showroomMain"
            baseName = "ViddikShowroom"
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.ui.tooling.preview)
            // The entry points. `viddik-annotations` arrives through it, and so does the registry's
            // ViddikComponent type; the plugin adds it to commonMain as well because
            // `showroomTargets` is on.
            // The version is written out but never resolved from a repository: `includeBuild("..")`
            // in the samples' settings substitutes this coordinate for the project that publishes it.
            // It has to parse, and that is all it has to do.
            api("io.github.youndie.viddik:viddik-showroom:$viddikVersion")
        }

        val desktopTest by getting {
            dependencies {
                // The host's skiko, for the source set that renders the goldens. Every consumer
                // writes this line; nothing viddik publishes carries a host-specific runtime.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// THE POINT OF THIS MODULE. The fixtures live in `commonMain`, so the registry KSP writes beside them
// is compiled for every target — the Android app links it, the iOS executable links it, and the
// desktop goldens are captured from the same list.
viddik {
    showroomTargets = true
    verifyOnCheck = true
}
