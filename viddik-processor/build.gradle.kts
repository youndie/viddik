plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    id("viddik.publishing")
}

group = "ru.workinprogress"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(kotlin("test"))
}

// How a fixture's name, size and theme are decided is the part of this module worth pinning, and
// FixtureMetadata.kt keeps it free of KSP so it can be tested without standing up a compilation.
tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
