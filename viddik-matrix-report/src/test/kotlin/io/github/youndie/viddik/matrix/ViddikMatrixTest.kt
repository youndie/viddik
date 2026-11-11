package io.github.youndie.viddik.matrix

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEVICE_31 = "{\"sdkInt\":31,\"release\":\"12\",\"model\":\"x\",\"density\":2.625}"
private const val DEVICE_36 = "{\"sdkInt\":36,\"release\":\"16\",\"model\":\"x\",\"density\":2.625}"

class ViddikMatrixTest {
    private val root: File = Files.createTempDirectory("viddik-matrix").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    // Nested the way AGP nests it, more or less — the point is that buildMatrix doesn't care.
    private fun device(
        sdk: Int,
        model: String = "sdk_gphone64_x86_64",
    ): File =
        File(root, "pulled/debug/viddikApi$sdk/viddik").apply {
            mkdirs()
            File(this, "device.json").writeText(
                "{\"sdkInt\":$sdk,\"release\":\"$sdk\",\"model\":\"$model\",\"density\":2.625,\"widthPx\":1080,\"heightPx\":2400}\n",
            )
        }

    private fun File.png(
        name: String,
        width: Int = 40,
        height: Int = 20,
        paint: (BufferedImage) -> Unit = {},
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.dispose()
        paint(image)
        ImageIO.write(image, "png", File(this, name))
    }

    @Test
    fun `each API level is compared with the newest one`() {
        device(36).png("Buttons_Primary.png")
        device(34).png("Buttons_Primary.png")
        device(31).png("Buttons_Primary.png") { image ->
            // A shadow that is not drawn on this level, in miniature.
            for (x in 0 until 40) for (y in 0 until 10) image.setRGB(x, y, Color.BLACK.rgb)
        }
        device(28).png("Buttons_Primary.png", height = 26)

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        assertEquals(listOf(28, 31, 34, 36), matrix.devices.map { it.sdkInt })
        assertEquals(36, matrix.reference.sdkInt)
        val cells = matrix.rows.single().cells
        assertEquals(CellStatus.REFERENCE, cells.getValue("api36").status)
        assertEquals(CellStatus.SAME, cells.getValue("api34").status)
        assertEquals(CellStatus.DIFFERS, cells.getValue("api31").status)
        assertEquals(50.0, cells.getValue("api31").mismatchPercent)
        assertEquals(CellStatus.SIZE, cells.getValue("api28").status)
        assertTrue(matrix.rows.single().diverges)
    }

    @Test
    fun `a fixture missing on one device is reported, not dropped`() {
        device(36).png("A.png")
        device(34).png("A.png")
        device(34).png("B.png")

        val matrix = buildMatrix(listOf(root), referenceSdk = 36)

        val b = matrix.rows.single { it.fileName == "B.png" }
        assertEquals(CellStatus.MISSING, b.cells.getValue("api36").status)
        assertEquals(CellStatus.NO_REFERENCE, b.cells.getValue("api34").status)
        assertFalse(matrix.rows.single { it.fileName == "A.png" }.diverges)
    }

    @Test
    fun `the report directory stands on its own`() {
        device(36).png("A.png")
        device(31).png("A.png") { image -> for (x in 0 until 40) image.setRGB(x, 5, Color.BLUE.rgb) }
        val out = File(root, "report")

        val matrix = buildMatrix(listOf(root))
        writeReport(matrix, out)

        assertTrue(File(out, "api36/A.png").isFile)
        assertTrue(File(out, "api31/A.png").isFile)
        assertTrue(File(out, "api31/diff/A.png").isFile)
        val html = File(out, "index.html").readText()
        assertTrue("api31/diff/A.png" in html)
        assertTrue("API 36 · reference" in html)
        val json = File(out, "summary.json").readText()
        assertTrue("\"references\": [\"api36\"]" in json, json)
        assertTrue("\"diverging\": 1" in json)
    }

    @Test
    fun `two devices on one API level get columns of their own`() {
        device(34, model = "Pixel 6").png("A.png")
        File(root, "pulled2/viddik")
            .apply {
                mkdirs()
                File(
                    this,
                    "device.json",
                ).writeText("{\"sdkInt\":34,\"release\":\"14\",\"model\":\"Pixel Tablet\",\"density\":2.0}")
            }.png("A.png")

        val matrix = buildMatrix(listOf(root))

        assertEquals(listOf("api34-1", "api34-2"), matrix.devices.map { it.slug })
    }

    @Test
    fun `a capture that never came back is a missing cell, even if no device has it`() {
        device(36).apply { File(this, "fixtures.txt").writeText("A.png\nLost.png\n") }.png("A.png")

        val matrix = buildMatrix(listOf(root))

        assertEquals(
            CellStatus.MISSING,
            matrix.rows
                .single { it.fileName == "Lost.png" }
                .cells
                .getValue("api36")
                .status,
        )
        assertEquals(1, matrix.rows.single { it.fileName == "Lost.png" }.missingOn)
        assertFalse(matrix.rows.single { it.fileName == "Lost.png" }.diverges)
    }

    @Test
    fun `devices left over from an earlier run are kept out`() {
        device(36).png("A.png")
        device(30).png("A.png")

        val matrix = buildMatrix(listOf(root), onlySdks = setOf(36))

        assertEquals(listOf(36), matrix.devices.map { it.sdkInt })
    }

    @Test
    fun `a capture is the level its device reports, whatever the directory is called`() {
        device(34).png("A.png")
        // What AGP 9.4.1 did in a group run: API 31's captures, pulled into the directory of API 36.
        File(root, "outputs/androidmain/viddikApi36/viddik")
            .apply {
                mkdirs()
                File(this, "device.json").writeText(DEVICE_31)
            }.png("A.png")
        File(root, "intermediates/viddikApi36AndroidDeviceTest/viddik")
            .apply {
                mkdirs()
                File(this, "device.json").writeText(DEVICE_36)
            }.png("A.png")

        val matrix = buildMatrix(listOf(root), onlySdks = setOf(31, 34, 36))

        assertEquals(listOf(31, 34, 36), matrix.devices.map { it.sdkInt })
        assertEquals(null, missingLevelsWarning(matrix, setOf(31, 34, 36)))
        assertTrue("API 30" in missingLevelsWarning(matrix, setOf(30, 34)).orEmpty())
    }

    @Test
    fun `no captures at all is an error that says why`() {
        val error = assertFailsWith<IllegalArgumentException> { buildMatrix(listOf(root)) }
        assertTrue("additionalTestOutputDir" in error.message.orEmpty())
    }

    @Test
    fun `a reference level that produced nothing falls back to the newest one, and says so`() {
        device(34).png("A.png")
        device(31).png("A.png")

        val matrix = buildMatrix(listOf(root), referenceSdk = 36)

        assertEquals(34, matrix.reference.sdkInt)
        assertTrue("referenceApi = 36" in matrix.notes.single())
    }

    @Test
    fun `of two pulls of one managed device, the more recent one is used`() {
        // What AGP leaves after a group run and then a one-device run: the same emulator pulled into
        // intermediates/ and into outputs/, a run apart.
        val older = File(root, "intermediates/androidDeviceTest/viddikApi34AndroidDeviceTest/viddik").apply { mkdirs() }
        File(older, "device.json").writeText("{\"sdkInt\":34,\"release\":\"14\",\"model\":\"x\",\"density\":2.625}")
        older.png("A.png")
        File(older, "device.json").setLastModified(1_000_000L)
        val newer = File(root, "outputs/androidmain/viddikApi34/viddik").apply { mkdirs() }
        File(newer, "device.json").writeText("{\"sdkInt\":34,\"release\":\"14\",\"model\":\"x\",\"density\":2.625}")
        newer.png("A.png")

        val matrix = buildMatrix(listOf(File(root, "nothing-here"), File(root, "intermediates"), File(root, "outputs")))

        assertEquals(listOf(newer), matrix.devices.map { it.dir })
    }

    @Test
    fun `a leftover capture of a fixture that is gone is not a row`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("A.png\n")
        dir.png("A.png")
        dir.png("Renamed_since.png")

        val matrix = buildMatrix(listOf(root))

        assertEquals(listOf("A.png"), matrix.rows.map { it.fileName })
    }

    @Test
    fun `the page starts with what differs most, and ends with what has nothing to show`() {
        val reference = device(36)
        val other = device(34)
        File(reference, "fixtures.txt").writeText("Same.png\nSome.png\nMost.png\nPartial.png\nGone.png\n")
        listOf("Same.png", "Some.png", "Most.png", "Partial.png").forEach { reference.png(it) }
        other.png("Same.png")
        other.png("Some.png") { image -> for (x in 0 until 40) image.setRGB(x, 0, Color.BLACK.rgb) }
        other.png(
            "Most.png",
        ) { image -> for (x in 0 until 40) for (y in 0 until 10) image.setRGB(x, y, Color.BLACK.rgb) }

        val matrix = buildMatrix(listOf(root))

        assertEquals(
            listOf("Partial.png", "Most.png", "Some.png", "Same.png", "Gone.png"),
            matrix.rows.map { it.fileName },
        )
        assertEquals(50.0, matrix.rows.single { it.fileName == "Most.png" }.maxMismatchPercent)
    }

    @Test
    fun `an empty cell says why, when the device said`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("Big.png\tA - Big\nLost.png\tA - Lost\n")
        File(dir, "failures.txt").writeText("Big.png\t1200x4500 px does not fit\n")

        val rows = buildMatrix(listOf(root)).rows

        assertEquals(
            "1200x4500 px does not fit",
            rows
                .single { it.fileName == "Big.png" }
                .cells
                .getValue("api34")
                .reason,
        )
        assertEquals(
            null,
            rows
                .single { it.fileName == "Lost.png" }
                .cells
                .getValue("api34")
                .reason,
        )
    }

    @Test
    fun `a row is called what the device said the fixture is called`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("Screens_Long-0a1b2c3d.png\tScreens - Long name cut in the file\n")
        dir.png("Screens_Long-0a1b2c3d.png")
        dir.png("Old_list.png")

        val row = buildMatrix(listOf(root)).rows.single()

        assertEquals("Screens - Long name cut in the file", row.title)
        assertEquals("Screens_Long-0a1b2c3d.png", row.fileName)
    }

    @Test
    fun `a lost capture has a message that names it, and a run that lost none has none`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("A.png\tGroup - A\nB.png\tGroup - B\n")
        dir.png("A.png")

        val lost = buildMatrix(listOf(root))
        val message = missingCapturesMessage(lost).orEmpty()
        assertEquals(1, lost.missingCaptures)
        assertTrue("1 capture(s) lost" in message && "Group - B" in message, message)

        dir.png("B.png")
        assertEquals(null, missingCapturesMessage(buildMatrix(listOf(root))))
    }

    @Test
    fun `what the device refused and what never reached this machine are counted apart`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("A.png\tGroup - A\nB.png\tGroup - B\nC.png\tGroup - C\n")
        File(dir, "failures.txt").writeText("B.png\tdoes not fit\n")
        dir.png("A.png")

        val message = missingCapturesMessage(buildMatrix(listOf(root))).orEmpty()

        assertTrue("1 capture(s) refused on the device" in message, message)
        assertTrue("1 capture(s) lost on the way back" in message, message)
        // Only the lost one is named: a refusal is not a capture that went missing.
        assertTrue("Group - C" in message && "Group - B" !in message, message)
    }

    @Test
    fun `refused and lost have counters of their own, in the summary and per column`() {
        val dir = device(34)
        File(dir, "fixtures.txt").writeText("A.png\tGroup - A\nB.png\tGroup - B\nC.png\tGroup - C\n")
        File(dir, "failures.txt").writeText("B.png\tdoes not fit\n")
        dir.png("A.png")
        val out = File(root, "report")

        val matrix = buildMatrix(listOf(File(root, "pulled")))
        writeReport(matrix, out)

        assertEquals(1, matrix.refusedCaptures)
        assertEquals(1, matrix.lostCaptures)
        val json = File(out, "summary.json").readText()
        assertTrue("\"refusedCaptures\": 1" in json && "\"lostCaptures\": 1" in json, json)
        assertTrue("\"captured\": 1, \"refused\": 1, \"lost\": 1" in json, json)
        assertTrue("1 refused on the device, 1 lost on the way back" in summaryLine(matrix, out))
        assertTrue("1 captured, 1 refused, 1 lost" in columnLines(matrix).single())
    }

    @Test
    fun `a fixture a level's run did not have is neither refused nor lost`() {
        val full = device(36)
        File(full, "fixtures.txt").writeText("A.png\tGroup - A\nB.png\tGroup - B\n")
        full.png("A.png")
        full.png("B.png")
        // A filtered run of another level, or an older one.
        val filtered = device(34)
        File(filtered, "fixtures.txt").writeText("A.png\tGroup - A\n")
        filtered.png("A.png")

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        assertEquals(1, matrix.unlistedCaptures)
        assertEquals(0, matrix.lostCaptures)
        assertEquals(0, matrix.refusedCaptures)
        assertTrue("not in that level's run" in missingCapturesMessage(matrix).orEmpty())
    }

    private fun profile(
        sdk: Int,
        density: Float,
    ): File =
        File(root, "pulled/profile$sdk-$density/viddik").apply {
            mkdirs()
            File(this, "device.json").writeText(
                "{\"sdkInt\":$sdk,\"release\":\"$sdk\",\"model\":\"x\",\"density\":$density}\n",
            )
        }

    @Test
    fun `each hardware profile has a reference of its own, and the page says which`() {
        profile(36, 3.0f).png("A.png", width = 120)
        profile(31, 3.0f).png("A.png", width = 120)
        profile(27, 2.625f).png("A.png", width = 105)
        profile(24, 2.625f).png("A.png", width = 105) { image ->
            for (x in 0 until 105) for (y in 0 until 10) image.setRGB(x, y, Color.BLACK.rgb)
        }

        val matrix = buildMatrix(listOf(File(root, "pulled")), referenceSdk = 36)

        val cells = matrix.rows.single().cells
        assertEquals(CellStatus.REFERENCE, cells.getValue("api36").status)
        assertEquals(CellStatus.SAME, cells.getValue("api31").status)
        assertEquals(CellStatus.REFERENCE, cells.getValue("api27").status)
        assertEquals(CellStatus.DIFFERS, cells.getValue("api24").status)
        assertEquals(50.0, cells.getValue("api24").mismatchPercent)
        assertEquals(27, matrix.referenceFor(matrix.devices.first()).sdkInt)
        assertEquals(36, matrix.reference.sdkInt)
        val note = matrix.notes.single()
        assertTrue("API 24, API 27 (2.625x) against API 27" in note, note)
        assertTrue("API 31, API 36 (3.0x) against API 36" in note, note)
    }

    @Test
    fun `one profile is one reference, and no note about it`() {
        profile(36, 2.625f).png("A.png")
        profile(31, 2.625f).png("A.png")

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        assertFalse(matrix.severalProfiles)
        assertEquals(emptyList(), matrix.notes)
    }

    @Test
    fun `a level captured on two profiles has two columns, and is said to`() {
        profile(36, 3.0f).png("A.png")
        profile(36, 2.625f).png("A.png")

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        assertEquals(listOf("api36-1", "api36-2"), matrix.devices.map { it.slug })
        assertTrue(matrix.notes.any { "API 36 has 2 columns" in it }, matrix.notes.toString())
    }

    @Test
    fun `a column from long before the others is called out`() {
        val old = device(31)
        old.png("A.png")
        val newest = device(36).apply { png("A.png") }
        File(old, "device.json").setLastModified(File(newest, "device.json").lastModified() - 3 * 24 * 60 * 60 * 1000L)

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        assertTrue(matrix.notes.any { it.startsWith("API 31 captured more than a day") }, matrix.notes.toString())
    }

    @Test
    fun `a level whose latest run pulled nothing says its column is an earlier run's, however recent`() {
        val kept = device(31)
        kept.png("A.png")
        device(36).png("A.png")
        val capturedAt = File(kept, "device.json").lastModified()
        // Where the keep task leaves it: the level's directory, above the capture.
        File(kept.parentFile, PULLED_NOTHING_MARKER).writeText((capturedAt + 60_000L).toString())

        val matrix = buildMatrix(listOf(File(root, "pulled")))

        val api31 = matrix.devices.first { it.sdkInt == 31 }
        assertEquals(capturedAt + 60_000L, api31.pulledNothingAt)
        assertEquals(null, matrix.devices.first { it.sdkInt == 36 }.pulledNothingAt)
        assertTrue(matrix.notes.single().startsWith("API 31: its latest run"), matrix.notes.toString())
        writeReport(matrix, File(root, "report"))
        assertTrue("an earlier run's: the run at" in File(root, "report/index.html").readText())
        assertTrue("\"pulledNothingAt\": ${capturedAt + 60_000L}" in File(root, "report/summary.json").readText())
    }

    @Test
    fun `a capture newer than the note is this run's, and the note is ignored`() {
        val kept = device(31)
        kept.png("A.png")
        File(kept.parentFile, PULLED_NOTHING_MARKER)
            .writeText((File(kept, "device.json").lastModified() - 60_000L).toString())

        assertEquals(null, buildMatrix(listOf(File(root, "pulled"))).devices.single().pulledNothingAt)
    }

    @Test
    fun `the summary names a reference per profile, not one for the page`() {
        profile(36, 3.0f).png("A.png", width = 120)
        profile(27, 2.625f).png("A.png", width = 105)

        writeReport(buildMatrix(listOf(File(root, "pulled")), referenceSdk = 36), File(root, "report"))

        val json = File(root, "report/summary.json").readText()
        assertTrue("\"references\": [\"api27\", \"api36\"]" in json, json)
        assertFalse("\"reference\": 36" in json, json)
    }

    @Test
    fun `columns of one run are not called stale`() {
        device(31).png("A.png")
        device(36).png("A.png")

        assertEquals(emptyList(), buildMatrix(listOf(File(root, "pulled"))).notes)
    }
}
