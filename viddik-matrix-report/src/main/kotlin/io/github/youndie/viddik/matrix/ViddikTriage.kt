package io.github.youndie.viddik.matrix

import io.github.youndie.viddik.core.ImageDiffer
import java.awt.image.BufferedImage
import java.io.File

/**
 * Which cells of the matrix look like a rendering problem on their level, rather than the ordinary
 * difference between Android releases (#78).
 *
 * Every level differs from the reference for reasons that are not defects — glyph rasterization,
 * shadows, a taller status bar — so sorting by the share of pixels that differ from the reference puts
 * the platform first. What does point at a level is that it disagrees with the *other* levels while they
 * agree among themselves: the reasoning that found the harness's own false differences ("when two columns
 * agree against a third, suspect the third"). So the levels of one density are compared with each other,
 * fixture by fixture, and a cell is a suspect when one of these holds:
 *
 * - **odd one out**: its median distance to the other levels is at least [ODD_FACTOR] times the spread
 *   among them, and at least [ODD_MIN_PERCENT]%. It needs [MIN_OTHERS] other levels with a capture: with
 *   two, on the first real captures (24, 26 and 34), the two old levels agreed against the new one — shadows
 *   arrive in 28 — and the new one was the suspect;
 * - **odd size**: the others agree on a size and it does not;
 * - **blank**: nearly one colour where the others are not;
 * - **above its level's normal**: [ABOVE_FACTOR] times the median difference its level has from the
 *   reference across the suite, and at least [ABOVE_MIN_PERCENT]%.
 *
 * One large connected change (a missing or moved element, as opposed to many small ones over the text)
 * adds to a suspect's rank, but is not one by itself: a level's own shadows look like that too.
 *
 * Nothing fails on it. It ranks for a reader, a person or an agent, who then looks at the crop.
 */
public class TriageFinding(
    public val row: MatrixRow,
    public val device: MatrixDevice,
    public val score: Double,
    public val reasons: List<String>,
    public val mismatchPercent: Double,
    public val levelMedianPercent: Double?,
    public val distanceToOthers: Double?,
    public val othersSpread: Double?,
    public val largestRegionShare: Double?,
    /** Slugs of the levels this cell is far from. */
    public val disagreesWith: List<String>,
    /** The level its crop compares it with: the one closest to all the others. */
    public val comparedWith: String?,
)

/** Every suspect, the most suspicious first. */
public fun triage(matrix: Matrix): List<TriageFinding> {
    val medians = matrix.devices.associateWith { levelMedian(matrix, it) }
    val groups = matrix.devices.groupBy { matrix.referenceFor(it) }.values
    val findings = mutableListOf<TriageFinding>()
    for (row in matrix.rows) {
        for (group in groups) {
            val captured = group.filter { row.cells.getValue(it.slug).image != null }
            if (captured.isEmpty()) continue
            val images = captured.associateWith { read(row.cells.getValue(it.slug).image!!) }
            val distance = PairDistances(images)
            for (device in captured) {
                findingFor(row, device, captured, images, distance, medians[device])?.let(findings::add)
            }
        }
    }
    return findings.sortedWith(compareByDescending<TriageFinding> { it.score }.thenByDescending { it.mismatchPercent })
}

private fun findingFor(
    row: MatrixRow,
    device: MatrixDevice,
    captured: List<MatrixDevice>,
    images: Map<MatrixDevice, BufferedImage>,
    distance: PairDistances,
    levelMedian: Double?,
): TriageFinding? {
    val cell = row.cells.getValue(device.slug)
    val others = captured - device
    val reasons = mutableListOf<String>()
    var score = 0.0

    val toOthers = others.map { distance(device, it) }
    val distanceToOthers = toOthers.medianOrNull()
    val othersSpread =
        others
            .flatMapIndexed { i, a -> others.drop(i + 1).map { b -> distance(a, b) } }
            .medianOrNull()
    if (others.size >= MIN_OTHERS && distanceToOthers != null && othersSpread != null &&
        distanceToOthers >= ODD_MIN_PERCENT &&
        distanceToOthers >= ODD_FACTOR * maxOf(othersSpread, SPREAD_FLOOR_PERCENT)
    ) {
        reasons +=
            "differs from the other levels by ${pct(distanceToOthers)}%, which agree within ${pct(othersSpread)}%"
        score += ODD_WEIGHT
    }

    val image = images.getValue(device)
    val otherSizes = others.map { images.getValue(it).let { other -> other.width to other.height } }.toSet()
    if (others.size >= 2 && otherSizes.size == 1 && otherSizes.single() != (image.width to image.height)) {
        val (width, height) = otherSizes.single()
        reasons += "is ${image.width}x${image.height} px where the other levels are ${width}x$height"
        score += SIZE_WEIGHT
    }

    if (others.isNotEmpty() && dominantShare(image) >= BLANK_SHARE &&
        others.any { dominantShare(images.getValue(it)) < NOT_BLANK_SHARE }
    ) {
        reasons += "is nearly blank where the other levels are not"
        score += BLANK_WEIGHT
    }

    if (cell.status == CellStatus.DIFFERS && levelMedian != null &&
        cell.mismatchPercent >= ABOVE_MIN_PERCENT &&
        cell.mismatchPercent >= ABOVE_FACTOR * maxOf(levelMedian, SPREAD_FLOOR_PERCENT)
    ) {
        reasons += "differs from the reference by ${pct(cell.mismatchPercent)}%, against ${pct(levelMedian)}% " +
            "for this level across the suite"
        score += ABOVE_WEIGHT
    }

    if (reasons.isEmpty()) return null

    val mismatched = cell.regions.sumOf { it.pixels }
    val largestShare =
        cell.regions.firstOrNull()?.let {
            if (mismatched ==
                0
            ) {
                null
            } else {
                it.pixels.toDouble() / mismatched
            }
        }
    val canvas = image.width.toDouble() * image.height
    val largest = cell.regions.firstOrNull()
    if (largestShare != null && largest != null && largestShare >= BLOB_SHARE &&
        largest.width * largest.height >= BLOB_AREA * canvas
    ) {
        reasons +=
            "most of the difference is one region, ${largest.width}x${largest.height} px at ${largest.x},${largest.y}"
        score += BLOB_WEIGHT
    }

    val threshold = maxOf(ODD_MIN_PERCENT, ODD_FACTOR * maxOf(othersSpread ?: 0.0, SPREAD_FLOOR_PERCENT))
    // What the crop compares it with: the other level that is closest to the rest, i.e. what the others
    // look like, which is the question; the reference may be the odd one itself.
    val comparedWith =
        others.minByOrNull { other -> (others - other).map { distance(other, it) }.medianOrNull() ?: 0.0 }
    return TriageFinding(
        row = row,
        device = device,
        score = score + cell.mismatchPercent / 1000,
        reasons = reasons,
        mismatchPercent = cell.mismatchPercent,
        levelMedianPercent = levelMedian,
        distanceToOthers = distanceToOthers,
        othersSpread = othersSpread,
        largestRegionShare = largestShare,
        disagreesWith = others.filter { distance(device, it) >= threshold }.map { it.slug },
        comparedWith = comparedWith?.slug,
    )
}

/** The share of pixels two captures differ by, each pair compared once; a size change counts as 100%. */
private class PairDistances(
    private val images: Map<MatrixDevice, BufferedImage>,
) {
    private val known = HashMap<Pair<String, String>, Double>()

    operator fun invoke(
        a: MatrixDevice,
        b: MatrixDevice,
    ): Double {
        val key = if (a.slug < b.slug) a.slug to b.slug else b.slug to a.slug
        return known.getOrPut(key) {
            val first = images.getValue(a)
            val second = images.getValue(b)
            if (first.width != second.width || first.height != second.height) {
                FULL_PERCENT
            } else {
                ImageDiffer.diff(first, second).mismatchPercent
            }
        }
    }
}

/** How far a level usually is from its reference, over the fixtures that have a comparison. */
private fun levelMedian(
    matrix: Matrix,
    device: MatrixDevice,
): Double? =
    matrix
        .cellsOf(device)
        .filter { it.status == CellStatus.SAME || it.status == CellStatus.DIFFERS }
        .map { it.mismatchPercent }
        .medianOrNull()

/** The share of the image taken by its most common colour, sampled on a grid. */
internal fun dominantShare(image: BufferedImage): Double {
    val counts = HashMap<Int, Int>()
    var total = 0
    for (y in 0 until image.height step SAMPLE_STEP) {
        for (x in 0 until image.width step SAMPLE_STEP) {
            counts.merge(image.getRGB(x, y), 1, Int::plus)
            total++
        }
    }
    return if (total == 0) 1.0 else (counts.values.maxOrNull() ?: 0).toDouble() / total
}

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private fun pct(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

/**
 * The command that captures just this fixture on just this level again: a finding worth reporting is one
 * that comes back. Below the managed devices' floor it is the connected run, on an emulator of that level.
 */
public fun rerunCommand(
    finding: TriageFinding,
    projectPath: String,
): String {
    val prefix = if (projectPath.isEmpty() || projectPath == ":") "" else projectPath
    val filter = "-PviddikMatrix.filter=${finding.row.title}"
    return if (finding.device.sdkInt >= MIN_MANAGED_API) {
        "./gradlew $prefix:viddikApi${finding.device.sdkInt}AndroidDeviceTest \"$filter\""
    } else {
        "ANDROID_SERIAL=<an API ${finding.device.sdkInt} emulator> ./gradlew $prefix:connectedAndroidDeviceTest \"$filter\""
    }
}

/** Where a finding's crop goes in the report directory, comparing it with [TriageFinding.comparedWith]. */
internal fun triageCropPath(finding: TriageFinding): String = "triage/${finding.device.slug}/${finding.row.fileName}"

internal fun writeTriageCrops(
    findings: List<TriageFinding>,
    outputDir: File,
) {
    for (finding in findings) {
        val against = finding.comparedWith ?: continue
        val mine =
            finding.row.cells
                .getValue(finding.device.slug)
                .image ?: continue
        val theirs =
            finding.row.cells
                .getValue(against)
                .image ?: continue
        val expected = read(theirs)
        val actual = read(mine)
        val diff = ImageDiffer.diff(expected, actual)
        val crop =
            io.github.youndie.viddik.core.DiffCrop
                .render(expected, actual, diff) ?: continue
        val file = File(outputDir, triageCropPath(finding))
        file.parentFile.mkdirs()
        javax.imageio.ImageIO.write(crop, "png", file)
    }
}

/** The managed devices' lowest level; below it a level is captured by a connected run. */
private const val MIN_MANAGED_API = 29

private const val FULL_PERCENT = 100.0
private const val ODD_MIN_PERCENT = 1.0
private const val ODD_FACTOR = 3.0

/** How many other levels have to agree before one of them disagreeing means something. */
private const val MIN_OTHERS = 3
private const val ABOVE_MIN_PERCENT = 1.0
private const val ABOVE_FACTOR = 5.0

/** A spread below this is noise between two runs, not agreement worth multiplying. */
private const val SPREAD_FLOOR_PERCENT = 0.1
private const val BLANK_SHARE = 0.98
private const val NOT_BLANK_SHARE = 0.9
private const val BLOB_SHARE = 0.6
private const val BLOB_AREA = 0.005
private const val SAMPLE_STEP = 4

private const val ODD_WEIGHT = 3.0
private const val SIZE_WEIGHT = 3.0
private const val BLANK_WEIGHT = 4.0
private const val ABOVE_WEIGHT = 2.0
private const val BLOB_WEIGHT = 1.0
