package io.github.youndie.viddik.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * The machine-readable half of a verification (#80): one JSON entry per fixture it compared, and a
 * `summary.json` that gathers them.
 *
 * The console says the same things in good sentences, and that is the trouble for a script or an
 * agent driving `viddikVerify`: it has to parse Gradle's output to learn which fixture failed, by how
 * much, where, and which files show it. These files say it as data, beside the PNGs, so a CI artifact
 * of the reports directory carries both.
 *
 * Each fixture writes its own entry ([RESULTS_DIR]/`<golden name>.json`), the way #63 writes its
 * three PNGs: a shard or a `--component` run sees only some fixtures and must not wipe another fork's.
 * The summary is rebuilt from every entry by the run's trailing test ([VERIFY_SUMMARY_TEST_NAME]), once
 * per fork; the last fork to finish writes the fullest one, and the entries stay the authority.
 */
internal object VerifyReport {
    enum class Status(
        val json: String,
    ) {
        MATCH("match"),
        MISMATCH("mismatch"),
        SIZE("size"),
        MISSING_GOLDEN("missingGolden"),
        UNREADABLE_GOLDEN("unreadableGolden"),
        ERROR("error"),
    }

    class Thresholds(
        val tolerancePercent: Double,
        val toleranceFrom: String,
        val channelTolerance: Int,
        val minMismatchedPixels: Int,
        val floorChannelDelta: Int,
        val pronouncedPixelBudget: Int,
    )

    class Entry(
        val group: String,
        val name: String,
        val golden: File,
        val status: Status,
        val thresholds: Thresholds,
        val message: String? = null,
        val expectedSize: Pair<Int, Int>? = null,
        val actualSize: Pair<Int, Int>? = null,
        val diff: DiffResult? = null,
        val diffFile: File? = null,
        val actualFile: File? = null,
        val expectedFile: File? = null,
        val cropFile: File? = null,
    )

    fun write(
        reportsDir: File,
        entry: Entry,
    ) {
        val dir = File(reportsDir, RESULTS_DIR).apply { mkdirs() }
        writeAtomically(File(dir, entry.golden.name.removeSuffix(".png") + ".json"), json(entry))
    }

    /**
     * Gathers every entry into `summary.json`, after dropping the entries of fixtures the module no
     * longer has ([goldenNames]): a renamed fixture would otherwise stay in the summary for good.
     */
    fun writeSummary(
        reportsDir: File,
        goldenNames: Set<String>,
    ): File {
        val dir = File(reportsDir, RESULTS_DIR)
        val entries =
            dir
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .sortedBy { it.name }
                .filter { file ->
                    val known = file.nameWithoutExtension + ".png" in goldenNames
                    if (!known) file.delete()
                    known
                }.mapNotNull { file ->
                    // When it was verified is the entry file's own time: the file system stamps it, so
                    // nothing here reads a clock.
                    val text = runCatching { file.readText().trim() }.getOrNull() ?: return@mapNotNull null
                    text.removeSuffix("}") + ", \"verifiedAt\": ${file.lastModified()}}"
                }
        val counts =
            Status.entries.associateWith { status -> entries.count { "\"status\": \"${status.json}\"" in it } }
        val summary =
            buildString {
                append("{\n")
                append("  \"compared\": ${entries.size},\n")
                append("  \"failed\": ${entries.size - counts.getValue(Status.MATCH)},\n")
                append("  \"counts\": {")
                append(counts.entries.joinToString(", ") { (status, count) -> "\"${status.json}\": $count" })
                append("},\n")
                append("  \"fixtures\": [\n")
                append(entries.joinToString(",\n") { "    $it" })
                append("\n  ]\n}\n")
            }
        val file = File(reportsDir, SUMMARY_FILE)
        writeAtomically(file, summary)
        return file
    }

    internal fun json(entry: Entry): String =
        buildString {
            append("{")
            append("\"group\": ${entry.group.jsonQuoted()}, ")
            append("\"name\": ${entry.name.jsonQuoted()}, ")
            append("\"golden\": ${entry.golden.invariantSeparatorsPath.jsonQuoted()}, ")
            append("\"status\": \"${entry.status.json}\", ")
            append("\"message\": ${entry.message?.jsonQuoted() ?: "null"}, ")
            append("\"expected\": ${size(entry.expectedSize)}, ")
            append("\"actual\": ${size(entry.actualSize)}, ")
            val diff = entry.diff
            if (diff != null) {
                append("\"mismatchedPixels\": ${diff.mismatchedPixels}, ")
                append("\"pronouncedPixels\": ${diff.pronouncedPixels}, ")
                append("\"totalPixels\": ${diff.totalPixels}, ")
                append("\"mismatchPercent\": ${"%.4f".format(Locale.ROOT, diff.mismatchPercent)}, ")
                append("\"maxChannelDelta\": ${diff.maxChannelDelta}, ")
                append("\"regions\": ${regionsJson(diff.regions)}, ")
            }
            val t = entry.thresholds
            append("\"thresholds\": {")
            append("\"tolerancePercent\": ${t.tolerancePercent}, ")
            append("\"toleranceFrom\": ${t.toleranceFrom.jsonQuoted()}, ")
            append("\"channelTolerance\": ${t.channelTolerance}, ")
            append("\"minMismatchedPixels\": ${t.minMismatchedPixels}, ")
            append("\"floorChannelDelta\": ${t.floorChannelDelta}, ")
            val budget = t.pronouncedPixelBudget.takeIf { it != UNLIMITED_PRONOUNCED_PIXELS }
            append("\"pronouncedPixelBudget\": ${budget ?: "null"}}, ")
            append("\"files\": {")
            append("\"diff\": ${entry.diffFile.pathJson()}, ")
            append("\"actual\": ${entry.actualFile.pathJson()}, ")
            append("\"expected\": ${entry.expectedFile.pathJson()}, ")
            append("\"crop\": ${entry.cropFile.pathJson()}}")
            append("}")
        }

    private fun size(size: Pair<Int, Int>?): String =
        size?.let { (width, height) -> "{\"width\": $width, \"height\": $height}" } ?: "null"

    private fun File?.pathJson(): String = this?.invariantSeparatorsPath?.jsonQuoted() ?: "null"

    private fun writeAtomically(
        file: File,
        text: String,
    ) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    const val RESULTS_DIR = "results"
    const val SUMMARY_FILE = "summary.json"
}

/** The run-level entry a verifying run appends to its fixtures; see [VerifyReport]. */
internal const val VERIFY_SUMMARY_TEST_NAME: String = "Verify summary"

/** How many regions an entry lists; past that, `moreRegions` says how many were left out. */
internal const val MAX_REGIONS_LISTED: Int = 16

/** [DiffResult.regions] as JSON: the [MAX_REGIONS_LISTED] largest, and how many more there were. */
internal fun regionsJson(regions: List<DiffRegion>): String {
    val listed =
        regions.take(MAX_REGIONS_LISTED).joinToString(", ") {
            "{\"x\": ${it.x}, \"y\": ${it.y}, \"width\": ${it.width}, \"height\": ${it.height}, \"pixels\": ${it.pixels}}"
        }
    val more = (regions.size - MAX_REGIONS_LISTED).coerceAtLeast(0)
    return "{\"listed\": [$listed], \"more\": $more}"
}

internal fun String.jsonQuoted(): String =
    buildString {
        append('"')
        for (character in this@jsonQuoted) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
