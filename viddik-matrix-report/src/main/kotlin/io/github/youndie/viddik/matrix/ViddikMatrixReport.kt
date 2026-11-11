package io.github.youndie.viddik.matrix

import io.github.youndie.viddik.core.ImageDiffer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Writes [matrix] into [outputDir]: `index.html`, `summary.json`, and every capture and diff copied
 * beside them so the directory is self-contained — what a CI run uploads is the whole answer.
 *
 * Nothing here fails on a divergence. Platforms differ, and the point of the page is to show where
 * and by how much, not to decide which API level is right. Only [main] fails, and only on a capture
 * that did not come back, after the page is written: see [missingCapturesMessage].
 */
public fun writeReport(
    matrix: Matrix,
    outputDir: File,
) {
    outputDir.deleteRecursively()
    outputDir.mkdirs()
    for (device in matrix.devices) File(outputDir, "${device.slug}/diff").mkdirs()
    for (row in matrix.rows) {
        for ((slug, cell) in row.cells) {
            cell.image?.copyTo(File(outputDir, "$slug/${row.fileName}"), overwrite = true)
            val against = cell.diffAgainst
            if (against != null && cell.image != null) {
                val diff = ImageDiffer.diff(read(against), read(cell.image)).diffImage
                ImageIO.write(diff, "png", File(outputDir, "$slug/diff/${row.fileName}"))
            }
        }
    }
    File(outputDir, "index.html").writeText(html(matrix))
    File(outputDir, "summary.json").writeText(json(matrix))
}

/** One line for the console: what ran, and how much of it diverged. */
public fun summaryLine(
    matrix: Matrix,
    outputDir: File,
): String {
    val diverging = matrix.rows.count { it.diverges }
    return "viddik android matrix: ${matrix.rows.size} fixture(s) on API " +
        matrix.devices.joinToString(", ") { it.sdkInt.toString() } +
        "; $diverging differ from ${referenceName(matrix)} (${spread(matrix)}); " +
        "${matrix.refusedCaptures} refused on the device, ${matrix.lostCaptures} lost on the way back. " +
        "Report: ${File(outputDir, "index.html").toURI()}"
}

private fun referenceName(matrix: Matrix): String =
    if (matrix.severalProfiles) "their profile's reference" else matrix.reference.label

/** One line per column, for the console: where each number the summary adds up came from. */
public fun columnLines(matrix: Matrix): List<String> =
    matrix.devices.map { device ->
        val own = matrix.referenceFor(device)
        val against = if (own === device) "reference" else "against ${own.label}"
        "  ${device.label}, ${device.detail}, $against: ${columnCounts(matrix, device)}"
    }

private fun columnCounts(
    matrix: Matrix,
    device: MatrixDevice,
): String {
    val cells = matrix.cellsOf(device)
    val captured = cells.count { it.status != CellStatus.MISSING }
    val unlisted = cells.count { it.status == CellStatus.MISSING && !it.listed }
    return "$captured captured, ${cells.count { it.refused }} refused, ${cells.count { it.lost }} lost" +
        if (unlisted > 0) ", $unlisted not in its run" else ""
}

/** How many fixtures differ by at least each of [SPREAD_PERCENTS], which says more than the total does. */
private fun spread(matrix: Matrix): String =
    SPREAD_PERCENTS.joinToString { percent ->
        "${matrix.rows.count { it.maxMismatchPercent >= percent }} by ${percent.toInt()}%+"
    }

private val SPREAD_PERCENTS = listOf(1.0, 5.0, 25.0)

/**
 * What a run that lost captures says, or null if it lost none. Differences never fail the task; a missing
 * capture does, because a green task with a fixture silently absent is how a level or a long file name
 * went unnoticed.
 */
public fun missingCapturesMessage(matrix: Matrix): String? {
    if (matrix.missingCaptures == 0) return null
    val parts = mutableListOf<String>()
    if (matrix.lostCaptures > 0) {
        val lost = matrix.rows.filter { row -> row.cells.values.any { it.lost } }
        val named = lost.take(MISSING_NAMED).joinToString { it.title }
        val more = if (lost.size > MISSING_NAMED) ", and ${lost.size - MISSING_NAMED} more" else ""
        parts +=
            "${matrix.lostCaptures} capture(s) lost on the way back, with no word from the device (the file did " +
            "not reach this machine, or the run ended early): $named$more."
    }
    if (matrix.refusedCaptures > 0) {
        val fixtures = matrix.rows.count { row -> row.cells.values.any { it.refused } }
        parts +=
            "${matrix.refusedCaptures} capture(s) refused on the device, from $fixtures fixture(s): a fixture " +
            "that threw, or one too large for it, and the report says why for each."
    }
    if (matrix.unlistedCaptures > 0) {
        parts +=
            "${matrix.unlistedCaptures} capture(s) not in that level's run, which was filtered or is older than " +
            "the others."
    }
    return parts.joinToString(" ")
}

private const val MISSING_NAMED = 10

/**
 * `--input <dir>` (repeatable), `--output <dir>`, `--reference-api <n>` and `--api <n,n,…>` (both
 * optional).
 *
 * The entry point the `viddikAndroidMatrix` task runs.
 */
public fun main(args: Array<String>) {
    val inputs = mutableListOf<File>()
    var output: File? = null
    var reference: Int? = null
    var only: Set<Int>? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--input" -> {
                inputs += File(args[++i])
            }

            "--output" -> {
                output = File(args[++i])
            }

            "--reference-api" -> {
                reference = args[++i].toInt()
            }

            "--api" -> {
                only = args[++i].split(',').map { it.trim().toInt() }.toSet()
            }

            else -> {
                System.err.println("Unknown argument ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }
    val out = output ?: error("--output is required")
    val matrix = buildMatrix(inputs, reference, only)
    writeReport(matrix, out)
    matrix.notes.forEach { System.err.println("warning: $it") }
    val missingLevels = only?.let { missingLevelsWarning(matrix, it) }
    println(summaryLine(matrix, out))
    columnLines(matrix).forEach(::println)
    val lost = listOfNotNull(missingLevels, missingCapturesMessage(matrix))
    lost.forEach { System.err.println(it) }
    if (lost.isNotEmpty()) exitProcess(1)
}

private fun html(matrix: Matrix): String =
    buildString {
        val diverging = matrix.rows.count { it.diverges }
        append(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>viddik android matrix</title>
            <style>
            :root { color-scheme: light; }
            body { font: 14px/1.4 system-ui, sans-serif; margin: 24px; color: #1b1b1f; background: #fff; }
            h1 { font-size: 20px; margin: 0 0 4px; }
            .meta { color: #5d5e66; margin-bottom: 16px; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid #d9d9e0; padding: 8px; vertical-align: top; text-align: left; }
            thead th { position: sticky; top: 0; background: #f6f6fa; z-index: 1; }
            th small { display: block; font-weight: normal; color: #5d5e66; }
            td.name { font-weight: 600; max-width: 220px; }
            td img { display: block; max-width: 260px; max-height: 420px; background:
              repeating-conic-gradient(#eee 0 25%, #fff 0 50%) 0 0 / 16px 16px; }
            .tag { display: inline-block; margin-top: 6px; padding: 1px 6px; border-radius: 4px; font-size: 12px; }
            .REFERENCE { background: #e3e7ff; } .SAME { background: #dcf3e2; } .DIFFERS { background: #ffe9c7; }
            .SIZE { background: #ffd4d4; } .MISSING, .NO_REFERENCE { background: #eceef2; }
            .filters { margin-bottom: 12px; } .filters label { margin-right: 16px; }
            .filters input[type=number] { width: 64px; }
            </style>
            </head>
            <body>
            """.trimIndent(),
        )
        append("\n<h1>viddik android matrix</h1>\n")
        append("<div class=\"meta\">${matrix.rows.size} fixture(s) × ${matrix.devices.size} device(s); ")
        append("$diverging differ from <b>${referenceName(matrix).escape()}</b> (")
        append(spread(matrix))
        append("), the most different first. Same = within viddikVerify's default thresholds.</div>\n")
        val notes = matrix.notes + listOfNotNull(missingCapturesMessage(matrix))
        for (note in notes) append("<div class=\"meta\"><b>Note:</b> ${note.escape()}</div>\n")
        append("<div class=\"filters\">")
        append("<label><input type=\"checkbox\" id=\"only\"> Only fixtures that differ</label>")
        append(
            "<label>Differ by at least <input type=\"number\" id=\"min\" min=\"0\" step=\"0.5\" value=\"0\">%</label>",
        )
        append("<span id=\"shown\"></span></div>\n")
        append("<table>\n<thead><tr><th>Fixture</th>")
        for (device in matrix.devices) {
            val own = matrix.referenceFor(device)
            val ref = if (own === device) " · reference" else ""
            append("<th>${device.label.escape()}$ref<small>${device.detail.escape()}</small>")
            if (own !== device && matrix.severalProfiles) append("<small>against ${own.label.escape()}</small>")
            append("<small>${columnCounts(matrix, device).escape()}</small>")
            append("<small>${capturedOn(device).escape()}</small></th>")
        }
        append("</tr></thead>\n<tbody>\n")
        for (row in matrix.rows) {
            // "same" is what the filter hides, and a row with a capture missing is not that.
            append("<tr class=\"${if (row.diverges || row.missingOn > 0) "diverges" else "same"}\" data-max=\"")
            append("%.4f".format(Locale.ROOT, row.maxMismatchPercent))
            append("\"><td class=\"name\">${row.title.escape()}</td>")
            for (device in matrix.devices) {
                val cell = row.cells.getValue(device.slug)
                append("<td>")
                if (cell.image != null) {
                    val src = "${device.slug}/${row.fileName}".escape()
                    append("<a href=\"$src\"><img loading=\"lazy\" src=\"$src\" alt=\"\"></a>")
                }
                append("<span class=\"tag ${cell.status}\">${describe(cell, matrix, row, device).escape()}</span>")
                if (cell.diffAgainst != null) {
                    append(" <a href=\"${"${device.slug}/diff/${row.fileName}".escape()}\">diff</a>")
                }
                append("</td>")
            }
            append("</tr>\n")
        }
        append("</tbody>\n</table>\n")
        append(
            """
            <script>
            var only = document.getElementById('only');
            var min = document.getElementById('min');
            var rows = document.querySelectorAll('tbody tr');
            function apply() {
              var least = parseFloat(min.value) || 0;
              var shown = 0;
              rows.forEach(function (row) {
                var keep = !(only.checked && row.classList.contains('same')) && parseFloat(row.dataset.max) >= least;
                row.style.display = keep ? '' : 'none';
                if (keep) shown++;
              });
              document.getElementById('shown').textContent = shown + ' of ' + rows.length + ' shown';
            }
            only.addEventListener('change', apply);
            min.addEventListener('input', apply);
            apply();
            </script>
            </body>
            </html>
            """.trimIndent(),
        )
        append('\n')
    }

private fun describe(
    cell: MatrixCell,
    matrix: Matrix,
    row: MatrixRow,
    device: MatrixDevice,
): String =
    when (cell.status) {
        CellStatus.REFERENCE -> {
            "reference · ${cell.width}×${cell.height}"
        }

        CellStatus.SAME -> {
            "same · ${percent(cell.mismatchPercent)}"
        }

        CellStatus.DIFFERS -> {
            "${percent(cell.mismatchPercent)} differ · max Δ ${cell.maxChannelDelta}"
        }

        CellStatus.SIZE -> {
            val ref = row.cells.getValue(matrix.referenceFor(device).slug)
            "size ${cell.width}×${cell.height}, reference ${ref.width}×${ref.height}"
        }

        CellStatus.MISSING -> {
            "no capture on ${device.label}: " + (cell.reason ?: "the device reported no failure, so the file was lost")
        }

        CellStatus.NO_REFERENCE -> {
            "nothing on ${matrix.referenceFor(device).label} to compare with"
        }
    }

private fun capturedOn(device: MatrixDevice): String =
    "captured " +
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(device.capturedAt))

private fun percent(value: Double): String = "%.2f%%".format(Locale.ROOT, value)

private fun String.escape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun json(matrix: Matrix): String =
    buildString {
        append("{\n")
        append("  \"reference\": ${matrix.reference.sdkInt},\n")
        append("  \"devices\": [")
        append(
            matrix.devices.joinToString(", ") {
                val cells = matrix.cellsOf(it)
                "{\"slug\": ${it.slug.quoted()}, \"sdkInt\": ${it.sdkInt}, \"release\": ${it.release.quoted()}, " +
                    "\"model\": ${it.model.quoted()}, \"density\": ${it.density}, " +
                    "\"reference\": ${matrix.referenceFor(it).slug.quoted()}, \"capturedAt\": ${it.capturedAt}, " +
                    "\"captured\": ${cells.count { cell -> cell.status != CellStatus.MISSING }}, " +
                    "\"refused\": ${cells.count { cell -> cell.refused }}, " +
                    "\"lost\": ${cells.count { cell -> cell.lost }}, " +
                    "\"notInRun\": ${cells.count { cell -> cell.status == CellStatus.MISSING && !cell.listed }}}"
            },
        )
        append("],\n")
        append("  \"diverging\": ${matrix.rows.count { it.diverges }},\n")
        append("  \"missingCaptures\": ${matrix.missingCaptures},\n")
        append("  \"refusedCaptures\": ${matrix.refusedCaptures},\n")
        append("  \"lostCaptures\": ${matrix.lostCaptures},\n")
        append("  \"notInRunCaptures\": ${matrix.unlistedCaptures},\n")
        append("  \"fixtures\": [\n")
        append(
            matrix.rows.joinToString(",\n") { row ->
                val cells =
                    row.cells.entries.joinToString(", ") { (slug, cell) ->
                        "${slug.quoted()}: {\"status\": ${cell.status.name.quoted()}, \"width\": ${cell.width}, " +
                            "\"height\": ${cell.height}, \"mismatchPercent\": ${"%.4f".format(
                                Locale.ROOT,
                                cell.mismatchPercent,
                            )}, " +
                            "\"maxChannelDelta\": ${cell.maxChannelDelta}}"
                    }
                val max = "%.4f".format(Locale.ROOT, row.maxMismatchPercent)
                "    {\"file\": ${row.fileName.quoted()}, \"title\": ${row.title.quoted()}, " +
                    "\"diverges\": ${row.diverges}, \"maxMismatchPercent\": $max, \"cells\": {$cells}}"
            },
        )
        append("\n  ]\n}\n")
    }

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
