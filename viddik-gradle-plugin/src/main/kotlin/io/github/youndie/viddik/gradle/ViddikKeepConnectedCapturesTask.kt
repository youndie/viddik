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
 * It is also what notices a run that captured everything and pulled nothing. Measured: connected runs on
 * API 26 and 27 took all 658 captures and brought back none, with the same outcome as any other run of that
 * suite. AGP uninstalls the test APK at the end of the run, and the captures go with it, so there is nothing
 * left to pull from here; the run has to be repeated, and the build says so rather than leaving it to a line
 * in the console. The level keeps what an earlier run left, and a [PULLED_NOTHING_MARKER] beside it has the
 * page say so.
 *
 * And a run that never got to the device: a build that failed on compilation left the level's earlier
 * captures where they were, with nothing to tell them from this run's. [ViddikPrepareConnectedEmulatorsTask]
 * runs before anything else of the module and notes which levels the run is for ([PENDING_DIR]); this task
 * finalizes it as well as the test, so it runs when the test does not, and marks those levels.
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
        val keptDir = kept.get().asFile
        val pendingDir = keptDir.resolve(PENDING_DIR)
        val pending = pendingLevels(pendingDir)
        // Anything older than the run's start is an earlier run's, results and pulled captures alike: AGP
        // empties its directories when its test starts, so when the test never ran they hold the last run's
        // (measured: a run that failed to compile kept yesterday's pull of API 26 as if it were new).
        val since = pendingDir.resolve(RUN_STARTED).takeIf { it.isFile }?.lastModified() ?: 0L
        pendingDir.deleteRecursively()

        val keptLevels = keepCaptures(pulled.get().asFile, keptDir, since)
        if (keptLevels.isNotEmpty()) {
            (pending - keptLevels).forEach { markPulledNothing(keptDir.resolve("api$it")) }
            return
        }
        val resultsDir = results.get().asFile
        if (!testRanIn(resultsDir, since)) {
            pending.forEach { markPulledNothing(keptDir.resolve("api$it")) }
            val levels = if (pending.isEmpty()) "" else " (API ${pending.sorted().joinToString()})"
            logger.warn(
                "viddik matrix: the connected run did not get to the device$levels: the build failed before it, " +
                    "or the test APK did not start." +
                    if (pending.isEmpty()) "" else " The page shows those levels as an earlier run's.",
            )
            return
        }
        // A module's own device tests go through this task too, and need not capture anything.
        if (!captureRanIn(resultsDir, since)) {
            logger.warn("viddik matrix: this connected run did not run the capture, and pulled no captures back.")
            return
        }
        val level = levelInLogcat(resultsDir, since) ?: pending.singleOrNull()
        if (level != null) markPulledNothing(keptDir.resolve("api$level"))
        throw GradleException(
            "viddik matrix: the capture ran on ${level?.let { "API $it" } ?: "the device"} and nothing was pulled " +
                "back, so this level has no new captures and its test APK, with the captures in it, is gone. Run it " +
                "again. " + emptyPullExplanation(whereCapturesWent(resultsDir, since)),
        )
    }
}

/**
 * Copies every capture directory under [from] (the one holding a `device.json`) to `api<level>/` under
 * [into], through [keepRuns]: replacing what an earlier run left for that level, or adding to it if this run
 * was filtered, and nothing of the other levels. The level is the one the device reported: a directory name
 * says only which serial it had, so a level run again on another port used to leave its earlier run beside
 * the new one.
 *
 * Only captures whose `device.json` is from [since] on: the rest is a run before this one.
 *
 * @return the levels it kept.
 */
internal fun keepCaptures(
    from: File,
    into: File,
    since: Long = 0L,
): Set<Int> {
    val captures =
        from
            .walkTopDown()
            .filter { it.name == CAPTURE_MARKER && it.lastModified() >= since }
            .mapNotNull { it.parentFile }
            .toList()
            .groupBy { capture -> levelOf(capture.resolve(CAPTURE_MARKER)) }
    captures.forEach { (level, runs) ->
        keepRuns(runs.map { it to it.relativeTo(from).path }, into.resolve("api$level"))
    }
    return captures.keys.mapNotNull { it.toIntOrNull() }.toSet()
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
            "The device wrote them to $where, the directory AGP itself passed to the run, and AGP then did " +
                "not pull it. Seen on API 26 and 27, on some runs and not on others, after a cold boot as well " +
                "as after a run of another level; the cause is not known, and running it again is what has " +
                "worked. Running with --info shows whether AGP starts the pull at all."
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

/**
 * Leaves a note in a level's kept directory that its latest run pulled nothing, for the report to put on that
 * level's column: what the column shows is an earlier run's. A run that does pull replaces the directory, note
 * and all.
 */
internal fun markPulledNothing(level: File) {
    level.mkdirs()
    // Empty: the file's own time is the run's, and the report reads that.
    level.resolve(PULLED_NOTHING_MARKER).writeText("")
}

/** The level the capture said it ran on, in the logcat AGP saves beside the results. */
internal fun levelInLogcat(
    results: File,
    since: Long = 0L,
): Int? =
    logcatLines(results, since).firstNotNullOfOrNull {
        CAPTURING_ON
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toInt()
    }

/** Whether the run whose results are under [results] has the matrix's one test in it. */
internal fun captureRanIn(
    results: File,
    since: Long = 0L,
): Boolean = resultFiles(results, since).any { CAPTURE_TEST_CASE in it.readText() }

/** Whether any test wrote results there at or after [since]. */
internal fun testRanIn(
    results: File,
    since: Long = 0L,
): Boolean = resultFiles(results, since).any()

private fun resultFiles(
    results: File,
    since: Long,
): Sequence<File> =
    results
        .walkTopDown()
        .filter { it.isFile && it.extension == "xml" && it.lastModified() >= since }

/** What the capture said in the logcat AGP saves beside the results, which is all that is left of it. */
internal fun whereCapturesWent(
    results: File,
    since: Long = 0L,
): String? =
    logcatLines(results, since).firstNotNullOfOrNull {
        CAPTURES_GO_TO
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

private fun logcatLines(
    results: File,
    since: Long,
): Sequence<String> =
    results
        .walkTopDown()
        .filter { it.isFile && it.name.startsWith("logcat-") && it.name.endsWith("-captureAll.txt") }
        .filter { it.lastModified() >= since }
        .flatMap { it.readLines().asSequence() }

/**
 * Under the kept connected directory: one empty `api<N>` file per level a connected run is about to capture,
 * written by [ViddikPrepareConnectedEmulatorsTask] and cleared by [ViddikKeepConnectedCapturesTask].
 */
internal const val PENDING_DIR = ".pending"

/** In [PENDING_DIR], whatever the levels: its time is when the run started. */
internal const val RUN_STARTED = "started"

/** The levels of the files in [PENDING_DIR]. */
internal fun pendingLevels(dir: File): Set<Int> =
    dir
        .listFiles()
        .orEmpty()
        .mapNotNull { it.name.removePrefix("api").toIntOrNull() }
        .toSet()

internal const val CAPTURE_MARKER = "device.json"
private const val CAPTURE_TEST_CASE = "name=\"captureAll\""
private val SDK_INT = Regex(""""sdkInt"\s*:\s*(\d+)""")
private val CAPTURES_GO_TO = Regex("Captures go to (.+)")
private val CAPTURING_ON = Regex("Capturing on API (\\d+)")

/** Has to match what viddik-matrix-report reads; the two modules share no code. */
internal const val PULLED_NOTHING_MARKER = "pulled-nothing-at"
