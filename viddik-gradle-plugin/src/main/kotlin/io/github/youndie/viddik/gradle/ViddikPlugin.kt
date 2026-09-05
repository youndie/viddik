package io.github.youndie.viddik.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * Wires viddik screenshot testing into a module.
 *
 * Replaces the block every consumer used to copy: the dependencies (with the right coordinates for a
 * KMP or a plain JVM module), the KSP processor on the right configuration, the generated-source
 * directory, and a pair of tasks for recording and verifying goldens.
 *
 * ```kotlin
 * plugins {
 *     kotlin("multiplatform")
 *     id("com.google.devtools.ksp")
 *     id("io.github.youndie.viddik")
 * }
 * ```
 *
 * KSP is the consumer's to apply: its version is pinned to their exact Kotlin compiler version, so
 * the plugin can only check that it's there.
 */
public class ViddikPlugin : Plugin<Project> {
    /** The KSP argument is project-wide; a module with two JVM targets must not add it twice. */
    private var generateTestsOptionApplied = false

    override fun apply(target: Project) {
        val extension = target.extensions.create(EXTENSION_NAME, ViddikExtension::class.java)
        extension.applyDefaults()

        // Registered up front so `tasks.named("viddikVerify") { }` works from the consumer's build
        // script; everything that needs to know the module's shape is configured in `afterEvaluate`,
        // once the target and the extension values are final.
        val verify =
            target.tasks.register(VERIFY_TASK, ViddikScreenshotTask::class.java) {
                it.group = LifecycleBasePlugin.VERIFICATION_GROUP
                it.description = "Verifies the recorded viddik screenshot goldens."
            }
        target.tasks.register(RECORD_TASK, ViddikScreenshotTask::class.java) {
            it.group = LifecycleBasePlugin.VERIFICATION_GROUP
            it.description = "Records viddik screenshot goldens, overwriting the existing ones."
        }
        target.tasks.register(SHOWROOM_TASK, JavaExec::class.java) {
            it.group = "application"
            it.description = "Opens this module's viddik component browser in a window."
        }

        // Dependencies and generated sources are wired the moment the target appears, not in
        // `afterEvaluate`: KSP decides whether its task has anything to do by looking at whether its
        // configuration is empty, and it makes that call from its own `afterEvaluate` — which runs
        // first whenever KSP is applied before this plugin. A processor added later is simply never
        // seen, and the symptom is not an error but `kspTestKotlinDesktop SKIPPED` followed by a
        // screenshot task that passes with no tests in it.
        target.plugins.withId(KMP_PLUGIN_ID) {
            target.extensions
                .getByType(KotlinMultiplatformExtension::class.java)
                .targets
                .withType(KotlinJvmTarget::class.java)
                .all { jvmTarget -> target.wireSources(extension, jvmTarget.layout()) }
        }
        target.plugins.withId(JVM_PLUGIN_ID) {
            target.wireSources(extension, ViddikLayout.forJvm())
        }

        target.afterEvaluate { project ->
            val module = project.detectModule()
            project.requireKsp(module)
            project.wireTasks(extension, module)

            val verifyOnCheck =
                extension.verifyOnCheck.get() || project.providers.gradleProperty(VERIFY_PROPERTY).isPresent
            if (verifyOnCheck) {
                project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { it.dependsOn(verify) }
            }
        }
    }

    /**
     * How the module is built, and the classpath its screenshot tests run against.
     *
     * A KMP module and a plain `kotlin("jvm")` module differ in every name the wiring touches, which
     * is exactly the fork this plugin exists to hide.
     */
    private class ModuleShape(
        val layout: ViddikLayout,
        val testClassesDirs: FileCollection,
        val runtimeClasspath: FileCollection,
    )

    private fun Project.detectModule(): ModuleShape =
        when {
            plugins.hasPlugin(KMP_PLUGIN_ID) -> {
                multiplatformShape()
            }

            plugins.hasPlugin(JVM_PLUGIN_ID) -> {
                jvmShape()
            }

            else -> {
                throw GradleException(
                    "The viddik plugin needs a Kotlin module to attach to: apply `kotlin(\"multiplatform\")` " +
                        "or `kotlin(\"jvm\")` in $displayName before `id(\"io.github.youndie.viddik\")`.",
                )
            }
        }

    private fun Project.multiplatformShape(): ModuleShape {
        val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
        val jvmTargets = kotlin.targets.withType(KotlinJvmTarget::class.java).toList()
        val configured = extensions.getByType(ViddikExtension::class.java).jvmTarget.orNull

        val jvmTarget =
            when {
                configured != null -> {
                    jvmTargets.firstOrNull { it.name == configured }
                        ?: throw GradleException(
                            "viddik { jvmTarget = \"$configured\" } names a target $displayName doesn't have. " +
                                "JVM targets here: ${jvmTargets.joinToString { it.name }.ifEmpty { "none" }}.",
                        )
                }

                jvmTargets.size == 1 -> {
                    jvmTargets.single()
                }

                jvmTargets.isEmpty() -> {
                    throw GradleException(
                        "viddik renders through a real Compose Desktop window, so $displayName needs a JVM " +
                            "target — add `jvm(\"desktop\")` (or a plain `jvm()`) to its `kotlin { }` block.",
                    )
                }

                else -> {
                    throw GradleException(
                        "$displayName has more than one JVM target (${jvmTargets.joinToString { it.name }}); " +
                            "say which one carries the screenshot fixtures via `viddik { jvmTarget = \"...\" }`.",
                    )
                }
            }

        val testCompilation = jvmTarget.compilations.getByName(TEST_COMPILATION)
        return ModuleShape(
            layout = jvmTarget.layout(),
            testClassesDirs = testCompilation.output.classesDirs,
            runtimeClasspath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles),
        )
    }

    /**
     * The test source set is read off the compilation rather than assembled from the target name:
     * they agree for a target declared the ordinary way, but the source set is where the generated
     * code and the goldens actually live, so it is the honest answer.
     */
    private fun KotlinJvmTarget.layout(): ViddikLayout =
        ViddikLayout.forMultiplatform(
            targetName = name,
            testSourceSetName = compilations.getByName(TEST_COMPILATION).defaultSourceSet.name,
        )

    private fun Project.jvmShape(): ModuleShape {
        val testSourceSet =
            extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets
                .getByName("test")
        return ModuleShape(
            layout = ViddikLayout.forJvm(),
            testClassesDirs = testSourceSet.output.classesDirs,
            runtimeClasspath = testSourceSet.runtimeClasspath,
        )
    }

    private fun Project.requireKsp(module: ModuleShape) {
        if (plugins.hasPlugin(KSP_PLUGIN_ID)) return
        throw GradleException(
            "viddik generates its component registry with KSP, which $displayName doesn't apply. Add " +
                "`id(\"com.google.devtools.ksp\")` — at a version matching your Kotlin compiler — and the " +
                "plugin will put the processor on `${module.layout.kspConfigurationName}` for you.",
        )
    }

    /**
     * Everything that has to be in place before any other plugin looks: the generated-source
     * directory, the KSP argument and the dependencies.
     *
     * Runs while the build script is still being evaluated, so the extension's values aren't final
     * yet — hence the deferred dependencies in [addLater] and the argument provider rather than plain
     * reads.
     */
    private fun Project.wireSources(
        extension: ViddikExtension,
        layout: ViddikLayout,
    ) {
        // KSP writes the registry (and the test class) outside any source set the consumer declared,
        // so the compilation has to be told where to find it.
        extensions
            .getByType(KotlinProjectExtension::class.java)
            .sourceSets
            .getByName(layout.testSourceSetName)
            .kotlin
            .srcDir(layout.generatedSourceDir)

        plugins.withId(KSP_PLUGIN_ID) {
            if (generateTestsOptionApplied) return@withId
            generateTestsOptionApplied = true
            val option = extension.generateTests.map { "$GENERATE_TESTS_OPTION=$it" }
            // A `CommandLineArgumentProvider` rather than `arg(key, value)`: the value is read at
            // execution time, by which point `viddik { generateTests = ... }` has been evaluated.
            extensions.getByType(KspExtension::class.java).arg { listOf(option.get()) }
        }

        addViddikDependencies(extension, layout)
    }

    private fun Project.wireTasks(
        extension: ViddikExtension,
        module: ModuleShape,
    ) {
        val layout = module.layout
        val generateTests = extension.generateTests.get()

        // Only now is the module's shape known, so this is where the default can be derived.
        val snapshotsDir = extension.snapshotsDir.getOrElse(layout.defaultSnapshotsDir)
        configureScreenshotTask(VERIFY_TASK, extension, module, snapshotsDir, generateTests) { task ->
            // A re-recorded golden has to re-run the comparison. A file tree rather than `inputs.dir`
            // so a module that hasn't recorded anything yet still reaches the task's own error
            // message ("No golden snapshot for ...") instead of failing on a missing directory.
            task.inputs
                .files(fileTree(snapshotsDir) { it.include("**/*.png") })
                .withPropertyName("viddikGoldens")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        configureScreenshotTask(RECORD_TASK, extension, module, snapshotsDir, generateTests) { task ->
            task.environment(RECORD_MODE_ENV, "true")
            // Recording is what the user asked for, not something to skip because the inputs happen
            // to look unchanged — this is the `--rerun` that used to be part of the incantation.
            task.outputs.upToDateWhen { false }
        }

        tasks.named(SHOWROOM_TASK, JavaExec::class.java).configure { task ->
            task.dependsOn(layout.testClassesTaskName)
            task.classpath = module.runtimeClasspath
            task.mainClass.set(SHOWROOM_MAIN_CLASS)
        }

        if (extension.excludeFromTestTask.get() && generateTests) {
            tasks
                .withType(Test::class.java)
                .matching { it.name == layout.testTaskName }
                .configureEach { task ->
                    task.filter {
                        it.excludeTestsMatching(GENERATED_TESTS_PATTERN)
                        // A module whose only tests are screenshots would otherwise fail the ordinary
                        // test task with "no tests found" the moment we exclude them.
                        it.isFailOnNoMatchingTests = false
                    }
                }
        }
    }

    private fun Project.addViddikDependencies(
        extension: ViddikExtension,
        layout: ViddikLayout,
    ) {
        val coordinates = layout.coordinates
        addLater(layout.implementationConfigurationName, extension) { version ->
            listOf("${coordinates.annotations}:$version", "${coordinates.testingCore}:$version")
        }
        addLater(layout.kspConfigurationName, extension) { version ->
            listOf("${coordinates.processor}:$version")
        }
        // The generated tests are JUnit 5; the engine and the launcher are only needed to run them.
        addLater(layout.runtimeOnlyConfigurationName, extension) {
            listOf(
                "org.junit.jupiter:junit-jupiter-engine:${ViddikPluginVersions.junitJupiter}",
                "org.junit.platform:junit-platform-launcher:${ViddikPluginVersions.junitPlatform}",
            )
        }
    }

    /**
     * Adds dependencies to a configuration without caring whether it exists yet — the KSP
     * configurations don't until KSP is applied, and the plugins can be applied in either order.
     *
     * The dependencies themselves stay behind a provider so that `viddik { }` still gets a say: this
     * runs while the build script is being evaluated, and reading the extension here would freeze
     * whatever the conventions happen to be at that moment.
     */
    private fun Project.addLater(
        configurationName: String,
        extension: ViddikExtension,
        notations: (version: String) -> List<String>,
    ) {
        val declared =
            extension.addDependencies.zip(extension.viddikVersion) { enabled, version ->
                if (enabled) notations(version).map(dependencies::create) else emptyList()
            }
        configurations
            .matching { it.name == configurationName }
            .configureEach { it.dependencies.addAllLater(declared) }
    }

    private fun Project.configureScreenshotTask(
        name: String,
        extension: ViddikExtension,
        module: ModuleShape,
        snapshotsDir: String,
        generateTests: Boolean,
        extraConfiguration: (Test) -> Unit,
    ) {
        tasks.named(name, ViddikScreenshotTask::class.java).configure { task ->
            // Without this the task happily runs against stale classes and reports a green build.
            task.dependsOn(module.layout.testClassesTaskName)
            task.testClassesDirs = module.testClassesDirs
            task.classpath = module.runtimeClasspath
            task.useJUnitPlatform()
            task.filter { it.includeTestsMatching(GENERATED_TESTS_PATTERN) }
            // `--component` is only known after configuration, so it travels as a lazy JVM argument
            // rather than a `systemProperty`; being an input, changing it also re-runs a verification
            // that would otherwise be up to date.
            task.jvmArgumentProviders.add(ViddikFilterArgumentProvider(task.component))
            // The interesting part of a screenshot failure is the message — which fixture, how many
            // pixels, where the _DIFF.png went, or which components a mistyped --component missed.
            // Gradle's default logging shows only the exception type and a line number in generated
            // code, sending you to the HTML report for every failure.
            task.testLogging { logging ->
                logging.events(TestLogEvent.FAILED)
                logging.exceptionFormat = TestExceptionFormat.FULL
                logging.showStackTraces = false
            }

            task.systemProperty(SNAPSHOTS_DIR_PROPERTY, snapshotsDir)
            extension.reportsDir.orNull?.let { task.systemProperty(REPORTS_DIR_PROPERTY, it) }
            extension.tolerancePercent.orNull?.let { task.systemProperty(TOLERANCE_PERCENT_PROPERTY, it) }
            extension.channelTolerance.orNull?.let { task.systemProperty(CHANNEL_TOLERANCE_PROPERTY, it) }

            if (!generateTests) {
                task.doFirst {
                    throw GradleException(
                        "`viddik { generateTests = false }` in $displayName, so no screenshot tests are " +
                            "generated here and there is nothing for :$name to run.",
                    )
                }
            }
            extraConfiguration(task)
        }
    }

    /**
     * `snapshotsDir` deliberately has no convention: its default depends on the module's shape, which
     * isn't known until `afterEvaluate` — see [wire].
     */
    private fun ViddikExtension.applyDefaults() {
        generateTests.convention(true)
        verifyOnCheck.convention(false)
        excludeFromTestTask.convention(true)
        addDependencies.convention(true)
        viddikVersion.convention(ViddikPluginVersions.viddik)
    }

    private companion object {
        const val EXTENSION_NAME = "viddik"
        const val VERIFY_TASK = "viddikVerify"
        const val RECORD_TASK = "viddikRecord"
        const val SHOWROOM_TASK = "viddikShowroom"

        const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
        const val JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
        const val KSP_PLUGIN_ID = "com.google.devtools.ksp"

        const val TEST_COMPILATION = "test"
        const val GENERATED_TESTS_PATTERN = "*GeneratedViddikTests*"
        const val SHOWROOM_MAIN_CLASS = "io.github.youndie.viddik.core.ViddikShowroomLauncher"

        const val VERIFY_PROPERTY = "viddik.verify"
        const val GENERATE_TESTS_OPTION = "viddik.generateTests"
        const val RECORD_MODE_ENV = "VIDDIK_RECORD_MODE"
        const val SNAPSHOTS_DIR_PROPERTY = "viddik.snapshotsDir"
        const val REPORTS_DIR_PROPERTY = "viddik.reportsDir"
        const val TOLERANCE_PERCENT_PROPERTY = "viddik.tolerancePercent"
        const val CHANNEL_TOLERANCE_PROPERTY = "viddik.channelTolerance"
    }
}
