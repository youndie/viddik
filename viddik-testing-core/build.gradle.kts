plugins {
    kotlin("multiplatform")
    alias(wip.plugins.composeMultiplatform)
    alias(wip.plugins.composeCompiler)
    alias(wip.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(projects.viddikAnnotations)
                implementation(wip.compose.ui)
                // ViddikTypography() (ViddikFonts.kt) builds a Material3 Typography — the only reason
                // this module has an opinion on Material3 at all, everything else here is design-
                // system-agnostic.
                implementation(libs.compose.material3)
                api(wip.compose.ui.test)
                // `common` AND NOT `currentOs`, and the difference only shows up in the POM.
                // `compose.desktop.currentOs` resolves to the machine that ran the build —
                // `desktop-jvm-macos-arm64` here — and publishing it puts that machine's skiko
                // runtime at compile scope for every consumer, whatever they run on. The classes
                // this module compiles against come from `common`; the native runtime belongs to
                // whoever is doing the rendering, which is why a consumer's test source set has had
                // to add `compose.desktop.currentOs` itself all along.
                api(compose.desktop.common)
                api(libs.junit.jupiter.api)
                api(libs.junit.vintage.engine)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit.jupiter.engine)
                implementation(libs.junit.platform.launcher)
                implementation(wip.compose.ui.tooling.preview)
                implementation(libs.backdrop)
                // The host's skiko, for the source set that actually renders. Nothing published
                // carries it, so this is where it has to be — the same line every consumer writes.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

dependencies {
    add("kspJvmTest", project(":viddik-processor"))
}

// `-Pshards=4` splits this module's own fixtures across four generated classes, which is what the
// plugin does for a consumer through `viddik { shards = ... }`. Here it exists to exercise the
// processor's side of that split and to measure it; the default is one class, as everywhere else.
ksp {
    providers.gradleProperty("shards").orNull?.let { arg("viddik.shards", it) }
}

kotlin.sourceSets.getByName("jvmTest") {
    kotlin.srcDir("build/generated/ksp/jvm/jvmTest/kotlin")
}

// The four ktlint tasks over `jvmTest` used to be disabled here: this source set contains the
// KSP-generated registry, and a formatter rewriting generated code makes the generator's next run
// look like a change. `sborka.lint` excludes `/build/generated/` from what ktlint sees, so the tasks
// can run again — and the hand-written tests in this source set are checked instead of skipped along
// with the generated file.

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("viddik.snapshotsDir", "src/jvmTest/snapshots")
    // This module's fixtures are themed with viddikTypography(), so the bundled font is the one that
    // draws them and the check has the right font to read. Eating our own dog food: a fixture that
    // starts drawing a character Roboto lacks fails here rather than on a contributor's other OS.
    systemProperty("viddik.glyphCheck", "true")
    // `-PsceneReuse=true` runs the whole suite against one shared scene (#40). The two paths have to
    // produce the same goldens, and the only way to keep that true is to be able to run both.
    providers.gradleProperty("sceneReuse").orNull?.let { systemProperty("viddik.sceneReuse", it) }
    providers.gradleProperty("filter").orNull?.let { systemProperty("viddik.filter", it) }
    providers.gradleProperty("shards").orNull?.let { maxParallelForks = it.toInt().coerceAtLeast(1) }
}
