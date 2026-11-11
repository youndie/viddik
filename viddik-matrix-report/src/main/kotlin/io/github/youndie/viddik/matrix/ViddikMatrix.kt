package io.github.youndie.viddik.matrix

import io.github.youndie.viddik.core.ImageDiffer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** One emulator's worth of captures: the directory `viddik-android-capture` filled on it. */
public class MatrixDevice(
    public val dir: File,
    public val sdkInt: Int,
    public val release: String,
    public val model: String,
    public val density: Float,
    /** When this capture reached this machine, which is what says that a column is an earlier run's. */
    public val capturedAt: Long = 0L,
) {
    /** What a column is called. The model is there for the day two devices share an API level. */
    public val label: String get() = "API $sdkInt"

    public val detail: String get() = "Android $release, $model, ${density}x"

    /** A directory name for this column in the report, unique however the devices are named. */
    internal var slug: String = "api$sdkInt"
}

public enum class CellStatus {
    /** The column every other one is compared against. */
    REFERENCE,

    /** Within the thresholds `viddikVerify` uses by default. */
    SAME,

    /** Same size, pixels past the thresholds. */
    DIFFERS,

    /** A different size — usually text wrapping or a row growing, the plainest divergence there is. */
    SIZE,

    /** No capture on this device: the fixture was skipped or failed there. */
    MISSING,

    /** Captured here, but the reference device has nothing to compare it with. */
    NO_REFERENCE,
}

public class MatrixCell(
    public val status: CellStatus,
    public val image: File?,
    public val width: Int = 0,
    public val height: Int = 0,
    public val mismatchPercent: Double = 0.0,
    public val maxChannelDelta: Int = 0,
    /**
     * The reference capture this one differs from, when it does. The diff image itself is not kept: on a
     * real suite (876 fixtures x 5 levels at device density) holding every one until the page is written
     * ran a 512 MB report JVM out of heap; [writeReport] recomputes it for the cells that need one.
     */
    internal val diffAgainst: File? = null,
    /** Why a [CellStatus.MISSING] cell is empty, when the device said; a file adb lost has no reason. */
    public val reason: String? = null,
    /** False for a [CellStatus.MISSING] cell whose level was not asked for this fixture in the run on the page. */
    public val listed: Boolean = true,
) {
    internal val refused: Boolean get() = status == CellStatus.MISSING && listed && reason != null
    internal val lost: Boolean get() = status == CellStatus.MISSING && listed && reason == null
}

public class MatrixRow(
    public val fileName: String,
    /** Keyed by [MatrixDevice.slug]. */
    public val cells: Map<String, MatrixCell>,
    /** The fixture's real name when a device listed it; a long one is cut in the file name. */
    title: String? = null,
) {
    public val title: String = title ?: fileName.removeSuffix(".png").replace('_', ' ')

    public val diverges: Boolean
        get() =
            cells.values.any {
                it.status == CellStatus.DIFFERS || it.status == CellStatus.SIZE || it.status == CellStatus.NO_REFERENCE
            }

    /** How far the furthest level is from the reference, a size change's non-overlap included. */
    public val maxMismatchPercent: Double
        get() = cells.values.maxOf { it.mismatchPercent }

    /** The levels this fixture has no capture from. */
    public val missingOn: Int
        get() = cells.values.count { it.status == CellStatus.MISSING }
}

public class Matrix(
    public val devices: List<MatrixDevice>,
    /** The reference asked for, or what stands in for it; the column the others of its profile are compared with. */
    public val reference: MatrixDevice,
    public val rows: List<MatrixRow>,
    /** Things about this run the page and the console should say, beyond the cells themselves. */
    public val notes: List<String> = emptyList(),
    private val references: Map<MatrixDevice, MatrixDevice> = emptyMap(),
) {
    /** Captures a device owed and that are not here, for whichever reason; the three below add up to it. */
    public val missingCaptures: Int get() = rows.sumOf { it.missingOn }

    /** The device tried and said why not: a fixture that threw, or one too large for it. */
    public val refusedCaptures: Int get() = missingCells().count { it.refused }

    /** The device listed the fixture and reported no failure, and no file is here: lost on the way back. */
    public val lostCaptures: Int get() = missingCells().count { it.lost }

    /** Fixtures that level's run did not have: it was filtered, or older than the others. */
    public val unlistedCaptures: Int get() = missingCells().count { !it.listed }

    private fun missingCells() = rows.flatMap { it.cells.values }.filter { it.status == CellStatus.MISSING }

    /** What [device]'s column is compared with: the reference of its own profile. */
    public fun referenceFor(device: MatrixDevice): MatrixDevice = references[device] ?: reference

    /** Whether the columns are compared against more than one reference. */
    public val severalProfiles: Boolean get() = devices.map { referenceFor(it) }.distinct().size > 1

    /** The cells of one column. */
    public fun cellsOf(device: MatrixDevice): List<MatrixCell> = rows.map { it.cells.getValue(device.slug) }
}

/**
 * Builds the matrix from the directories the devices wrote.
 *
 * Every `device.json` under [inputRoots] is one capture of a device — walking for the file the capture
 * side writes, rather than trusting a directory layout, is what keeps this working across AGP versions.
 * AGP pulls into two places, `outputs/…/<variant>/<device>/` and
 * `intermediates/…/<device>AndroidDeviceTest/`, and which one a device gets is not something a directory
 * name can be trusted for: measured with AGP 9.4.1, a group run pulled API 31's captures into
 * `outputs/…/viddikApi36/` and API 34's and 36's into `intermediates/`. So a capture is identified by what
 * the device itself wrote in `device.json` — API level, model and density — and where two pulls say the
 * same, the more recent is used and the other is the previous run's.
 *
 * [referenceSdk] picks the column to compare against; by default the newest. A reference level that
 * produced nothing does not cost the whole page: the newest level that did is used, and [Matrix.notes]
 * says so. [onlySdks] keeps the devices of those API levels, so a level dropped from the matrix does not
 * come back as a column from its last run's leftovers.
 *
 * Columns of another density have a reference of their own, the newest of that density. A fixture is laid
 * out in dp, so a capture at another density is another size whatever it looks like, and on a page of two
 * profiles every cell of the second said "size" and counted towards the percentages: 1973 cells, and
 * nothing to read from the header. Model is no use for this, it comes with the system image.
 */
public fun buildMatrix(
    inputRoots: List<File>,
    referenceSdk: Int? = null,
    onlySdks: Set<Int>? = null,
): Matrix {
    val devices =
        inputRoots
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == DEVICE_FILE }.toList() }
            .map { readDevice(it) }
            .filter { onlySdks == null || it.sdkInt in onlySdks }
            .groupBy { Triple(it.sdkInt, it.model, it.density) }
            .values
            .map { captures -> captures.maxBy { File(it.dir, DEVICE_FILE).lastModified() } }
            .sortedWith(compareBy({ it.sdkInt }, { it.model }, { it.dir.path }))
    require(devices.isNotEmpty()) {
        "No $DEVICE_FILE under ${inputRoots.joinToString()} — no device test wrote any captures. " +
            "Did the managed-device run happen, and did it pass the additionalTestOutputDir argument?"
    }
    devices.groupBy { it.sdkInt }.values.filter { it.size > 1 }.forEach { same ->
        same.forEachIndexed { index, device -> device.slug = "api${device.sdkInt}-${index + 1}" }
    }

    val notes = mutableListOf<String>()
    val requestedReference = referenceSdk?.let { sdk -> devices.lastOrNull { it.sdkInt == sdk } }
    if (referenceSdk != null && requestedReference == null) {
        val ran = devices.joinToString { it.sdkInt.toString() }
        notes +=
            "referenceApi = $referenceSdk, but nothing came back from it (the devices that did are API $ran), " +
            "so API ${devices.last().sdkInt} is the reference instead. $MISSING_LEVEL_HINT"
    }
    val reference = requestedReference ?: devices.last()
    val references =
        devices
            .groupBy { it.density }
            .values
            .flatMap { profile ->
                val own = if (reference in profile) reference else profile.last()
                profile.map { it to own }
            }.toMap()
    notes += profileNotes(devices, references)
    notes += staleNotes(devices)

    // What each device said it would capture, when it said so: a name in that list with no PNG is a
    // capture that failed or never made it back, and a PNG not in it is a leftover from an earlier run
    // (a fixture since renamed or removed) that must not come back as a row.
    val reasons =
        devices.associate { device ->
            device.slug to
                File(device.dir, FAILURES_FILE)
                    .takeIf { it.isFile }
                    ?.readLines()
                    .orEmpty()
                    .filter { it.contains('\t') }
                    .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        }

    val titles = mutableMapOf<String, String?>()
    val listed = mutableMapOf<String, Set<String>>()
    for (device in devices) {
        val expected = File(device.dir, EXPECTED_FILE).takeIf { it.isFile }
        if (expected != null) {
            val lines = expected.readLines().filter { it.isNotBlank() }
            lines.forEach { line ->
                titles[line.substringBefore('\t')] = line.substringAfter('\t', "").ifEmpty { null }
            }
            listed[device.slug] = lines.map { it.substringBefore('\t') }.toSet()
        } else {
            device.dir
                .listFiles { file -> file.extension == "png" }
                .orEmpty()
                .forEach { titles.putIfAbsent(it.name, null) }
        }
    }

    val rows =
        titles.map { (name, title) ->
            val referenceFiles =
                references.values.distinct().associateWith { File(it.dir, name).takeIf { file -> file.isFile } }
            val referenceImages = referenceFiles.mapValues { (_, file) -> file?.let(::read) }
            val cells =
                devices.associate { device ->
                    val file = File(device.dir, name).takeIf { it.isFile }
                    val own = references.getValue(device)
                    device.slug to
                        when {
                            file != null -> {
                                cell(device === own, file, referenceFiles[own], referenceImages[own])
                            }

                            listed[device.slug]?.contains(name) == false -> {
                                MatrixCell(CellStatus.MISSING, null, reason = NOT_LISTED_REASON, listed = false)
                            }

                            else -> {
                                MatrixCell(CellStatus.MISSING, null, reason = reasons[device.slug]?.get(name))
                            }
                        }
                }
            MatrixRow(name, cells, title)
        }
    return Matrix(devices, reference, rows.sortedWith(WORST_FIRST), notes, references)
}

/** Says which column is compared with which when there is more than one profile, and what a level has two of. */
private fun profileNotes(
    devices: List<MatrixDevice>,
    references: Map<MatrixDevice, MatrixDevice>,
): List<String> {
    val notes = mutableListOf<String>()
    val profiles = devices.groupBy { references.getValue(it) }
    if (profiles.size > 1) {
        val each =
            profiles.entries.joinToString("; ") { (own, columns) ->
                "${columns.joinToString { it.label }} (${own.density}x) against ${own.label}"
            }
        notes +=
            "These columns come from ${profiles.size} hardware profiles, and a capture at another density is " +
            "another size, so each profile has its own reference: $each. Nothing here compares one profile " +
            "with another."
    }
    devices.groupBy { it.sdkInt }.values.filter { it.size > 1 }.forEach { same ->
        notes +=
            "API ${same.first().sdkInt} has ${same.size} columns (${same.joinToString { it.detail }}). If one " +
            "of them is what an earlier run left, `clean` removes it."
    }
    return notes
}

/** Says which columns are from long before the others, which on a page of levels run by hand is easy to miss. */
private fun staleNotes(devices: List<MatrixDevice>): List<String> {
    val newest = devices.maxOf { it.capturedAt }
    val stale = devices.filter { newest - it.capturedAt > STALE_AFTER_MS }
    if (stale.isEmpty()) return emptyList()
    return listOf(
        "${stale.joinToString { it.label }} captured more than a day before the newest column: an earlier run's, " +
            "with whatever the code looked like then. Run that level again, or `clean` to drop it.",
    )
}

private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

internal const val NOT_LISTED_REASON = "that level's run did not have this fixture: it was filtered, or is older"

/**
 * The page is read from the top, and between Android releases nearly every fixture differs by something:
 * on a real suite 870 of 876 did, most of them by a fraction of a percent of text anti-aliasing. So what
 * differs most comes first. Above that, a fixture captured on some levels and missing on others, which
 * says more than any percentage; a fixture missing everywhere has nothing to look at and goes last.
 */
private val WORST_FIRST: Comparator<MatrixRow> =
    compareByDescending<MatrixRow> { row ->
        when (row.missingOn) {
            0 -> 1
            row.cells.size -> 0
            else -> 2
        }
    }.thenByDescending { it.maxMismatchPercent }.thenBy { it.title }

private fun cell(
    isReference: Boolean,
    file: File,
    referenceFile: File?,
    referenceImage: BufferedImage?,
): MatrixCell {
    val image = read(file)
    if (isReference) return MatrixCell(CellStatus.REFERENCE, file, image.width, image.height)
    if (referenceImage == null) return MatrixCell(CellStatus.NO_REFERENCE, file, image.width, image.height)
    val diff = ImageDiffer.diff(referenceImage, image)
    val status =
        when {
            image.width != referenceImage.width || image.height != referenceImage.height -> CellStatus.SIZE
            diff.matches() -> CellStatus.SAME
            else -> CellStatus.DIFFERS
        }
    return MatrixCell(
        status = status,
        image = file,
        width = image.width,
        height = image.height,
        mismatchPercent = diff.mismatchPercent,
        maxChannelDelta = diff.maxChannelDelta,
        diffAgainst = if (status == CellStatus.SAME) null else referenceFile,
    )
}

internal fun read(file: File): BufferedImage = ImageIO.read(file) ?: error("${file.path} is not an image")

internal const val DEVICE_FILE = "device.json"

/**
 * Where to look when a level produced nothing. Both causes were seen: an emulator left running by an
 * interrupted run stays attached to adb and takes the next run's tests, and a device test that Gradle
 * judged up to date ran nothing and pulled nothing (the plugin now forbids that, but a build that
 * configures the tasks itself can bring it back).
 */
internal const val MISSING_LEVEL_HINT: String =
    "Check that level's device test report, and `adb devices` for an emulator left running by an " +
        "interrupted run, which takes the next run's tests."

/** The API levels in [requested] no device reported, with the hint above, or null when none is missing. */
public fun missingLevelsWarning(
    matrix: Matrix,
    requested: Set<Int>,
): String? {
    val missing = requested - matrix.devices.map { it.sdkInt }.toSet()
    if (missing.isEmpty()) return null
    return "No captures came back from API ${missing.sorted().joinToString()}. $MISSING_LEVEL_HINT"
}

internal const val EXPECTED_FILE = "fixtures.txt"
internal const val FAILURES_FILE = "failures.txt"

internal fun readDevice(file: File): MatrixDevice {
    val text = file.readText()

    fun number(key: String): String =
        Regex(""""$key"\s*:\s*(-?[0-9.]+)""").find(text)?.groupValues?.get(1)
            ?: error("${file.path} has no \"$key\"")

    fun string(key: String): String =
        Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: error("${file.path} has no \"$key\"")
    return MatrixDevice(
        dir = file.parentFile,
        sdkInt = number("sdkInt").toInt(),
        release = string("release"),
        model = string("model"),
        density = number("density").toFloat(),
        capturedAt = file.lastModified(),
    )
}
