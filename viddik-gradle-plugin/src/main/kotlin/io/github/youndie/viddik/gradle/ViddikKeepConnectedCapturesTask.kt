package io.github.youndie.viddik.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Keeps what a `connectedAndroidDeviceTest` run pulled, level by level.
 *
 * The levels below what Gradle Managed Devices can capture are run one emulator at a time, and each run
 * empties AGP's output directory first, so after 24, 26 and 27 only 27 was left for the report to read.
 * This copies each run out to a directory nothing clears, for the report to read instead.
 *
 * It is also what notices a run that captured everything and pulled nothing. Measured: one connected run
 * in four took all 658 captures and brought back none, with the same outcome as any other run of that
 * suite, whose failing tests were fixtures too large for the device. AGP uninstalls the test APK at the end
 * of the run, and the captures go with it, so there is nothing left to pull from here; the run has to be
 * repeated, and the build says so rather than leaving it to a line in the console.
 */
@DisableCachingByDefault(because = "It copies files whose only reader is the report, and has to run after every run.")
public abstract class ViddikKeepConnectedCapturesTask : DefaultTask() {
    @get:Internal
    public abstract val pulled: DirectoryProperty

    @get:Internal
    public abstract val kept: DirectoryProperty

    /** Where AGP puts the run's test results, which say whether the capture ran at all. */
    @get:Internal
    public abstract val results: DirectoryProperty

    @TaskAction
    public fun keep() {
        val copied = keepCaptures(pulled.get().asFile, kept.get().asFile)
        if (copied > 0) return
        val resultsDir = results.get().asFile
        // A module's own device tests go through this task too, and need not capture anything.
        if (!captureRanIn(resultsDir)) {
            logger.warn("viddik matrix: this connected run did not run the capture, and pulled no captures back.")
            return
        }
        throw GradleException(
            "viddik matrix: the capture ran on the device and nothing was pulled back, so this level has no new " +
                "captures and its test APK, with the captures in it, is gone. Run it again. " +
                emptyPullExplanation(whereCapturesWent(resultsDir)),
        )
    }
}

/**
 * Copies every capture directory under [from] (the one holding a `device.json`) to `api<level>/` under
 * [into], replacing everything an earlier run left for that level and nothing of the others. The level is
 * the one the device reported: a directory name says only which serial it had, so a level run again on
 * another port used to leave its earlier run beside the new one.
 *
 * @return how many directories it copied.
 */
internal fun keepCaptures(
    from: File,
    into: File,
): Int {
    val captures =
        from
            .walkTopDown()
            .filter { it.name == CAPTURE_MARKER }
            .mapNotNull { it.parentFile }
            .associateWith { capture -> "api${levelOf(capture.resolve(CAPTURE_MARKER))}" }
    captures.values.toSet().forEach { into.resolve(it).deleteRecursively() }
    captures.forEach { (capture, level) ->
        capture.copyRecursively(into.resolve(level).resolve(capture.relativeTo(from)))
    }
    return captures.size
}

/**
 * Why a connected run that captured everything pulled nothing, as far as where the capture wrote tells.
 * The capture writes under `test_data/` only when AGP passed it that directory, and to its own files
 * directory when AGP passed none.
 */
internal fun emptyPullExplanation(where: String?): String =
    when {
        where == null -> {
            "Logcat does not say where the device wrote them (docs/android-matrix.md, \"Levels below 29\")."
        }

        "/test_data/" in where -> {
            "The device wrote them to $where, the directory AGP itself passed to the run: AGP then did not " +
                "pull it, which is not a MediaStore or boot timing problem. Seen twice, both times on the first " +
                "connected run of API 26 after a boot, and the run after it pulled everything; the cause is not known."
        }

        else -> {
            "The device wrote them to $where, its own fallback: AGP passed no directory, because MediaStore had no " +
                "/sdcard/Android when it asked. viddikPrepareConnectedEmulators adds it to emulators; on another " +
                "device add it by hand (docs/android-matrix.md, \"Levels below 29\")."
        }
    }

internal fun levelOf(deviceFile: File): String =
    SDK_INT
        .find(deviceFile.readText())
        ?.groupValues
        ?.get(1)
        ?: "-unknown"

/** Whether the run whose results are under [results] has the matrix's one test in it. */
internal fun captureRanIn(results: File): Boolean =
    results
        .walkTopDown()
        .filter { it.isFile && it.extension == "xml" }
        .any { CAPTURE_TEST_CASE in it.readText() }

/** What the capture said in the logcat AGP saves beside the results, which is all that is left of it. */
internal fun whereCapturesWent(results: File): String? =
    results
        .walkTopDown()
        .filter { it.isFile && it.name.startsWith("logcat-") && it.name.endsWith("-captureAll.txt") }
        .flatMap { it.readLines().asSequence() }
        .firstNotNullOfOrNull {
            CAPTURES_GO_TO
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }

internal const val CAPTURE_MARKER = "device.json"
private const val CAPTURE_TEST_CASE = "name=\"captureAll\""
private val SDK_INT = Regex(""""sdkInt"\s*:\s*(\d+)""")
private val CAPTURES_GO_TO = Regex("Captures go to (.+)")
