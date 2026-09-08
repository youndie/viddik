plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    android {
        namespace = "io.github.youndie.viddik.annotations"
        compileSdk = 37
        minSdk = 24
        // `commonTest` exists now (ViddikShowroomSearchTest), and without this AGP warns on every
        // build that it can see the directory and is not running anything from it. Enabling the host
        // tests answers the warning by running them, which for a pure-logic test costs a JVM.
        withHostTest {}
    }

    jvm("desktop")

    // The showroom is the reason these are here: it is plain Compose Multiplatform, and a component
    // browser that can only be opened on the machine that runs the build is half a browser. Nothing
    // in this module touches a platform API, so the targets cost a compilation each and no code.
    // No iosX64: Compose Multiplatform stopped publishing the Intel-simulator variant, and asking
    // for it fails resolution of every compose artifact in commonMain rather than of that one target.
    iosArm64()
    iosSimulatorArm64()

    // Nothing in this build applies the default hierarchy template — `kotlin.sourceSets.names` has
    // no `iosMain` in it without this line — so the two iOS targets would otherwise have nowhere to
    // share the `glyphPerspectiveNudge` actual, and it would need writing out once per target.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }

        // The module's first test source set. `matchesQuery` is the whole of the showroom's search
        // that can be judged without rendering anything, and it is `internal`, so it is tested from
        // inside the module rather than promoted to public API to make it reachable from elsewhere.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// `compileIosMainKotlinMetadata` is the one compilation that sees both halves of a version-line split
// this repository has carried since before it had native targets: `compose.ui`/`foundation` are on
// `org.jetbrains.compose` 1.12.0, which depends on the `org.jetbrains.androidx.lifecycle` fork, while
// `material3` only exists on its own 1.12.0-alpha line, which has already moved to real AndroidX
// `androidx.lifecycle`. Both publish klibs with the same `unique_name`, and the shared metadata
// compilation loads both, one `w:` per pair.
//
// It is invisible on JVM and Android — that is why nothing noticed until the iOS targets arrived —
// and it is inert here: this module names no lifecycle, savedstate or navigationevent API, and the
// per-target compilations (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`) resolve one
// copy each and pass with -Werror intact. Kotlin has no flag to silence just this warning
// (`-Xklib-duplicated-unique-name-strategy` chooses which copy wins, not whether to mention it), so
// the exception is scoped to the metadata tasks by name rather than taken project-wide.
//
// Removes itself the day the two lines converge: check whether
// `org.jetbrains.compose.material3:material3` has a version matching `compose-multiplatform` in the
// catalog, and if it has, delete this block and let the build tell you.
tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>()
    .matching { it.name.endsWith("KotlinMetadata") }
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }
