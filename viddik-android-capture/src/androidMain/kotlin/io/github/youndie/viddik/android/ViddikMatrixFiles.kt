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
 * The fixtures a run covers: all of them, or those whose `group - name` contains [filter], ignoring case.
 * A filter that matches nothing fails, listing nothing: a run of no fixtures must not look like a pass.
 */
internal fun selected(
    components: List<ViddikComponent>,
    encodedFilter: String?,
): List<ViddikComponent> {
    if (encodedFilter.isNullOrEmpty()) return components
    // Form-encoded by the plugin: the argument travels through a command line nobody quotes.
    val filter = URLDecoder.decode(encodedFilter, Charsets.UTF_8.name())
    val matching = components.filter { "${it.group} - ${it.name}".contains(filter, ignoreCase = true) }
    require(matching.isNotEmpty()) { "No fixture of ${components.size} matches the filter \"$filter\"" }
    return matching
}

/** A line of [EXPECTED_FILE]: the file, a tab, and what the report calls the fixture. */
internal fun expectedLine(component: ViddikComponent): String {
    val title = "${component.group} - ${component.name}".replace(Regex("\\s"), " ")
    return matrixFileNameFor(component) + "\t" + title
}

/** A line of [FAILURES_FILE]: the file that is not there, a tab, and why. */
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
