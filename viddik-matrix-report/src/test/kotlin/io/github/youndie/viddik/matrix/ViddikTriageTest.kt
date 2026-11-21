package io.github.youndie.viddik.matrix

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViddikTriageTest {
    private val root: File = Files.createTempDirectory("viddik-triage").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun device(sdk: Int): File =
        File(root, "pulled/api$sdk").apply {
            mkdirs()
            File(this, "device.json").writeText(
                "{\"sdkInt\":$sdk,\"release\":\"$sdk\",\"model\":\"x\",\"density\":2.625,\"widthPx\":1080,\"heightPx\":2400}\n",
            )
            File(this, "fixtures.txt").writeText("Screen.png\tGroups - Screen\n")
        }

    /** A white screen with a dark button and a line of "text" that every level draws a little differently. */
    private fun File.screen(
        sdk: Int,
        button: Boolean = true,
        blank: Boolean = false,
    ) {
        val image = BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 200, 120)
        if (!blank) {
            g.color = Color.DARK_GRAY
            if (button) g.fillRect(20, 60, 100, 30)
            // Platform noise: one pixel of the text row moves with the level.
            g.fillRect(20, 20, 120, 6)
            g.color = Color.LIGHT_GRAY
            g.fillRect(20 + sdk % 5, 27, 1, 1)
        }
        g.dispose()
        ImageIO.write(image, "png", File(this, "Screen.png"))
    }

    @Test
    fun `a level that disagrees with the others while they agree is the suspect, and only it`() {
        device(26).screen(26)
        device(31).screen(31, button = false)
        device(34).screen(34)
        device(36).screen(36)
        val out = File(root, "report")

        val findings = writeReport(buildMatrix(listOf(File(root, "pulled"))), out, ":app")

        assertEquals(listOf("api31"), findings.map { it.device.slug })
        val finding = findings.single()
        assertTrue(finding.reasons.first().startsWith("differs from the other levels by"), finding.reasons.toString())
        assertTrue(finding.reasons.any { "one region" in it }, "a missing button is one region: ${finding.reasons}")
        assertEquals(listOf("api26", "api34", "api36"), finding.disagreesWith)
        assertTrue(File(out, "triage/api31/Screen.png").isFile, "the crop compares it with what the others look like")

        val json = File(out, "triage.json").readText()
        assertTrue("\"suspects\": 1" in json, json)
        assertTrue(
            "./gradlew :app:viddikApi31AndroidDeviceTest \\\"-PviddikMatrix.filter=Groups - Screen\\\"" in json,
            json,
        )
        assertTrue("<h2>Suspects</h2>" in File(out, "index.html").readText())
    }

    @Test
    fun `three levels are too few to call one of them the odd one out`() {
        // Two old levels agreeing against a new one is as likely the platform's history as a defect.
        device(24).screen(24)
        device(26).screen(26)
        device(34).screen(34, button = false)

        assertEquals(emptyList(), triage(buildMatrix(listOf(File(root, "pulled")))).map { it.device.slug })
    }

    @Test
    fun `levels that only differ by the platform's noise have no suspect`() {
        listOf(26, 31, 34, 36).forEach { device(it).screen(it) }

        val findings = writeReport(buildMatrix(listOf(File(root, "pulled"))), File(root, "report"))

        assertEquals(emptyList(), findings.map { it.device.slug })
        assertTrue("\"suspects\": 0" in File(root, "report/triage.json").readText())
    }

    @Test
    fun `a blank capture is a suspect even on the reference level`() {
        device(26).screen(26)
        device(31).screen(31)
        device(36).screen(36, blank = true)

        val finding = triage(buildMatrix(listOf(File(root, "pulled")))).single()

        assertEquals("api36", finding.device.slug)
        assertTrue(finding.reasons.any { "nearly blank" in it }, finding.reasons.toString())
    }

    @Test
    fun `below the managed devices the rerun is a connected run`() {
        device(24).screen(24, button = false)
        device(26).screen(26)
        device(31).screen(31)
        device(36).screen(36)

        val finding = triage(buildMatrix(listOf(File(root, "pulled")))).single()

        assertTrue(
            rerunCommand(
                finding,
                ":app",
            ).startsWith("ANDROID_SERIAL=<an API 24 emulator> ./gradlew :app:connectedAndroidDeviceTest"),
        )
    }
}
