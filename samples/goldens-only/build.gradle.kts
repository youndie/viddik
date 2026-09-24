plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.github.youndie.viddik")
}

// THE OTHER SHAPE, and the more common one: a multiplatform library that uses viddik for its desktop
// goldens and nothing else. `showroomTargets` stays at its default, the fixtures live in
// `desktopTest`, and nothing viddik generates is compiled into `commonMain`.
//
// It exists because the plugin once put its showroom directory into `commonMain` in this shape too,
// and every per-target KSP task then read an output of `kspCommonMainKotlinMetadata` without being
// ordered after it — a build Gradle refuses to run (issue #44). The `:fixtures` module has the
// feature on and could not see it. More than one target on purpose: that is what gives `commonMain`
// a metadata compilation, and with it the KSP task whose output was being read.
kotlin {
    jvm("desktop")
    iosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(wip.compose.runtime)
            implementation(wip.compose.foundation)
        }

        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
