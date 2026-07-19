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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
