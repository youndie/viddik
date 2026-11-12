package io.github.youndie.viddik.gradle

import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTest
import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.VerificationTask
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.net.URLEncoder

/** The lowest level a Gradle-managed device can capture on; see the plugin's `finalizeDsl` for why. */
internal const val MIN_MANAGED_API = 29

/**
 * What runs when nobody says otherwise. All of it has to be at or above [MIN_MANAGED_API]: a default the
 * default task cannot capture would be a column that is empty on the first run anyone ever does.
 */
internal val DEFAULT_API_LEVELS = listOf(29, 31, 34, 36)

internal const val DEVICE_PREFIX = "viddikApi"
internal const val DEVICE_TEST_TASK_SUFFIX = "AndroidDeviceTest"
internal const val DEVICE_SETUP_TASK_SUFFIX = "Setup"

/**
 * The API level in the name of a task that holds an emulator (`viddikApi34Setup` → 34,
 * `viddikApi34AndroidDeviceTest` → 34), or null if it is not one.
 */
internal fun deviceLevel(taskName: String): Int? =
    listOf(DEVICE_TEST_TASK_SUFFIX, DEVICE_SETUP_TASK_SUFFIX).firstNotNullOfOrNull { suffix ->
        taskName
            .takeIf { it.startsWith(DEVICE_PREFIX) && it.endsWith(suffix) }
            ?.removePrefix(DEVICE_PREFIX)
            ?.removeSuffix(suffix)
            ?.toIntOrNull()
    }

private const val KEEP_LEVEL_PREFIX = "viddikKeepApi"
private const val KEEP_LEVEL_SUFFIX = "Captures"

/** The task that keeps what one managed level pulled: `viddikKeepApi34Captures`. */
internal fun keepTaskName(level: Int): String = "$KEEP_LEVEL_PREFIX$level$KEEP_LEVEL_SUFFIX"

/** The level in the name of such a task, or null. */
internal fun keepLevel(taskName: String): Int? =
    taskName
        .takeIf { it.startsWith(KEEP_LEVEL_PREFIX) && it.endsWith(KEEP_LEVEL_SUFFIX) }
        ?.removePrefix(KEEP_LEVEL_PREFIX)
        ?.removeSuffix(KEEP_LEVEL_SUFFIX)
        ?.toIntOrNull()

/**
 * The Android matrix: the module's fixtures captured on Gradle-managed emulators of several API
 * levels, and an HTML page lining the levels up against each other.
 *
 * ```kotlin
 * plugins {
 *     id("io.github.youndie.viddik")
 *     id("io.github.youndie.viddik.android-matrix")
 * }
 * viddik { showroomTargets = true }
 * ```
 * ```bash
 * ./gradlew :module:viddikAndroidMatrix   # → build/reports/viddik-matrix/index.html
 * ```
 *
 * A plugin of its own, and a strictly additive one: `io.github.youndie.viddik` does not know it
 * exists, `viddikVerify`/`viddikRecord` run exactly as they do without it, and it is the only class
 * in this jar that names an AGP type — a module that never applies it never loads one.
 *
 * It needs the registry in `commonMain` (`showroomTargets`), because that is the only registry an
 * Android compilation can see, and a `com.android.kotlin.multiplatform.library` module, whose
 * `androidDeviceTest` is where the capture runs.
 */
public class ViddikAndroidMatrixPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(EXTENSION_NAME, ViddikAndroidMatrixExtension::class.java)
        extension.apiLevels.convention(DEFAULT_API_LEVELS)
        extension.device.convention(DEFAULT_DEVICE)
        extension.systemImageSource.convention(DEFAULT_IMAGE_SOURCE)

        // `-PviddikMatrix.apiLevels=34` beats the build script, for a quick run on one emulator.
        val apiLevels: Provider<List<Int>> =
            target.providers
                .gradleProperty(API_LEVELS_PROPERTY)
                .map(::parseApiLevels)
                .orElse(extension.apiLevels)
        val referenceApi: Provider<Int> = extension.referenceApi.orElse(apiLevels.map { it.max() })
        // The same for the hardware profile: a fixture taller than the default device's safe area fails
        // by name, and trying a taller one should not take an edit.
        val device: Provider<String> = target.providers.gradleProperty(DEVICE_PROPERTY).orElse(extension.device)
        val viddikVersion: Provider<String> =
            target.provider {
                target.extensions
                    .findByType(ViddikExtension::class.java)
                    ?.viddikVersion
                    ?.orNull
                    ?: ViddikPluginVersions.viddik
            }

        val generate =
            target.tasks.register(GENERATE_TASK, ViddikGenerateAndroidMatrixTestTask::class.java) {
                it.description = "Writes the device test class the viddik Android matrix runs."
                it.outputDir.set(target.layout.buildDirectory.dir(GENERATED_DIR))
            }

        target.pluginManager.withPlugin(KMP_ANDROID_PLUGIN_ID) {
            target.wireDeviceTest(extension, apiLevels, device, viddikVersion, generate)
        }

        val reportClasspath =
            target.configurations.create(REPORT_CLASSPATH) { configuration ->
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
                configuration.description = "The viddik matrix report generator, run by $REPORT_TASK."
                configuration.attributes { attributes ->
                    attributes.attribute(
                        Usage.USAGE_ATTRIBUTE,
                        target.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
                    )
                    attributes.attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        target.objects.named(Category::class.java, Category.LIBRARY),
                    )
                    attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        target.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                    )
                    attributes.attribute(
                        Bundling.BUNDLING_ATTRIBUTE,
                        target.objects.named(Bundling::class.java, Bundling.EXTERNAL),
                    )
                    attributes.attribute(
                        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        target.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
                    )
                    attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
                }
                configuration.dependencies.addLater(
                    viddikVersion.map { target.dependencies.create("$REPORT_COORDINATES:$it") },
                )
            }

        // What each managed level's run pulled, copied out by ViddikKeepManagedCapturesTask: AGP's own
        // directories are not to be relied on, see there.
        val managedDir = target.layout.buildDirectory.dir(KEPT_MANAGED_DIR)
        // Levels below MIN_MANAGED_API have no managed device; their captures come from a
        // `connectedAndroidDeviceTest` run on an emulator started by hand, and land here.
        val connectedDir = target.layout.buildDirectory.dir(KEPT_CONNECTED_DIR)
        val keepConnected =
            target.tasks.register(KEEP_CONNECTED_TASK, ViddikKeepConnectedCapturesTask::class.java) { task ->
                task.description = "Keeps what a connected run pulled, which the next connected run would clear."
                task.pulled.set(target.layout.buildDirectory.dir(CONNECTED_OUTPUT_DIR))
                task.kept.set(connectedDir)
                task.results.set(target.layout.buildDirectory.dir(CONNECTED_RESULTS_DIR))
            }
        target.tasks.named { it == CONNECTED_TEST_TASK }.configureEach { it.finalizedBy(keepConnected) }
        // It finalizes the preparation too, so it also runs when the test never does (a compilation error),
        // and marks the levels that run was for; after the test, when there is one.
        keepConnected.configure { it.mustRunAfter(target.tasks.named { name -> name == CONNECTED_TEST_TASK }) }
        target.tasks.named { it == PREPARE_CONNECTED_TASK }.configureEach { it.finalizedBy(keepConnected) }
        val reportDir = target.layout.buildDirectory.dir(REPORT_DIR)
        val report =
            target.tasks.register(REPORT_TASK, JavaExec::class.java) { task ->
                task.group = LifecycleBasePlugin.VERIFICATION_GROUP
                task.description = "Lines up the captures the last Android matrix run pulled back, as an HTML page."
                task.classpath = reportClasspath
                task.mainClass.set(REPORT_MAIN_CLASS)
                task.args(
                    "--input",
                    managedDir.get().asFile.path,
                    "--input",
                    connectedDir.get().asFile.path,
                    "--output",
                    reportDir.get().asFile.path,
                    "--api",
                    apiLevels.get().joinToString(","),
                    "--reference-api",
                    referenceApi.get().toString(),
                )
                task.outputs.dir(reportDir)
                // The captures are on the emulators until a run pulls them back, so there is nothing
                // here an up-to-date check could look at.
                task.outputs.upToDateWhen { false }
                // Gradle says of a JavaExec that fails only "Process ... finished with non-zero exit value 1",
                // and the reason is in the console above it. The report writes the reason down, and the task
                // fails with it.
                task.isIgnoreExitValue = true
                val failure = reportDir.map { it.file(REPORT_FAILURE_FILE) }
                task.doLast { done ->
                    val exit = (done as JavaExec).executionResult.get().exitValue
                    if (exit != 0) {
                        val file = failure.get().asFile
                        val reason =
                            if (file.isFile) file.readText() else "the report generator exited with $exit; see above."
                        throw GradleException("viddik matrix: $reason")
                    }
                }
            }

        target.tasks.register(MATRIX_TASK) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description =
                "Captures every viddik fixture on each API level of viddikAndroidMatrix { } and writes a report."
            task.dependsOn(target.tasks.named { it == MANAGED_DEVICE_GROUP_TASK })
        }
        // After the device run, and after it even when a level failed: the page is most useful exactly
        // then, and a failure there is reported as a missing cell. Two things have to hold for that, both
        // measured (an emulator timing out failed the build with API 31 pulled and no page at all):
        // - a finalizer runs only if a task it finalizes did, so finalizing the group task is not enough,
        //   since that one never starts when a level fails;
        // - the report must not also be a dependency of the matrix task. A task that is both is cancelled
        //   with the rest of the build, whatever it finalizes.
        // Matched by name (`named { }`), so the tasks AGP creates later are found without realizing the
        // others.
        // A level run on its own (`viddikApi34AndroidDeviceTest`) is not a matrix, and would be told off
        // for every level it was not asked for, so the report stands down when that is what is running.
        // Asked for by name, with no device task in the graph, it runs over what the last run pulled.
        val reportWanted = target.objects.property(Boolean::class.java).convention(true)
        val taskPathPrefix = target.path.trimEnd(':') + ":"
        target.gradle.taskGraph.whenReady { graph ->
            val aLevel =
                apiLevels.get().any { level ->
                    listOf(DEVICE_TEST_TASK_SUFFIX, DEVICE_SETUP_TASK_SUFFIX).any { suffix ->
                        graph.hasTask("$taskPathPrefix$DEVICE_PREFIX$level$suffix")
                    }
                }
            val matrixRun = graph.hasTask(taskPathPrefix + MANAGED_DEVICE_GROUP_TASK)
            reportWanted.set(!aLevel || matrixRun)
            val (managed, connected) = apiLevels.get().partition { it >= MIN_MANAGED_API }
            val managedRun =
                matrixRun ||
                    managed.any { level ->
                        listOf(DEVICE_TEST_TASK_SUFFIX, DEVICE_SETUP_TASK_SUFFIX).any { suffix ->
                            graph.hasTask("$taskPathPrefix$DEVICE_PREFIX$level$suffix")
                        }
                    }
            // Said when it applies and not on every configuration: a connected run, or a build that runs no
            // device at all, has no use for either line.
            if (managedRun) target.warnAboutTheRenderer()
            if (matrixRun && connected.isNotEmpty()) target.explainConnectedLevels(connected)
        }
        val matrixTasks =
            target.tasks.named { name ->
                name == MANAGED_DEVICE_GROUP_TASK || deviceLevel(name) != null
            }
        matrixTasks.configureEach { it.finalizedBy(report) }
        report.configure { task ->
            task.mustRunAfter(matrixTasks)
            task.mustRunAfter(target.tasks.named { it == KEEP_CONNECTED_TASK || keepLevel(it) != null })
            task.onlyIf("not a single level run on its own") { reportWanted.get() }
        }
        // A device run is never up to date and never from the cache. Gradle judges the task by its test
        // results, and the captures are not among the outputs it restores: measured, an unchanged
        // viddikApi31AndroidDeviceTest came back UP-TO-DATE (and once FROM-CACHE) with its results intact
        // and not one capture pulled, so its column silently disappeared from the page.
        target.tasks
            .named { it.startsWith(DEVICE_PREFIX) && it.endsWith(DEVICE_TEST_TASK_SUFFIX) }
            .configureEach { task ->
                // Keep what this level pulled the moment it is pulled; see ViddikKeepManagedCapturesTask.
                val level = deviceLevel(task.name)
                if (level != null) {
                    val started = target.layout.buildDirectory.file("$KEPT_MANAGED_DIR/.api$level-started")
                    task.doFirst {
                        started.get().asFile.apply {
                            parentFile.mkdirs()
                            writeText("")
                        }
                    }
                    task.finalizedBy(keepTaskName(level))
                }
                task.outputs.upToDateWhen { false }
                task.outputs.cacheIf("the captures it pulls are not outputs Gradle can restore") { false }
                // A fixture failing on one level must not cost the levels after it, which now wait their
                // turn and would never start. The failure still fails the build: the report does that,
                // once the page is written, for every capture that did not come back.
                (task as? VerificationTask)?.ignoreFailures = true
            }
        // One level at a time. Measured on a real suite (876 fixtures): three emulators running together
        // stopped at the same minute with qemu at 0% CPU, and the same levels went through one by one.
        // Ordered rather than limited through an AGP option, so it holds for whatever invoked them.
        // Setup boots an emulator too, so it waits for every task of the lower levels, tests included.
        // Ordering setups among themselves and tests among themselves left viddikApi36Setup running
        // beside viddikApi34AndroidDeviceTest: the test's emulator vanished at fixture 18 of 876, and
        // the setup timed out.
        target.tasks.named { deviceLevel(it) != null }.configureEach { task ->
            val level = deviceLevel(task.name)
            task.mustRunAfter(
                target.tasks.named { other ->
                    deviceLevel(other).let { it != null && level != null && it < level }
                },
            )
        }

        target.afterEvaluate { project -> project.checkPreconditions(apiLevels.get()) }
    }

    private fun Project.wireDeviceTest(
        extension: ViddikAndroidMatrixExtension,
        apiLevels: Provider<List<Int>>,
        deviceProfile: Provider<String>,
        viddikVersion: Provider<String>,
        generate: org.gradle.api.tasks.TaskProvider<ViddikGenerateAndroidMatrixTestTask>,
    ) {
        val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.sourceSets.matching { it.name == DEVICE_TEST_SOURCE_SET }.configureEach { sourceSet ->
            sourceSet.kotlin.srcDir(generate.flatMap { it.outputDir })
        }
        configurations.matching { it.name == DEVICE_TEST_IMPLEMENTATION }.configureEach { configuration ->
            configuration.dependencies.addLater(viddikVersion.map { dependencies.create("$CAPTURE_COORDINATES:$it") })
        }

        // Device tests have to exist from the moment the target does: Compose Multiplatform wires its
        // resources into a compilation when that compilation appears, and one created later — from
        // finalizeDsl, say — gets a `copyAndroidDeviceTestComposeResourcesToAndroidAssets` with no
        // output directory, which fails the build.
        //
        // AGP allows `withDeviceTest` once per module, and a second call is an error rather than more
        // configuration. So a module that already has device tests keeps them: the compilation is the
        // same DSL object the call would have handed over, and the matrix configures that instead.
        kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach { android ->
            val existing = android.deviceTestCompilation()
            if (existing != null) existing.configureForMatrix() else android.withDeviceTest { configureForMatrix() }
        }

        val androidComponents = extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
        val prepareConnected =
            tasks.register(PREPARE_CONNECTED_TASK, ViddikPrepareConnectedEmulatorsTask::class.java) { task ->
                task.description = "Gets the emulators of a connected run ready for AGP to pull captures from."
                task.adb.set(androidComponents.sdkComponents.adb)
                task.androidSerial.set(providers.environmentVariable(ANDROID_SERIAL))
                task.pending.set(layout.buildDirectory.dir("$KEPT_CONNECTED_DIR/$PENDING_DIR"))
            }
        tasks.named { it == CONNECTED_TEST_TASK }.configureEach { it.dependsOn(prepareConnected) }
        // First, before the compilation: what it notes is how a run that fails to compile is told apart from
        // one that ran. Only an order, so it binds only when the preparation is in the graph at all.
        tasks.named { it != PREPARE_CONNECTED_TASK }.configureEach { it.mustRunAfter(prepareConnected) }

        // The devices are declared once the build script is done with `viddikAndroidMatrix { }`, and
        // before AGP turns them into tasks — which is what finalizeDsl is for.
        androidComponents.finalizeDsl { dsl ->
            val levels = apiLevels.get()
            // Compose Multiplatform 1.12 registers a task copying compose resources into the device
            // test APK, and leaves its output unset unless the module has Android resources switched on
            // — a failure in any module with `withDeviceTest {}` and no `androidResources { enable }`,
            // the matrix or not. Such a module ships no compose resources on Android in the first
            // place, so there is nothing to copy, and the task is turned off rather than the module's
            // own resource processing turned on behind its back.
            if (!dsl.androidResources.enable) {
                tasks.named { it == COMPOSE_DEVICE_TEST_RESOURCES_TASK }.configureEach { it.enabled = false }
            }
            val deviceTest =
                kotlin.targets
                    .withType(KotlinMultiplatformAndroidLibraryTarget::class.java)
                    .single()
                    .deviceTestCompilation()
                    ?: throw GradleException(
                        "$path has no Android device test compilation for the viddik matrix to run in.",
                    )
            // `-PviddikMatrix.filter=Buttons|Surfaces`: a few groups of a large suite, for a quick look at a
            // change. A filtered run adds to what each level already has rather than replacing it (keepRuns).
            // Encoded, because AGP writes these arguments into an `am instrument` command line unquoted. A
            // space there ended the command ("Error: Invalid userId -2"): no test ran, nothing was pulled,
            // and the build stayed green.
            providers.gradleProperty(FILTER_PROPERTY).orNull?.let {
                deviceTest.instrumentationRunnerArguments[FILTER_ARGUMENT] =
                    URLEncoder.encode(it, Charsets.UTF_8.name())
            }
            val managedDevices = deviceTest.managedDevices
            val group = managedDevices.groups.maybeCreate(MANAGED_DEVICE_GROUP)
            // Two AGP limits, both measured on 9.4.1. Below 27 it refuses to create a managed device ("API
            // level 26 and lower is currently not supported for Gradle Managed devices"), at task creation,
            // so the whole build fails. On 27 and 28 it creates one, runs every test green and pulls
            // nothing back ("additionalTestOutput is not supported on Gradle managed devices running API
            // level < 29"), so the column would silently be empty. Both stay in the report and are
            // captured by a connected run instead.
            levels.filter { it >= MIN_MANAGED_API }.forEach { api ->
                tasks.register(keepTaskName(api), ViddikKeepManagedCapturesTask::class.java) { task ->
                    task.description = "Keeps what the API $api managed device pulled, for the matrix report."
                    task.pulled.from(
                        layout.buildDirectory.dir(MANAGED_DEVICE_INTERMEDIATES_DIR),
                        layout.buildDirectory.dir(MANAGED_DEVICE_OUTPUT_DIR),
                    )
                    task.kept.set(layout.buildDirectory.dir(KEPT_MANAGED_DIR))
                    task.level.set(api)
                    task.started.set(layout.buildDirectory.file("$KEPT_MANAGED_DIR/.api$api-started"))
                }
                val device = managedDevices.localDevices.maybeCreate("$DEVICE_PREFIX$api")
                device.device = deviceProfile.get()
                device.apiLevel = api
                device.systemImageSource = extension.systemImageSource.get()
                device.require64Bit = true
                group.targetDevices.add(device)
            }
        }
    }

    /**
     * AGP's own default is an option the emulator rejects ("Selected GPU option 'auto-no-window' is not
     * valid"), and what it falls back to is a software Vulkan that can take qemu down. Measured on fixtures
     * with AGSL shaders, a fresh boot per run: llvmpipe died in 3 of 8, swiftshader in 3 of 8, the host GPU in
     * 0 of 8. An option AGP reads for itself, so a plugin cannot set it.
     */
    private fun Project.warnAboutTheRenderer() {
        if (providers.gradleProperty(EMULATOR_GPU_PROPERTY).orNull == "host") return
        logger.lifecycle(
            "viddik matrix: $EMULATOR_GPU_PROPERTY is not `host`. A software renderer can crash the " +
                "emulator on fixtures with shaders, which loses that level's captures; on a machine with " +
                "a GPU, put `$EMULATOR_GPU_PROPERTY=host` in gradle.properties (docs/android-matrix.md).",
        )
    }

    private fun Project.explainConnectedLevels(connected: List<Int>) {
        logger.lifecycle(
            "viddik matrix: API ${connected.joinToString()} is below what Gradle Managed Devices " +
                "can capture (API $MIN_MANAGED_API: no device below 27, no test output pulled below " +
                "29). Start an emulator of that level and run `ANDROID_SERIAL=<its serial> ./gradlew " +
                "$path:connectedAndroidDeviceTest`; the report picks its captures up from " +
                "build/$KEPT_CONNECTED_DIR (docs/android-matrix.md, \"Levels below 29\").",
        )
    }

    private fun KotlinMultiplatformAndroidLibraryTarget.deviceTestCompilation():
        KotlinMultiplatformAndroidDeviceTestCompilation? =
        compilations.withType(KotlinMultiplatformAndroidDeviceTestCompilation::class.java).firstOrNull()

    /** A module that set its own runner keeps it; turning animations off is the matrix's one opinion. */
    private fun KotlinMultiplatformAndroidDeviceTest.configureForMatrix() {
        if (instrumentationRunner == null) instrumentationRunner = INSTRUMENTATION_RUNNER
        animationsDisabled = true
    }

    private fun Project.checkPreconditions(levels: List<Int>) {
        if (!pluginManager.hasPlugin(KMP_ANDROID_PLUGIN_ID)) {
            throw GradleException(
                "$path applies io.github.youndie.viddik.android-matrix, which runs on the Android target of a " +
                    "Kotlin Multiplatform module: apply $KMP_ANDROID_PLUGIN_ID and declare `kotlin { android { } }`.",
            )
        }
        val viddik =
            extensions.findByType(ViddikExtension::class.java)
                ?: throw GradleException(
                    "$path applies io.github.youndie.viddik.android-matrix without io.github.youndie.viddik, " +
                        "which generates the registry the matrix captures. Apply both.",
                )
        if (!viddik.showroomTargets.get()) {
            throw GradleException(
                "The Android matrix in $path needs `viddik { showroomTargets = true }`: without it the registry is " +
                    "generated into a desktop test source set, which no Android compilation can see.",
            )
        }
        if (levels.isEmpty()) throw GradleException("viddikAndroidMatrix { apiLevels } in $path is empty.")
        levels.filter { it < MIN_API }.takeIf { it.isNotEmpty() }?.let { low ->
            throw GradleException(
                "viddikAndroidMatrix { apiLevels } in $path contains ${low.joinToString()}: the capture needs " +
                    "API $MIN_API or higher (PixelCopy from 26, a screenshot of the display on 24 and 25).",
            )
        }
    }

    internal companion object {
        const val EXTENSION_NAME = "viddikAndroidMatrix"
        const val MATRIX_TASK = "viddikAndroidMatrix"
        const val REPORT_TASK = "viddikAndroidMatrixReport"

        /** Has to match what viddik-matrix-report writes; the two modules share no code. */
        const val REPORT_FAILURE_FILE = "failure.txt"
        const val GENERATE_TASK = "viddikGenerateAndroidMatrixTest"
        const val API_LEVELS_PROPERTY = "viddikMatrix.apiLevels"
        const val DEVICE_PROPERTY = "viddikMatrix.device"
        const val FILTER_PROPERTY = "viddikMatrix.filter"
        const val EMULATOR_GPU_PROPERTY = "android.testoptions.manageddevices.emulator.gpu"
        const val ANDROID_SERIAL = "ANDROID_SERIAL"

        /** `ViddikAndroidCapture.FILTER_ARGUMENT`, spelled out: this module does not depend on that one. */
        const val FILTER_ARGUMENT = "viddikFilter"

        const val DEFAULT_DEVICE = "Pixel 6"
        const val DEFAULT_IMAGE_SOURCE = "aosp"
        const val MIN_API = 24

        const val KMP_ANDROID_PLUGIN_ID = "com.android.kotlin.multiplatform.library"
        const val INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val DEVICE_TEST_SOURCE_SET = "androidDeviceTest"
        const val DEVICE_TEST_IMPLEMENTATION = "androidDeviceTestImplementation"
        const val COMPOSE_DEVICE_TEST_RESOURCES_TASK = "copyAndroidDeviceTestComposeResourcesToAndroidAssets"
        const val MANAGED_DEVICE_GROUP = "viddikMatrix"
        const val MANAGED_DEVICE_GROUP_TASK = "viddikMatrixGroupAndroidDeviceTest"
        const val MANAGED_DEVICE_INTERMEDIATES_DIR = "intermediates/managed_device_android_test_additional_output"
        const val MANAGED_DEVICE_OUTPUT_DIR = "outputs/managed_device_android_test_additional_output"
        const val CONNECTED_OUTPUT_DIR = "outputs/connected_android_test_additional_output"
        const val CONNECTED_RESULTS_DIR = "outputs/androidTest-results/connected"
        const val PREPARE_CONNECTED_TASK = "viddikPrepareConnectedEmulators"
        const val KEPT_CONNECTED_DIR = "viddik-matrix/connected"
        const val KEPT_MANAGED_DIR = "viddik-matrix/managed"
        const val CONNECTED_TEST_TASK = "connectedAndroidDeviceTest"
        const val KEEP_CONNECTED_TASK = "viddikKeepConnectedCaptures"
        const val GENERATED_DIR = "generated/viddik/androidMatrix"
        const val REPORT_DIR = "reports/viddik-matrix"
        const val REPORT_CLASSPATH = "viddikMatrixReportClasspath"
        const val REPORT_MAIN_CLASS = "io.github.youndie.viddik.matrix.ViddikMatrixReportKt"
        const val CAPTURE_COORDINATES = "io.github.youndie.viddik:viddik-android-capture"
        const val REPORT_COORDINATES = "io.github.youndie.viddik:viddik-matrix-report"

        fun parseApiLevels(value: String): List<Int> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { level ->
                level.toIntOrNull() ?: throw GradleException("-P$API_LEVELS_PROPERTY: '$level' is not an API level")
            }
    }
}
