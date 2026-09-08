package io.github.youndie.viddik.core

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

// Design references are drawn by a different rasterizer altogether — a browser rendering an artboard,
// a designer's export — so the numbers that make a golden comparison strict would make this one fail
// on anti-aliasing alone. The defaults below are a first approximation of "looks the same": a wide
// per-channel allowance absorbs the shade differences along every edge, and the percentage then
// counts the pixels that are genuinely somewhere else. Calibrate on a real screen before trusting
// either number; they are a starting point, not a measurement.
public const val DEFAULT_DESIGN_TOLERANCE_PERCENT: Double = 5.0
public const val DEFAULT_DESIGN_CHANNEL_TOLERANCE: Int = 16

internal const val DESIGN_PARITY_PROPERTY = "viddik.designParity"
internal const val DESIGN_DIR_PROPERTY = "viddik.designDir"
internal const val DESIGN_TOLERANCE_PERCENT_PROPERTY = "viddik.designTolerancePercent"
internal const val DESIGN_CHANNEL_TOLERANCE_PROPERTY = "viddik.designChannelTolerance"
internal const val DESIGN_STRICT_PROPERTY = "viddik.designStrict"

/** Subdirectory of the snapshots directory the references live in, and of the reports one the results go to. */
internal const val DESIGN_SUBDIR = "design"
internal const val DESIGN_SUMMARY_JSON = "summary.json"
internal const val DESIGN_SUMMARY_TXT = "summary.txt"

/** How one fixture came out against its design reference. */
public enum class DesignParityStatus {
    /** Within tolerance. */
    MATCH,

    /** Same size, too many pixels differ. */
    MISMATCH,

    /**
     * The reference and the render are not the same size, so the pixel count compares the overlap
     * plus everything outside it. Fix the fixture's `width`/`height` (or the artboard) before reading
     * the percentage.
     */
    SIZE_MISMATCH,

    /** No reference PNG for this fixture. Not every fixture has a design; this is information, not a failure. */
    MISSING_REFERENCE,
}

public data class DesignParityResult(
    val group: String,
    val name: String,
    /** Where the reference was looked for, whether or not it was there. */
    val reference: File,
    val status: DesignParityStatus,
    val mismatchedPixels: Int,
    val totalPixels: Int,
    val renderedWidth: Int,
    val renderedHeight: Int,
    /** Null when there is no reference. */
    val referenceWidth: Int?,
    val referenceHeight: Int?,
    /** What the fixture rendered, always written so it can be put next to the reference. */
    val actual: File,
    /** The red-mask diff, written whenever at least one pixel differs. */
    val diff: File?,
) {
    val mismatchPercent: Double get() = if (totalPixels == 0) 0.0 else mismatchedPixels * 100.0 / totalPixels
}

/**
 * The results of one design-parity run, in the order they arrived, and the two files they are
 * written to after every fixture: `summary.json` for tooling, `summary.txt` for a person.
 *
 * Rewritten after each result rather than once at the end because the run is a list of JUnit dynamic
 * tests, and nothing runs after the last of them if it fails; a summary that only exists when
 * everything passed would be missing exactly when it is needed.
 */
internal class DesignParityReport(
    private val reportsDir: File,
    private val tolerancePercent: Double,
    private val channelTolerance: Int,
) {
    private val results = mutableListOf<DesignParityResult>()

    fun record(result: DesignParityResult) {
        results += result
        write()
    }

    val compared: List<DesignParityResult>
        get() = results.filter { it.status != DesignParityStatus.MISSING_REFERENCE }

    fun write() {
        reportsDir.mkdirs()
        File(reportsDir, DESIGN_SUMMARY_JSON).writeText(json())
        File(reportsDir, DESIGN_SUMMARY_TXT).writeText(text())
    }

    fun text(): String =
        buildString {
            val matched = results.count { it.status == DesignParityStatus.MATCH }
            append("Design parity: $matched/${compared.size} within $tolerancePercent% ")
            append("(channel tolerance ±$channelTolerance)")
            val missing = results.count { it.status == DesignParityStatus.MISSING_REFERENCE }
            if (missing > 0) append(", $missing without a reference")
            append('\n')
            results.forEach { result ->
                append("  ")
                append(
                    when (result.status) {
                        DesignParityStatus.MATCH -> "ok      "
                        DesignParityStatus.MISMATCH -> "DIFF    "
                        DesignParityStatus.SIZE_MISMATCH -> "SIZE    "
                        DesignParityStatus.MISSING_REFERENCE -> "no ref  "
                    },
                )
                append("${result.group} - ${result.name}")
                when (result.status) {
                    DesignParityStatus.MISSING_REFERENCE -> {
                        append(" (expected ${result.reference.path})")
                    }

                    DesignParityStatus.SIZE_MISMATCH -> {
                        append(
                            " ${result.renderedWidth}x${result.renderedHeight} rendered vs " +
                                "${result.referenceWidth}x${result.referenceHeight} reference",
                        )
                    }

                    else -> {
                        append(" ${"%.2f".format(result.mismatchPercent)}%")
                    }
                }
                result.diff?.let { append(" -> ${it.path}") }
                append('\n')
            }
        }

    private fun json(): String =
        buildString {
            append("{\n")
            append("  \"tolerancePercent\": $tolerancePercent,\n")
            append("  \"channelTolerance\": $channelTolerance,\n")
            append("  \"results\": [\n")
            results.forEachIndexed { index, result ->
                append("    {\n")
                append("      \"group\": ${result.group.quoted()},\n")
                append("      \"name\": ${result.name.quoted()},\n")
                append("      \"status\": \"${result.status}\",\n")
                append("      \"reference\": ${result.reference.path.quoted()},\n")
                append("      \"referenceExists\": ${result.referenceWidth != null},\n")
                append("      \"actual\": ${result.actual.path.quoted()},\n")
                append("      \"diff\": ${result.diff?.path?.quoted() ?: "null"},\n")
                append("      \"mismatchedPixels\": ${result.mismatchedPixels},\n")
                append("      \"totalPixels\": ${result.totalPixels},\n")
                append("      \"mismatchPercent\": ${"%.4f".format(Locale.ROOT, result.mismatchPercent)},\n")
                append(
                    "      \"rendered\": {\"width\": ${result.renderedWidth}, \"height\": ${result.renderedHeight}},\n",
                )
                append("      \"referenceSize\": ")
                if (result.referenceWidth == null) {
                    append("null")
                } else {
                    append("{\"width\": ${result.referenceWidth}, \"height\": ${result.referenceHeight}}")
                }
                append("\n    }")
                if (index < results.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }

    private fun String.quoted(): String =
        buildString {
            append('"')
            this@quoted.forEach { character ->
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
}

internal fun writePng(
    image: BufferedImage,
    file: File,
) {
    file.parentFile?.mkdirs()
    check(ImageIO.write(image, "png", file)) { "No PNG writer for ${file.path}" }
}
