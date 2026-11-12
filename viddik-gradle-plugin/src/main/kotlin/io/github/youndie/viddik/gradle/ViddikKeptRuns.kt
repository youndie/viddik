package io.github.youndie.viddik.gradle

import java.io.File

/**
 * Keeps one level's runs (each a capture directory and where under [level] it goes) in [level]: in place of
 * what was there, or, when every run was a filtered one on the same device profile as what is kept, on top
 * of it.
 *
 * Replacing was the only behaviour once, and it made a filtered run expensive: a quick look at a few groups
 * emptied the rest of the column, so on a large suite the only way to a full page was a full run of every
 * level, 6 to 16 minutes each, for a change that touched a few dozen fixtures.
 */
internal fun keepRuns(
    runs: List<Pair<File, String>>,
    level: File,
) {
    val existing = keptCapture(level)
    if (existing != null && runs.all { (capture, _) -> appendsTo(capture, existing) }) {
        runs.forEach { (capture, _) -> mergeRun(capture, existing) }
        // The level's latest run pulled something after all; what the column shows is a mix, and its age is
        // still that of the run it started from.
        level.resolve(PULLED_NOTHING_MARKER).delete()
        return
    }
    level.deleteRecursively()
    runs.forEach { (capture, relative) -> capture.copyRecursively(level.resolve(relative), overwrite = true) }
}

/** The capture a level holds, or null. Nested as the connected run pulled it, so it is found, not assumed. */
private fun keptCapture(level: File): File? =
    level
        .walkTopDown()
        .filter { it.isFile && it.name == CAPTURE_MARKER }
        .maxByOrNull { it.lastModified() }
        ?.parentFile

/**
 * Whether [capture] adds to [existing] rather than replacing it: it was filtered, and both came from the same
 * device profile, since captures of two densities are two sizes of every fixture and do not belong in one
 * column. An existing capture with no fixture list predates the list, and cannot say which of its files are
 * rows; it is replaced.
 */
internal fun appendsTo(
    capture: File,
    existing: File,
): Boolean =
    capture.resolve(FILTER_FILE).isFile &&
        existing.resolve(EXPECTED_FILE).isFile &&
        profileOf(capture.resolve(CAPTURE_MARKER)) == profileOf(existing.resolve(CAPTURE_MARKER))

private fun profileOf(deviceFile: File): List<String?> {
    val text = deviceFile.takeIf { it.isFile }?.readText().orEmpty()
    return PROFILE_FIELDS.map { field -> Regex(""""$field"\s*:\s*("[^"]*"|[^,}]+)""").find(text)?.groupValues?.get(1) }
}

private val PROFILE_FIELDS = listOf("sdkInt", "model", "density")

/**
 * Puts a filtered run's captures into [into]: its PNGs replace theirs, its fixture list's lines replace
 * theirs and add the new ones, and what it says about its fixtures (a failure, a fixture too large) replaces
 * what the earlier run said about them. `device.json` stays the earlier one's, so the column's age is that of
 * the run most of it came from.
 */
internal fun mergeRun(
    capture: File,
    into: File,
) {
    val lines = linesOf(capture.resolve(EXPECTED_FILE))
    val files = lines.map { it.substringBefore('\t') }.toSet()
    files.forEach { into.resolve(it).delete() }
    capture
        .listFiles { file -> file.isFile && file.extension == "png" }
        .orEmpty()
        .forEach { it.copyTo(into.resolve(it.name), overwrite = true) }

    val expected = LinkedHashMap<String, String>()
    linesOf(into.resolve(EXPECTED_FILE)).forEach { expected[it.substringBefore('\t')] = it }
    lines.forEach { expected[it.substringBefore('\t')] = it }
    into.resolve(EXPECTED_FILE).writeLines(expected.values)

    listOf(FAILURES_FILE, UNFIT_FILE).forEach { name ->
        val kept = linesOf(into.resolve(name)).filter { it.substringBefore('\t') !in files }
        into.resolve(name).writeLines(kept + linesOf(capture.resolve(name)))
    }
}

private fun linesOf(file: File): List<String> =
    if (file.isFile) {
        file.readLines().filter {
            it.isNotBlank()
        }
    } else {
        emptyList()
    }

private fun File.writeLines(lines: Collection<String>) = writeText(lines.joinToString("") { "$it\n" })

// The capture's file names, spelled out: this module does not depend on viddik-android-capture.
internal const val EXPECTED_FILE = "fixtures.txt"
internal const val FAILURES_FILE = "failures.txt"
internal const val UNFIT_FILE = "does-not-fit.txt"
internal const val FILTER_FILE = "filter.txt"
