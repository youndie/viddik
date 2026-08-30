plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
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
                implementation(libs.compose.ui)
                // ViddikTypography() (ViddikFonts.kt) builds a Material3 Typography — the only reason
                // this module has an opinion on Material3 at all, everything else here is design-
                // system-agnostic.
                implementation(libs.compose.material3)
                api(libs.ui.test)
                api(compose.desktop.currentOs)
                api(libs.junit.jupiter.api)
                api(libs.junit.vintage.engine)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.junit.jupiter.engine)
                implementation(libs.junit.platform.launcher)
                implementation(libs.ui.tooling.preview)
            }
        }
    }
}

dependencies {
    add("kspJvmTest", project(":viddik-processor"))
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
}
