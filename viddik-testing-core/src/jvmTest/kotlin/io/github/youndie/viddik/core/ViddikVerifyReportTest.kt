package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.youndie.viddik.annotations.ViddikComponent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 40
private const val HEIGHT = 20

/** What [ViddikEngine.verify] names the golden of the fixture below, and its reports after it. */
private const val STEM = "Engine_Report"

private const val RED_ARGB = 0xFFFF0000.toInt()
private const val BLUE_ARGB = 0xFF0000FF.toInt()

/**
 * What a failed verification leaves in the reports directory (#63): the diff alone paints over
 * exactly the pixels that changed, so the render and the golden go beside it — and a fixture that
 * passes today takes yesterday's three files away.
 *
 * `record` is passed rather than inherited from `VIDDIK_RECORD_MODE`, so these read the same inside a
 * recording run.
 */
class ViddikVerifyReportTest {
    @Test
    fun `a failed verification writes the render and the golden beside the diff`(
        @TempDir dir: File,
    ) {
        val golden = File(dir, "$STEM.png")
        check(ImageIO.write(solid(BLUE_ARGB), "png", golden)) { "no PNG writer" }

        val failure = assertThrows<IllegalStateException> { verify(dir, record = false) }

        val reports = File(dir, "reports")
        val actual = ImageIO.read(File(reports, "${STEM}_ACTUAL.png"))
        assertEquals(RED_ARGB, actual.getRGB(WIDTH / 2, HEIGHT / 2), "_ACTUAL.png is the render, not the diff")
        assertArrayEquals(golden.readBytes(), File(reports, "${STEM}_EXPECTED.png").readBytes())
        assertTrue(File(reports, "${STEM}_DIFF.png").exists())
        assertTrue(failure.message!!.contains("${STEM}_ACTUAL.png"), failure.message)
    }

    @Test
    fun `a verification that passes removes what an earlier failure left`(
        @TempDir dir: File,
    ) {
        val reports = File(dir, "reports").apply { mkdirs() }
        val stale = listOf("_DIFF.png", "_ACTUAL.png", "_EXPECTED.png").map { File(reports, STEM + it) }
        stale.forEach { it.writeText("yesterday") }
        verify(dir, record = true)

        verify(dir, record = false)

        stale.forEach { assertFalse(it.exists(), "${it.name} outlived the failure it reported") }
    }

    private fun verify(
        dir: File,
        record: Boolean,
    ) = ViddikEngine.verify(
        component =
            ViddikComponent(name = "Report", group = "Engine", width = WIDTH, height = HEIGHT) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            },
        snapshotsDir = dir,
        reportsDir = File(dir, "reports"),
        record = record,
        force = record,
    )

    private fun solid(argb: Int): BufferedImage =
        BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) setRGB(x, y, argb)
            }
        }
}
