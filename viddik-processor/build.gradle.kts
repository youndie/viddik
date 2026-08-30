plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaJvm)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}

// How a fixture's name, size and theme are decided is the part of this module worth pinning, and
// FixtureMetadata.kt keeps it free of KSP so it can be tested without standing up a compilation.
// `kotlin("test")`, the JUnit Platform and the publication for this `kotlin("jvm")` module all come
// from the conventions now; the block that registered the publication by hand lived here because the
// old convention did not know that a plain Kotlin/JVM module registers none of its own.
