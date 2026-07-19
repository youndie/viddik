plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    id("viddik.publishing")
}

group = "ru.workinprogress"

kotlin {
    jvm()

    jvmToolchain(21)

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

tasks
    .matching {
        it.name in
            setOf(
                "ktlintJvmTestSourceSetCheck",
                "runKtlintCheckOverJvmTestSourceSet",
                "ktlintJvmTestSourceSetFormat",
                "runKtlintFormatOverJvmTestSourceSet",
            )
    }.configureEach { enabled = false }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("viddik.snapshotsDir", "src/jvmTest/snapshots")
    // This module's own self-test needs to pass on both a macOS dev machine and Linux CI, so it
    // always opts into ViddikTypography() (see DemoViddik.kt) — a real consumer decides this for
    // itself instead of inheriting it from here.
    systemProperty("viddik.consistentRendering", "true")
}
