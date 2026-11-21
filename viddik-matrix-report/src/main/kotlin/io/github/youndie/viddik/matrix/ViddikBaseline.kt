package io.github.youndie.viddik.matrix

import io.github.youndie.viddik.core.DiffCrop
import io.github.youndie.viddik.core.ImageDiffer
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Each level against its own earlier run (#81): not "which level is wrong now" (that is [triage]) but
 * "which level did this change break".
 *
 * Levels never agree with each other pixel for pixel, but a level agrees with itself: on one renderer two
 * full runs were byte-identical on 811 of 811 captures of API 31 and of API 34. So a kept run of a level is
 * its baseline, every capture is compared with the same level's baseline, and what decides "re-record or
 * fix" is where a fixture changed:
 *
 * - on every level compared: an intended change of the component — make a new baseline;
 * - on some levels only: a regression on those levels;
 * - nowhere: nothing.
 *
 * A baseline is compared only with captures of the same level, density and model, and refused when the
 * system image build (`fingerprint`) or the renderer differs: either moves pixels on its own, and the
 * comparison would report the emulator, not the change.
 */
public class BaselineReport(
    /** Per column slug: the baseline it was compared with, or why not. */
    public val levels: List<BaselineLevel>,
    /** Per fixture file, per column slug. */
    public val cells: Map<String, Map<String, BaselineCell>>,
    /** Per fixture file: where it changed, for fixtures compared on at least one level. */
    public val changes: Map<String, RowChange>,
) {
    public val notes: List<String> get() = levels.mapNotNull { it.refusal }
}

public class BaselineLevel(
    public val slug: String,
    public val label: String,
    /** The baseline's directory, when there is one for this level and profile. */
    public val baseline: File?,
    /** Why the baseline was not used, or null when it was (or there is none). */
    public val refusal: String?,
) {
    public val compared: Boolean get() = baseline != null && refusal == null
}

public enum class BaselineStatus { UNCHANGED, CHANGED, NEW }

public class BaselineCell(
    public val status: BaselineStatus,
    public val mismatchPercent: Double = 0.0,
    public val pronouncedPixels: Int = 0,
)

public enum class ChangeKind(
    public val json: String,
) {
    UNCHANGED("unchanged"),

    /** Every level compared changed: the component, not a level. */
    EVERYWHERE("everywhere"),

    /** Some levels changed and others did not: what to look at first. */
    SOME("some"),

    /** Only one level was compared, so where it changed says nothing about levels. */
    CHANGED("changed"),
}

public class RowChange(
    public val kind: ChangeKind,
    public val changedOn: List<String>,
    public val compared: Int,
)

/** The baseline directory of a capture: one per level and density, as the baseline task writes it. */
public fun baselineKey(
    sdkInt: Int,
    density: Float,
): String = "api$sdkInt-${density.toString().removeSuffix(".0")}"

/** Compares every capture of [matrix] with the baseline of its level under [root], if it has one. */
public fun compareWithBaselines(
    matrix: Matrix,
    root: File,
): BaselineReport {
    val levels =
        matrix.devices.map { device ->
            val dir = File(root, baselineKey(device.sdkInt, device.density))
            val file = File(dir, DEVICE_FILE)
            if (!file.isFile) return@map BaselineLevel(device.slug, device.label, null, null)
            val baseline = readDevice(file)
            BaselineLevel(device.slug, device.label, dir, refusal(device, baseline))
        }
    val bySlug = levels.associateBy { it.slug }
    val cells = mutableMapOf<String, MutableMap<String, BaselineCell>>()
    for (row in matrix.rows) {
        for (device in matrix.devices) {
            val level = bySlug.getValue(device.slug)
            if (!level.compared) continue
            val image = row.cells.getValue(device.slug).image ?: continue
            val before = File(level.baseline, row.fileName)
            val cell =
                if (!before.isFile) {
                    BaselineCell(BaselineStatus.NEW)
                } else {
                    val diff = ImageDiffer.diff(read(before), read(image))
                    BaselineCell(
                        status = if (diff.matches()) BaselineStatus.UNCHANGED else BaselineStatus.CHANGED,
                        mismatchPercent = diff.mismatchPercent,
                        pronouncedPixels = diff.pronouncedPixels,
                    )
                }
            cells.getOrPut(row.fileName) { mutableMapOf() }[device.slug] = cell
        }
    }
    val changes =
        cells
            .mapNotNull { (file, bySlugCell) ->
                val compared = bySlugCell.filterValues { it.status != BaselineStatus.NEW }
                if (compared.isEmpty()) return@mapNotNull null
                val changed = compared.filterValues { it.status == BaselineStatus.CHANGED }.keys.sorted()
                val kind =
                    when {
                        changed.isEmpty() -> ChangeKind.UNCHANGED
                        compared.size == 1 -> ChangeKind.CHANGED
                        changed.size == compared.size -> ChangeKind.EVERYWHERE
                        else -> ChangeKind.SOME
                    }
                file to RowChange(kind, changed, compared.size)
            }.toMap()
    return BaselineReport(levels, cells, changes)
}

/** Why [baseline] cannot stand for [device]'s earlier run, or null when it can. */
private fun refusal(
    device: MatrixDevice,
    baseline: MatrixDevice,
): String? {
    fun differs(
        what: String,
        now: String?,
        then: String?,
    ) = if (now != null && then != null && now != then) "$what is $now, the baseline's was $then" else null
    val reasons =
        listOfNotNull(
            differs("the model", device.model, baseline.model),
            differs("the system image", device.fingerprint, baseline.fingerprint),
            differs("the renderer", device.renderer, baseline.renderer),
        )
    if (reasons.isEmpty()) return null
    return "${device.label} is not compared with its baseline: ${reasons.joinToString("; ")}. Either moves pixels on " +
        "its own; make a new baseline on this setup (viddikAndroidMatrixBaseline)."
}

/** One line for the console, or null when no level had a baseline. */
public fun baselineLine(report: BaselineReport): String? {
    if (report.levels.none { it.compared }) return null
    val counts = ChangeKind.entries.associateWith { kind -> report.changes.values.count { it.kind == kind } }
    return "Against the baseline: ${counts.getValue(ChangeKind.SOME)} fixture(s) changed on some levels only, " +
        "${counts.getValue(ChangeKind.EVERYWHERE)} on every level, ${counts.getValue(ChangeKind.UNCHANGED)} " +
        "unchanged" +
        (counts.getValue(ChangeKind.CHANGED).takeIf { it > 0 }?.let { ", $it on the one level compared" } ?: "") +
        "."
}

internal fun baselineCropPath(
    slug: String,
    file: String,
): String = "$slug/baseline-crop/$file"

/** The crop of every changed cell against its baseline, beside the report's other crops. */
internal fun writeBaselineCrops(
    matrix: Matrix,
    report: BaselineReport,
    outputDir: File,
) {
    val levels = report.levels.associateBy { it.slug }
    for (row in matrix.rows) {
        val cells = report.cells[row.fileName] ?: continue
        for ((slug, cell) in cells) {
            if (cell.status != BaselineStatus.CHANGED) continue
            val image = row.cells.getValue(slug).image ?: continue
            val before = read(File(levels.getValue(slug).baseline, row.fileName))
            val now = read(image)
            val crop = DiffCrop.render(before, now, ImageDiffer.diff(before, now)) ?: continue
            val file = File(outputDir, baselineCropPath(slug, row.fileName))
            file.parentFile.mkdirs()
            ImageIO.write(crop, "png", file)
        }
    }
}

internal fun baselineJson(
    matrix: Matrix,
    report: BaselineReport,
): String =
    buildString {
        append("{\n  \"levels\": [")
        append(
            report.levels.joinToString(", ") {
                "{\"level\": ${it.slug.jsonQuoted()}, \"baseline\": ${it.baseline?.name?.jsonQuoted() ?: "null"}, " +
                    "\"compared\": ${it.compared}, \"refusal\": ${it.refusal?.jsonQuoted() ?: "null"}}"
            },
        )
        append("],\n  \"fixtures\": [\n")
        append(
            matrix.rows
                .mapNotNull { row ->
                    val change = report.changes[row.fileName] ?: return@mapNotNull null
                    val cells =
                        report.cells.getValue(row.fileName).entries.joinToString(", ") { (slug, cell) ->
                            "${slug.jsonQuoted()}: {\"status\": ${cell.status.name.lowercase().jsonQuoted()}, " +
                                "\"mismatchPercent\": ${"%.4f".format(Locale.ROOT, cell.mismatchPercent)}, " +
                                "\"pronouncedPixels\": ${cell.pronouncedPixels}, \"crop\": " +
                                (
                                    if (cell.status ==
                                        BaselineStatus.CHANGED
                                    ) {
                                        baselineCropPath(slug, row.fileName).jsonQuoted()
                                    } else {
                                        "null"
                                    }
                                ) +
                                "}"
                        }
                    "    {\"file\": ${row.fileName.jsonQuoted()}, \"title\": ${row.title.jsonQuoted()}, " +
                        "\"change\": ${change.kind.json.jsonQuoted()}, " +
                        "\"changedOn\": [${change.changedOn.joinToString { it.jsonQuoted() }}], \"cells\": {$cells}}"
                }.joinToString(",\n"),
        )
        append("\n  ]\n}\n")
    }

/** The page's section: fixtures that changed on some levels only first, then those that changed everywhere. */
internal fun baselineHtml(
    matrix: Matrix,
    report: BaselineReport,
): String =
    buildString {
        if (report.levels.none { it.compared }) return@buildString
        val rows = matrix.rows.associateBy { it.fileName }
        val some = report.changes.filterValues { it.kind == ChangeKind.SOME }
        val everywhere = report.changes.filterValues { it.kind == ChangeKind.EVERYWHERE }
        append("<h2>Against the baseline</h2>\n<div class=\"meta\">")
        append(baselineLine(report).orEmpty().removePrefix("Against the baseline: ").escapeHtml())
        append(" Changed on some levels only is a regression on those levels until shown otherwise; changed ")
        append("everywhere is the component, and wants a new baseline.</div>\n")
        if (some.isNotEmpty() || everywhere.isNotEmpty()) {
            append(
                "<table class=\"suspects\">\n<thead><tr><th>Fixture</th><th>Changed on</th><th>Crops</th></tr></thead>\n<tbody>\n",
            )
            for ((file, change) in some.entries + everywhere.entries) {
                val row = rows.getValue(file)
                append("<tr><td class=\"name\">${row.title.escapeHtml()}</td><td>")
                append(
                    if (change.kind ==
                        ChangeKind.EVERYWHERE
                    ) {
                        "every level"
                    } else {
                        change.changedOn.joinToString().escapeHtml()
                    },
                )
                append("</td><td>")
                for (slug in change.changedOn) {
                    val src = baselineCropPath(slug, file).escapeHtml()
                    append("<a href=\"$src\"><img loading=\"lazy\" src=\"$src\" alt=\"$slug\"></a>")
                }
                append("</td></tr>\n")
            }
            append("</tbody>\n</table>\n")
        }
    }

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun String.jsonQuoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
