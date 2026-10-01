plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaJvm)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:${wip.versions.ksp.get()}")
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    // RegistryCompileTest compiles what the emitter writes, in-process and with the Compose plugin:
    // the 64 KB method limit is reached in bytecode, and only the composable lambdas the plugin
    // generates make the bytecode the size it is in a consumer. The registry names
    // `ViddikComponent` and `LocalViddikDarkTheme`, hence the annotations — whose JVM variant
    // brings the Compose runtime along.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${wip.versions.kotlin.get()}")
    testImplementation("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${wip.versions.kotlin.get()}")
    testImplementation(projects.viddikAnnotations)
}

// A Kotlin compilation of a few thousand functions inside the test JVM; the default 512 MB is not it.
tasks.test {
    maxHeapSize = "2g"
}

// How a fixture's name, size and theme are decided is the part of this module worth pinning, and
// FixtureMetadata.kt keeps it free of KSP so it can be tested without standing up a compilation.
// `kotlin("test")`, the JUnit Platform and the publication for this `kotlin("jvm")` module all come
// from the conventions now; the block that registered the publication by hand lived here because the
// old convention did not know that a plain Kotlin/JVM module registers none of its own.
