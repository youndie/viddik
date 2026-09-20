package io.github.youndie.viddik.core

import io.github.youndie.viddik.annotations.ViddikComponent
import org.junit.jupiter.api.DynamicTest
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private const val RECORD_MODE_ENV = "VIDDIK_RECORD_MODE"
private const val FORCE_RECORD_PROPERTY = "viddik.forceRecord"
private const val FILTER_PROPERTY = "viddik.filter"
private const val SNAPSHOTS_DIR_PROPERTY = "viddik.snapshotsDir"
private const val REPORTS_DIR_PROPERTY = "viddik.reportsDir"
private const val TOLERANCE_PERCENT_PROPERTY = "viddik.tolerancePercent"
private const val CHANNEL_TOLERANCE_PROPERTY = "viddik.channelTolerance"
private const val MIN_MISMATCHED_PIXELS_PROPERTY = "viddik.minMismatchedPixels"
private const val DEFAULT_SNAPSHOTS_DIR = "src/desktopTest/snapshots"
private const val DEFAULT_REPORTS_DIR = "build/reports/screenshots"

/** The run-level entry a recording run appends to its fixtures; see [ViddikEngine.recordTests]. */
internal const val RECORD_SUMMARY_TEST_NAME: String = "Record summary"

/**
 * What a recording run did with each fixture it rendered, so the run can say it once at the end
 * rather than a line per fixture.
 *
 * Keyed by display name and never cleared, rather than a pair of counters reset per run: this is a
 * singleton — the generated `@TestFactory` reaches [ViddikEngine.verify] through its defaults and has
 * nowhere to thread a per-run object through — and viddik's own suite calls
 * [ViddikEngine.dynamicTests] from other test classes in the same JVM. Counters would be reset or
 * inflated by those; per-fixture outcomes are simply overwritten by the run that produced them, and
 * [summary] only reads the fixtures it is asked about.
 */
private object RecordTally {
    private const val NAMES_SHOWN = 10

    /** Display name to "was written", as opposed to kept. */
    private val outcomes = ConcurrentHashMap<String, Boolean>()

    fun wrote(displayName: String) {
        outcomes[displayName] = true
    }

    fun keptOne(displayName: String) {
        outcomes[displayName] = false
    }

    fun summary(displayNames: List<String>): String {
        val written = displayNames.filter { outcomes[it] == true }
        val kept = displayNames.count { outcomes[it] == false }
        if (written.isEmpty()) {
            return "viddik record: nothing to write — all $kept golden(s) already pass the " +
                "verification. --force (or -D$FORCE_RECORD_PROPERTY=true) rewrites them anyway."
        }
        val names = written.take(NAMES_SHOWN).joinToString()
        val rest = if (written.size > NAMES_SHOWN) ", +${written.size - NAMES_SHOWN} more" else ""
        return "viddik record: wrote ${written.size} golden(s), kept $kept the verification " +
            "already accepts. Written: $names$rest"
    }
}

public object ViddikEngine {
    private val recordMode: Boolean
        get() = System.getenv(RECORD_MODE_ENV)?.toBooleanStrictOrNull() == true

    private val forceRecordMode: Boolean
        get() = System.getProperty(FORCE_RECORD_PROPERTY)?.toBooleanStrictOrNull() == true

    private val designParityMode: Boolean
        get() = System.getProperty(DESIGN_PARITY_PROPERTY)?.toBooleanStrictOrNull() == true

    private val defaultSnapshotsDir: File
        get() = File(System.getProperty(SNAPSHOTS_DIR_PROPERTY) ?: DEFAULT_SNAPSHOTS_DIR)

    private val defaultReportsDir: File
        get() = File(System.getProperty(REPORTS_DIR_PROPERTY) ?: DEFAULT_REPORTS_DIR)

    /**
     * Renders the fixture and compares it against its golden — or, under `VIDDIK_RECORD_MODE`, writes
     * that golden ([record], [recordGolden]).
     *
     * [record] and [force] are parameters and not only the environment variable and the
     * `viddik.forceRecord` property they default to, so that the two modes are reachable from a test
     * without setting an environment variable for the JVM that is running it.
     */
    public fun verify(
        component: ViddikComponent,
        snapshotsDir: File = defaultSnapshotsDir,
        reportsDir: File = defaultReportsDir,
        // A fixture that states its own budget wins over the run's, which is the entire point of
        // stating it: the global number is what the suite as a whole can hold, and a fixture only names
        // its own when it provably can't hold that. An explicit argument here still beats both.
        tolerancePercent: Double =
            component.tolerancePercent
                ?: System.getProperty(TOLERANCE_PERCENT_PROPERTY)?.toDoubleOrNull()
                ?: DEFAULT_TOLERANCE_PERCENT,
        channelTolerance: Int =
            System.getProperty(CHANNEL_TOLERANCE_PROPERTY)?.toIntOrNull() ?: DEFAULT_CHANNEL_TOLERANCE,
        minMismatchedPixels: Int =
            System.getProperty(MIN_MISMATCHED_PIXELS_PROPERTY)?.toIntOrNull() ?: DEFAULT_MIN_MISMATCHED_PIXELS,
        record: Boolean = recordMode,
        force: Boolean = forceRecordMode,
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

        if (record) {
            recordGolden(
                component = component,
                actual = actual,
                goldenFile = goldenFile,
                force = force,
                tolerancePercent = tolerancePercent,
                channelTolerance = channelTolerance,
                minMismatchedPixels = minMismatchedPixels,
            )
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
            // Naming where the tolerance came from matters once a fixture can carry its own: otherwise a
            // failure reads as "the suite's threshold" when it was really this fixture's own number.
            val toleranceOrigin =
                if (component.tolerancePercent == tolerancePercent) " from @ViddikScreenshot" else ""
            error(
                "Screenshot mismatch for ${component.group}/${component.name}: " +
                    "${diff.mismatchedPixels}/${diff.totalPixels} px differ (${"%.2f".format(
                        diff.mismatchPercent,
                    )}%, " +
                    "tolerance $tolerancePercent%$toleranceOrigin or $minMismatchedPixels px). " +
                    "Diff saved to ${diffFile.path}",
            )
        }
    }

    /**
     * The recording half of [verify]: writes the golden only when the render is one the verification
     * would reject.
     *
     * Recording used to write every fixture unconditionally, and that is what makes a record on an
     * unchanged tree produce a diff — the render and the golden differ by exactly the residue the
     * comparison exists to absorb (a channel of anti-aliasing, a pixel of cross-OS layout), and a PNG
     * written from it is a new file even though it is the same picture to every check that looks at
     * it. Measured on this repository's own 28 fixtures: a record on a clean tree rewrote 4 of them,
     * all 4 green under `viddikVerify` before and after. Downstream, where the goldens are in Git LFS,
     * each such run is also a fresh blob per rewritten file.
     *
     * Comparing first, with the same differ and the same three thresholds the verification uses, makes
     * the rule exact: a golden the verification accepts is a golden the recording leaves alone, so what
     * a record leaves behind in `git status` is what actually moved.
     *
     * [force] is the way back to writing everything, for the re-record after a Compose, font or
     * renderer change — there "would the old comparison accept this" is not the question being asked.
     */
    private fun recordGolden(
        component: ViddikComponent,
        actual: BufferedImage,
        goldenFile: File,
        force: Boolean,
        tolerancePercent: Double,
        channelTolerance: Int,
        minMismatchedPixels: Int,
    ) {
        val existing =
            if (force) {
                null
            } else {
                // A golden that cannot be read is one to replace, not one to fail the recording on:
                // recording is how a half-written or corrupted file is meant to be repaired.
                goldenFile.takeIf(File::exists)?.let { runCatching { ImageIO.read(it) }.getOrNull() }
            }
        if (existing != null) {
            val diff = ImageDiffer.diff(existing, actual, channelTolerance)
            if (diff.matches(tolerancePercent, minMismatchedPixels)) {
                RecordTally.keptOne(displayNameFor(component))
                return
            }
        }
        writePng(actual, goldenFile)
        RecordTally.wrote(displayNameFor(component))
    }

    /**
     * Renders the fixture and measures it against the design it was built from — a PNG named exactly
     * like its golden would be, in [designDir] (`<snapshots>/design/` unless `viddik.designDir` says
     * otherwise). Never records: the reference is the designer's, and a run that could overwrite it
     * with the render would turn "does the code match the design" into "does the code match itself".
     *
     * Reports rather than judges. The result is returned, the render is written beside the reference
     * as `_ACTUAL.png` and a red-mask `_DIFF.png` whenever a pixel differs, so the next step — a
     * person or a tool deciding what to move — has both images and the number. Whether the number is
     * a failure is [dynamicTests]'s call, under `viddik.designStrict`.
     *
     * The fixture's own `@ViddikScreenshot(tolerancePercent)` is not consulted: it budgets rendering
     * noise between two runs of the same code, which is a different question from how far the code is
     * from its design.
     */
    public fun designParity(
        component: ViddikComponent,
        designDir: File = defaultDesignDir,
        reportsDir: File = defaultDesignReportsDir,
        tolerancePercent: Double = designTolerancePercent,
        channelTolerance: Int = designChannelTolerance,
    ): DesignParityResult {
        val fileName = fileNameFor(component)
        val reference = File(designDir, fileName)
        val actual =
            captureComposable(
                width = component.width,
                height = component.height,
                fontScale = component.fontScale,
                content = component.content,
            )
        val actualFile = File(reportsDir, fileName.removeSuffix(".png") + "_ACTUAL.png")
        writePng(actual, actualFile)

        if (!reference.exists()) {
            return DesignParityResult(
                group = component.group,
                name = component.name,
                reference = reference,
                status = DesignParityStatus.MISSING_REFERENCE,
                mismatchedPixels = 0,
                totalPixels = actual.width * actual.height,
                renderedWidth = actual.width,
                renderedHeight = actual.height,
                referenceWidth = null,
                referenceHeight = null,
                actual = actualFile,
                diff = null,
            )
        }

        val expected = ImageIO.read(reference) ?: error("${reference.path} is not an image ImageIO can read")
        val diff = ImageDiffer.diff(expected, actual, channelTolerance)
        val diffFile =
            if (diff.mismatchedPixels > 0) {
                File(reportsDir, fileName.removeSuffix(".png") + "_DIFF.png").also { writePng(diff.diffImage, it) }
            } else {
                null
            }
        val status =
            when {
                expected.width != actual.width || expected.height != actual.height -> DesignParityStatus.SIZE_MISMATCH
                diff.mismatchPercent <= tolerancePercent -> DesignParityStatus.MATCH
                else -> DesignParityStatus.MISMATCH
            }
        return DesignParityResult(
            group = component.group,
            name = component.name,
            reference = reference,
            status = status,
            mismatchedPixels = diff.mismatchedPixels,
            totalPixels = diff.totalPixels,
            renderedWidth = actual.width,
            renderedHeight = actual.height,
            referenceWidth = expected.width,
            referenceHeight = expected.height,
            actual = actualFile,
            diff = diffFile,
        )
    }

    private val defaultDesignDir: File
        get() = System.getProperty(DESIGN_DIR_PROPERTY)?.let(::File) ?: File(defaultSnapshotsDir, DESIGN_SUBDIR)

    private val defaultDesignReportsDir: File
        get() = File(defaultReportsDir, DESIGN_SUBDIR)

    private val designTolerancePercent: Double
        get() =
            System.getProperty(DESIGN_TOLERANCE_PERCENT_PROPERTY)?.toDoubleOrNull()
                ?: DEFAULT_DESIGN_TOLERANCE_PERCENT

    private val designChannelTolerance: Int
        get() =
            System.getProperty(DESIGN_CHANNEL_TOLERANCE_PROPERTY)?.toIntOrNull()
                ?: DEFAULT_DESIGN_CHANNEL_TOLERANCE

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

        if (designParityMode) return designParityTests(selected, components)
        if (recordMode) return recordTests(selected)

        return selected.map { component ->
            DynamicTest.dynamicTest(displayNameFor(component)) { verify(component) }
        }
    }

    /**
     * The `VIDDIK_RECORD_MODE` shape of the run: the same fixture-per-test list, plus one trailing
     * test that says what the run wrote.
     *
     * A recording that leaves an unchanged tree alone — see [recordGolden] — is silent by
     * construction, and a task that writes nothing and says nothing reads the same as one that never
     * ran. The summary is the run saying which of the two it was.
     */
    private fun recordTests(selected: List<ViddikComponent>): List<DynamicTest> {
        val names = selected.map(::displayNameFor)
        val perFixture =
            selected.map { component ->
                DynamicTest.dynamicTest(displayNameFor(component)) { verify(component) }
            }
        return perFixture + DynamicTest.dynamicTest(RECORD_SUMMARY_TEST_NAME) { println(RecordTally.summary(names)) }
    }

    /**
     * The `viddik.designParity=true` shape of the run: one test per fixture that measures it against
     * its design reference and records the result, then one more that writes the summary and fails
     * if nothing was compared at all. A module with no references — or a `viddik.designDir` pointing
     * at the wrong place — would otherwise be a green run that measured nothing.
     *
     * A fixture's own test fails only under `viddik.designStrict`; by default the run is a report,
     * because a screen half-way through being built to a design is the normal state of a screen.
     */
    private fun designParityTests(
        selected: List<ViddikComponent>,
        all: List<ViddikComponent>,
    ): List<DynamicTest> {
        val designDir = defaultDesignDir
        val reportsDir = defaultDesignReportsDir
        val tolerancePercent = designTolerancePercent
        val channelTolerance = designChannelTolerance
        val strict = System.getProperty(DESIGN_STRICT_PROPERTY)?.toBooleanStrictOrNull() == true
        val report = DesignParityReport(reportsDir, tolerancePercent, channelTolerance)

        // Only what a previous run of this mode left behind: a diff that no longer reproduces would
        // otherwise sit next to this run's summary, looking like part of it.
        reportsDir
            .listFiles()
            ?.filter {
                it.name.endsWith("_DIFF.png") || it.name.endsWith("_ACTUAL.png") ||
                    it.name.startsWith("summary.")
            }?.forEach { it.delete() }

        val perFixture =
            selected.map { component ->
                DynamicTest.dynamicTest("${displayNameFor(component)} [design]") {
                    val result = designParity(component, designDir, reportsDir, tolerancePercent, channelTolerance)
                    report.record(result)
                    val complaint =
                        when (result.status) {
                            DesignParityStatus.MISMATCH -> {
                                "Design mismatch for ${displayNameFor(component)}: " +
                                    "${result.mismatchedPixels}/${result.totalPixels} px differ " +
                                    "(${"%.2f".format(result.mismatchPercent)}%, tolerance $tolerancePercent% " +
                                    "at ±$channelTolerance per channel). Diff saved to ${result.diff?.path}"
                            }

                            DesignParityStatus.SIZE_MISMATCH -> {
                                "Design size mismatch for ${displayNameFor(component)}: rendered " +
                                    "${result.renderedWidth}x${result.renderedHeight}, reference " +
                                    "${result.referenceWidth}x${result.referenceHeight} (${result.reference.path})"
                            }

                            DesignParityStatus.MATCH, DesignParityStatus.MISSING_REFERENCE -> {
                                null
                            }
                        }
                    if (strict && complaint != null) error(complaint)
                }
            }
        val summary =
            DynamicTest.dynamicTest("Design parity summary") {
                report.write()
                println(report.text())
                if (report.compared.isEmpty()) {
                    error(
                        "No design reference matched any fixture in ${designDir.path}" +
                            (if (designDir.isDirectory) "" else " (the directory does not exist)") +
                            ". A reference is a PNG named like the fixture's golden, e.g. " +
                            "${all.firstOrNull()?.let(::fileNameFor) ?: "<group>_<name>.png"}; " +
                            "this module has: ${all.joinToString { "\"${displayNameFor(it)}\"" }}",
                    )
                }
            }
        return perFixture + summary
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

    internal fun fileNameFor(component: ViddikComponent): String {
        val safe = "${component.group}_${component.name}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "$safe.png"
    }
}
