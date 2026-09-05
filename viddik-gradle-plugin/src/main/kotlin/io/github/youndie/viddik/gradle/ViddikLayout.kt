package io.github.youndie.viddik.gradle

/**
 * Every name and path the plugin needs, derived once from how the consumer's module is built.
 *
 * The whole point of the plugin lives here: a `jvm("desktop")` target, an unnamed `jvm()` target and
 * a plain `kotlin("jvm")` module each spell the same six things differently, and getting one of them
 * wrong fails in a way that looks like something else — a `kspTest` dependency on a KMP module simply
 * generates nothing, and the screenshot task then reports a green build with no tests in it.
 */
internal data class ViddikLayout(
    /** Test source set the fixtures and the generated code live in. */
    val testSourceSetName: String,
    /** Configuration the KSP processor is added to. */
    val kspConfigurationName: String,
    /** Where KSP writes the generated registry and test class, relative to the module directory. */
    val generatedSourceDir: String,
    /** Default value for the `viddik.snapshotsDir` system property. */
    val defaultSnapshotsDir: String,
    /** Configuration for the compile-time viddik dependencies. */
    val implementationConfigurationName: String,
    /** Configuration for the JUnit 5 engine and launcher, which are needed at runtime only. */
    val runtimeOnlyConfigurationName: String,
    /** Lifecycle task that compiles the test source set. */
    val testClassesTaskName: String,
    /** The module's ordinary test task, which the generated screenshot tests are excluded from. */
    val testTaskName: String,
    /** Coordinates of the artifacts to add, which differ between a KMP and a plain JVM consumer. */
    val coordinates: ViddikCoordinates,
) {
    internal companion object {
        /**
         * Layout for a Kotlin Multiplatform module, e.g. `jvm("desktop")` → `desktopTest`,
         * `kspDesktopTest`, `src/desktopTest/snapshots`.
         *
         * [testSourceSetName] comes from the compilation itself rather than from `targetName + "Test"`
         * — they agree for every target declared the ordinary way, but the source set is what the
         * generated code and the goldens actually sit in, so it is the honest source.
         */
        fun forMultiplatform(
            targetName: String,
            testSourceSetName: String,
        ): ViddikLayout =
            ViddikLayout(
                testSourceSetName = testSourceSetName,
                kspConfigurationName = "ksp${targetName.capitalizeAscii()}Test",
                generatedSourceDir = "build/generated/ksp/$targetName/$testSourceSetName/kotlin",
                defaultSnapshotsDir = "src/$testSourceSetName/snapshots",
                implementationConfigurationName = "${testSourceSetName}Implementation",
                runtimeOnlyConfigurationName = "${testSourceSetName}RuntimeOnly",
                testClassesTaskName = "${testSourceSetName}Classes",
                testTaskName = "${targetName}Test",
                // A KMP-aware consumer resolves the right variant through Gradle module metadata, so
                // the base coordinates are correct regardless of whether its target name matches ours.
                coordinates =
                    ViddikCoordinates(
                        annotations = "io.github.youndie.viddik:viddik-annotations",
                        testingCore = "io.github.youndie.viddik:viddik-testing-core",
                    ),
            )

        /**
         * Layout for a plain `kotlin("jvm")` module, which is not KMP-aware and therefore cannot
         * resolve a multiplatform variant: it needs the platform-suffixed artifacts, and the target
         * suffix differs per module because viddik's own two KMP modules name their JVM targets
         * differently (`jvm("desktop")` in annotations, unnamed `jvm()` in testing-core).
         */
        fun forJvm(): ViddikLayout =
            ViddikLayout(
                testSourceSetName = "test",
                kspConfigurationName = "kspTest",
                generatedSourceDir = "build/generated/ksp/test/kotlin",
                defaultSnapshotsDir = "src/test/snapshots",
                implementationConfigurationName = "testImplementation",
                runtimeOnlyConfigurationName = "testRuntimeOnly",
                testClassesTaskName = "testClasses",
                testTaskName = "test",
                coordinates =
                    ViddikCoordinates(
                        annotations = "io.github.youndie.viddik:viddik-annotations-desktop",
                        testingCore = "io.github.youndie.viddik:viddik-testing-core-jvm",
                    ),
            )

        private fun String.capitalizeAscii(): String = replaceFirstChar { it.uppercaseChar() }
    }
}

/** Module coordinates without a version; the version comes from the extension. */
internal data class ViddikCoordinates(
    val annotations: String,
    val testingCore: String,
) {
    /** The processor is a plain `kotlin("jvm")` module, so its coordinates never carry a suffix. */
    val processor: String get() = "io.github.youndie.viddik:viddik-processor"
}
