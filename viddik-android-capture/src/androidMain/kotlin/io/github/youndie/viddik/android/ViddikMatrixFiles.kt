package io.github.youndie.viddik.android

import io.github.youndie.viddik.annotations.ViddikComponent
import java.net.URLDecoder

/**
 * The longest file name a capture gets, extension included.
 *
 * AGP pulls captures with adb to `<module>/build/intermediates/managed_device_android_test_additional_output/
 * androidDeviceTest/viddikApi34AndroidDeviceTest/viddik/<name>`, about 200 characters before the name on an
 * ordinary Windows checkout, and past MAX_PATH adb fails one file at a time while the test stays green.
 * Measured on a real suite: every fixture over ~70 characters was lost that way.
 */
internal const val MAX_FILE_NAME: Int = 48

/**
 * The file a fixture's capture is written to: the same name the JVM golden of that fixture has, as long
 * as that name fits [MAX_FILE_NAME]. A longer one keeps its start and gets a hash of the whole name in
 * place of the rest, so two fixtures that only differ late in a long name stay two files; the report
 * shows the fixture's real name, from [expectedLine].
 *
 * Copied from `ViddikEngine.fileNameFor` in viddik-testing-core rather than shared, because that one
 * is `internal` to a JVM-only module this one cannot depend on. One name per fixture across both
 * paths is what lets a JVM golden and its Android captures be lined up by eye or by script;
 * `ViddikMatrixFilesTest` pins the rule against names the JVM path is known to produce.
 */
internal fun matrixFileNameFor(component: ViddikComponent): String {
    val whole = "${component.group}_${component.name}"
    val safe = whole.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    if (safe.length + PNG.length <= MAX_FILE_NAME) return safe + PNG
    val hash =
        whole
            .hashCode()
            .toUInt()
            .toString(radix = 16)
            .padStart(length = 8, padChar = '0')
    return safe.take(MAX_FILE_NAME - PNG.length - hash.length - 1) + "-" + hash + PNG
}

/**
 * The fixtures a run covers: all of them, or those whose `group - name` contains one of the patterns in
 * [encodedFilter], ignoring case. Patterns are separated by `|` (`Buttons|Surfaces - Sheet`), so a run can
 * take a few groups of a large suite at once. A filter that matches nothing fails: a run of no fixtures must
 * not look like a pass.
 */
internal fun selected(
    components: List<ViddikComponent>,
    encodedFilter: String?,
): List<ViddikComponent> {
    val patterns = filterPatterns(encodedFilter)
    if (patterns.isEmpty()) return components
    val matching =
        components.filter { component ->
            val title = "${component.group} - ${component.name}"
            patterns.any { title.contains(it, ignoreCase = true) }
        }
    require(matching.isNotEmpty()) {
        "No fixture of ${components.size} matches the filter \"${patterns.joinToString(FILTER_SEPARATOR)}\""
    }
    return matching
}

/** The patterns of a filter argument, decoded: the plugin form-encodes it for a command line nobody quotes. */
internal fun filterPatterns(encodedFilter: String?): List<String> =
    if (encodedFilter.isNullOrEmpty()) {
        emptyList()
    } else {
        URLDecoder
            .decode(encodedFilter, Charsets.UTF_8.name())
            .split(FILTER_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

private const val FILTER_SEPARATOR = "|"

/** A line of [EXPECTED_FILE]: the file, a tab, and what the report calls the fixture. */
internal fun expectedLine(component: ViddikComponent): String {
    val title = "${component.group} - ${component.name}".replace(Regex("\\s"), " ")
    return matrixFileNameFor(component) + "\t" + title
}

/** A line of [FAILURES_FILE] or [UNFIT_FILE]: the file that is not there, a tab, and why. */
internal fun failureLine(
    component: ViddikComponent,
    reason: String,
): String = matrixFileNameFor(component) + "\t" + reason.replace(Regex("\\s+"), " ")

private const val PNG = ".png"

/** Where the captures go inside the instrumentation's output directory. */
internal const val MATRIX_SUBDIR: String = "viddik"

/** One per device: what the report needs to label and order its columns. */
internal const val DEVICE_FILE: String = "device.json"

/** One per device: every capture the run there should have written, one [expectedLine] per line. */
internal const val EXPECTED_FILE: String = "fixtures.txt"

/**
 * One per device: why a capture is not there, one [failureLine] per line. With one test for the whole
 * registry the test report has one failure for all of them, so the page reads the reasons from here.
 */
internal const val FAILURES_FILE: String = "failures.txt"

/**
 * One per device: the fixtures too large for its safe area, in [failureLine]s. Apart from [FAILURES_FILE]
 * because they are not failures of the fixture: a suite with full-height screens has a couple of hundred of
 * them on a small device, and as failing tests they made the run's exit code say nothing — a run that died
 * on compilation looked like every other.
 */
internal const val UNFIT_FILE: String = "does-not-fit.txt"

/**
 * Written only by a filtered run, holding its patterns: the host then adds this run's captures to what the
 * level already has instead of replacing them, so a quick look at three groups does not empty the column.
 */
internal const val FILTER_FILE: String = "filter.txt"

/**
 * Where the run of fixtures that starts at [start] ends (exclusive): the fixtures that take turns in one
 * activity and one composition.
 *
 * A fixture captured at a moment of the clock (`captureAtMillis`) is a run of its own. The stage's clock is
 * shared by every fixture on it and only moves forward, so the frame time a timed fixture saw would depend on
 * how many fixtures were captured before it — in a full run, a filtered one, or a shard — and an animation
 * that reads the frame time itself (a spinner whose angle is the frame time modulo a turn) would come out at
 * another angle each time. An activity of its own starts it from the same clock every time. Every other
 * fixture shares the stage, which is what keeps a capture at a fraction of a second.
 */
internal fun stageRunEnd(
    components: List<ViddikComponent>,
    start: Int,
): Int {
    if (components[start].captureAtMillis != null) return start + 1
    var end = start + 1
    while (end < components.size && components[end].captureAtMillis == null) end++
    return end
}
