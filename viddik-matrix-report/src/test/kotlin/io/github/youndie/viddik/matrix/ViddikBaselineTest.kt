package io.github.youndie.viddik.matrix

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViddikBaselineTest {
    private val root: File = Files.createTempDirectory("viddik-baseline").toFile()
    private val pulled = File(root, "pulled")
    private val baselines = File(root, "baselines")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun capture(
        dir: File,
        sdk: Int,
        renderer: String = "SwiftShader",
        shades: Map<String, Color>,
    ) {
        dir.mkdirs()
        File(dir, "device.json").writeText(
            "{\"sdkInt\":$sdk,\"release\":\"$sdk\",\"model\":\"x\",\"density\":2.625,\"widthPx\":1080," +
                "\"heightPx\":2400,\"fingerprint\":\"img/$sdk\",\"renderer\":\"$renderer\"}\n",
        )
        File(
            dir,
            "fixtures.txt",
        ).writeText(shades.keys.joinToString("") { "$it\tGroup - ${it.removeSuffix(".png")}\n" })
        shades.forEach { (file, color) ->
            val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = color
            g.fillRect(0, 0, 40, 20)
            g.dispose()
            ImageIO.write(image, "png", File(dir, file))
        }
    }

    private val before = mapOf("Button.png" to Color.BLUE, "Card.png" to Color.GRAY, "Chip.png" to Color.GREEN)

    @Test
    fun `where a fixture changed against each level's own baseline says whether it is the component or a level`() {
        val levels = listOf(26, 31, 34)
        levels.forEach { capture(File(baselines, baselineKey(it, 2.625f)), it, shades = before) }
        // Button changed on 31 only; Card on every level; Chip nowhere; Badge is new.
        levels.forEach { sdk ->
            val now = before.toMutableMap()
            if (sdk == 31) now["Button.png"] = Color.RED
            now["Card.png"] = Color.BLACK
            now["Badge.png"] = Color.YELLOW
            capture(File(pulled, "api$sdk"), sdk, shades = now)
        }

        val matrix = buildMatrix(listOf(pulled))
        val report = compareWithBaselines(matrix, baselines)

        assertEquals(ChangeKind.SOME, report.changes.getValue("Button.png").kind)
        assertEquals(listOf("api31"), report.changes.getValue("Button.png").changedOn)
        assertEquals(ChangeKind.EVERYWHERE, report.changes.getValue("Card.png").kind)
        assertEquals(ChangeKind.UNCHANGED, report.changes.getValue("Chip.png").kind)
        assertNull(report.changes["Badge.png"], "a fixture no baseline has is new, not unchanged")
        assertEquals(
            BaselineStatus.NEW,
            report.cells
                .getValue("Badge.png")
                .getValue("api26")
                .status,
        )
        assertEquals(
            "Against the baseline: 1 fixture(s) changed on some levels only, 1 on every level, 1 unchanged.",
            baselineLine(report),
        )

        val out = File(root, "report")
        writeReport(matrix, out, baselines = report)
        val json = File(out, "baseline.json").readText()
        assertTrue("\"change\": \"some\", \"changedOn\": [\"api31\"]" in json, json)
        assertTrue(File(out, "api31/baseline-crop/Button.png").isFile)
        assertTrue("<h2>Against the baseline</h2>" in File(out, "index.html").readText())
    }

    @Test
    fun `a baseline taken on another renderer is not compared, and the report says why`() {
        capture(File(baselines, baselineKey(34, 2.625f)), 34, renderer = "Host GPU", shades = before)
        capture(File(pulled, "api34"), 34, shades = before)

        val report = compareWithBaselines(buildMatrix(listOf(pulled)), baselines)

        val level = report.levels.single()
        assertNotNull(level.baseline)
        assertTrue("the renderer is SwiftShader, the baseline's was Host GPU" in level.refusal.orEmpty(), level.refusal)
        assertTrue(report.changes.isEmpty())
        assertNull(baselineLine(report), "no level compared, nothing to say")
    }

    @Test
    fun `the baseline directory is named by level and density`() {
        assertEquals("api34-2.625", baselineKey(34, 2.625f))
        assertEquals("api34-3", baselineKey(34, 3.0f))
    }
}
