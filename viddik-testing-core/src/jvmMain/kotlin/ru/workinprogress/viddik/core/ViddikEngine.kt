package ru.workinprogress.viddik.core

import org.junit.jupiter.api.DynamicTest
import ru.workinprogress.viddik.annotations.ViddikComponent
import java.io.File
import javax.imageio.ImageIO

private const val RECORD_MODE_ENV = "VIDDIK_RECORD_MODE"
private const val FILTER_PROPERTY = "viddik.filter"
private const val SNAPSHOTS_DIR_PROPERTY = "viddik.snapshotsDir"
private const val REPORTS_DIR_PROPERTY = "viddik.reportsDir"
private const val TOLERANCE_PERCENT_PROPERTY = "viddik.tolerancePercent"
private const val CHANNEL_TOLERANCE_PROPERTY = "viddik.channelTolerance"
private const val MIN_MISMATCHED_PIXELS_PROPERTY = "viddik.minMismatchedPixels"
private const val DEFAULT_SNAPSHOTS_DIR = "src/desktopTest/snapshots"
private const val DEFAULT_REPORTS_DIR = "build/reports/screenshots"

public object ViddikEngine {
    private val recordMode: Boolean
        get() = System.getenv(RECORD_MODE_ENV)?.toBooleanStrictOrNull() == true

    public fun verify(
        component: ViddikComponent,
        snapshotsDir: File = File(System.getProperty(SNAPSHOTS_DIR_PROPERTY) ?: DEFAULT_SNAPSHOTS_DIR),
        reportsDir: File = File(System.getProperty(REPORTS_DIR_PROPERTY) ?: DEFAULT_REPORTS_DIR),
        tolerancePercent: Double =
            System.getProperty(TOLERANCE_PERCENT_PROPERTY)?.toDoubleOrNull() ?: DEFAULT_TOLERANCE_PERCENT,
        channelTolerance: Int =
            System.getProperty(CHANNEL_TOLERANCE_PROPERTY)?.toIntOrNull() ?: DEFAULT_CHANNEL_TOLERANCE,
        minMismatchedPixels: Int =
            System.getProperty(MIN_MISMATCHED_PIXELS_PROPERTY)?.toIntOrNull() ?: DEFAULT_MIN_MISMATCHED_PIXELS,
    ) {
        val fileName = fileNameFor(component)
        val goldenFile = File(snapshotsDir, fileName)
        val actual =
            captureComposable(
                width = component.width,
                height = component.height,
                fontScale = component.fontScale,
                content = component.content,
            )

        if (recordMode) {
            snapshotsDir.mkdirs()
            ImageIO.write(actual, "png", goldenFile)
            return
        }

        if (!goldenFile.exists()) {
            error(
                "No golden snapshot for ${component.group}/${component.name} at ${goldenFile.path}. " +
                    "Run with $RECORD_MODE_ENV=true to record it.",
            )
        }

        val expected = ImageIO.read(goldenFile)
        val diff = ImageDiffer.diff(expected, actual, channelTolerance)
        if (!diff.matches(tolerancePercent, minMismatchedPixels)) {
            reportsDir.mkdirs()
            val diffFile = File(reportsDir, fileName.removeSuffix(".png") + "_DIFF.png")
            ImageIO.write(diff.diffImage, "png", diffFile)
            error(
                "Screenshot mismatch for ${component.group}/${component.name}: " +
                    "${diff.mismatchedPixels}/${diff.totalPixels} px differ (${"%.2f".format(
                        diff.mismatchPercent,
                    )}%, " +
                    "tolerance $tolerancePercent% or $minMismatchedPixels px). Diff saved to ${diffFile.path}",
            )
        }
    }

    /**
     * Every `@ViddikScreenshot` fixture in the module, as one dynamic test each — or the subset named
     * by the `viddik.filter` system property.
     *
     * The filter exists because these are *dynamic* tests under a single generated class, which
     * Gradle's `--tests` (classes and methods only) can't reach into. It is a case-insensitive
     * substring match against `"$group - $name"`, with `*` and `?` as wildcards, so both `Primary`
     * and `Buttons*Dark` select something sensible.
     *
     * A filter that matches nothing fails loudly rather than reporting an empty, green run — an
     * accidentally over-narrow filter would otherwise look exactly like a passing verification.
     */
    public fun dynamicTests(components: List<ViddikComponent>): List<DynamicTest> {
        val pattern = System.getProperty(FILTER_PROPERTY)?.takeIf { it.isNotBlank() }
        val selected =
            if (pattern == null) {
                components
            } else {
                val regex = globToRegex(pattern)
                components.filter { regex.containsMatchIn(displayNameFor(it)) }
            }

        if (pattern != null && selected.isEmpty()) {
            error(
                "No @ViddikScreenshot component matches $FILTER_PROPERTY=\"$pattern\". " +
                    "This module has: ${components.joinToString { "\"${displayNameFor(it)}\"" }}",
            )
        }

        return selected.map { component ->
            DynamicTest.dynamicTest(displayNameFor(component)) { verify(component) }
        }
    }

    private fun displayNameFor(component: ViddikComponent): String = "${component.group} - ${component.name}"

    /**
     * Unanchored on purpose: `containsMatchIn` gives substring semantics, so a filter doesn't have to
     * spell out the group to reach a component.
     */
    internal fun globToRegex(pattern: String): Regex =
        buildString {
            pattern.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(character.toString()))
                }
            }
        }.toRegex(RegexOption.IGNORE_CASE)

    private fun fileNameFor(component: ViddikComponent): String {
        val safe = "${component.group}_${component.name}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "$safe.png"
    }
}
