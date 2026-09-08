package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.youndie.viddik.annotations.ViddikComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 40
private const val HEIGHT = 20

/** What the engine names the reference of the fixture below — the same name its golden would have. */
private const val REFERENCE_NAME = "Engine_Parity.png"

private const val RED_ARGB = 0xFFFF0000.toInt()
private const val BLUE_ARGB = 0xFF0000FF.toInt()

/**
 * A solid red fixture against references that are either the same, entirely different, or absent —
 * so that every outcome here turns on what `designParity` decided, not on how a component renders.
 *
 * The run-level half (`dynamicTests` under `viddik.designParity`) is driven by executing the
 * `DynamicTest`s it returns, which is the only way to reach the summary test and the strict switch.
 */
class ViddikDesignParityTest {
    @Test
    fun `a missing reference is reported, not thrown, and the render is still written`(
        @TempDir dir: File,
    ) {
        val result = parityAgainst(dir)

        assertEquals(DesignParityStatus.MISSING_REFERENCE, result.status)
        assertEquals(File(dir, REFERENCE_NAME), result.reference)
        assertTrue(result.actual.exists(), "the render should be written for a person to look at")
        assertNull(result.diff)
    }

    @Test
    fun `a reference identical to the render matches with no diff written`(
        @TempDir dir: File,
    ) {
        writeReference(dir, RED_ARGB)

        val result = parityAgainst(dir)

        assertEquals(DesignParityStatus.MATCH, result.status)
        assertEquals(0, result.mismatchedPixels)
        assertNull(result.diff)
    }

    @Test
    fun `a reference of the same size that differs everywhere is a mismatch with a diff`(
        @TempDir dir: File,
    ) {
        writeReference(dir, BLUE_ARGB)

        val result = parityAgainst(dir)

        assertEquals(DesignParityStatus.MISMATCH, result.status)
        assertEquals(WIDTH * HEIGHT, result.mismatchedPixels)
        assertEquals(100.0, result.mismatchPercent)
        assertTrue(result.diff?.exists() == true, "the diff should be on disk")
    }

    @Test
    fun `a mismatch within the design tolerance is a match`(
        @TempDir dir: File,
    ) {
        writeReference(dir, BLUE_ARGB)

        val result = parityAgainst(dir, tolerancePercent = 100.0)

        assertEquals(DesignParityStatus.MATCH, result.status)
        // Still every pixel, and the diff still written: tolerance decides the verdict, not the facts.
        assertEquals(WIDTH * HEIGHT, result.mismatchedPixels)
        assertNotNull(result.diff)
    }

    @Test
    fun `a reference of another size is reported as such before the percentage means anything`(
        @TempDir dir: File,
    ) {
        writeReference(dir, RED_ARGB, width = WIDTH * 2, height = HEIGHT)

        val result = parityAgainst(dir, tolerancePercent = 100.0)

        assertEquals(DesignParityStatus.SIZE_MISMATCH, result.status)
        assertEquals(WIDTH * 2, result.referenceWidth)
        assertEquals(WIDTH, result.renderedWidth)
    }

    @Test
    fun `the run adds a summary test that fails when nothing was compared`(
        @TempDir dir: File,
    ) {
        val tests = inParityMode(dir) { ViddikEngine.dynamicTests(listOf(component())) }

        assertEquals(2, tests.size, "one test per fixture plus the summary")
        tests.first().executable.execute()
        val failure = assertThrows<IllegalStateException> { tests.last().executable.execute() }

        assertTrue("No design reference matched" in failure.message.orEmpty(), failure.message)
        assertTrue("does not exist" in failure.message.orEmpty(), failure.message)
        val summary = File(dir, "reports/design/summary.json")
        assertTrue(summary.exists(), "the summary is written even when the run ends in a failure")
        assertTrue("\"status\": \"MISSING_REFERENCE\"" in summary.readText(), summary.readText())
    }

    @Test
    fun `a mismatch is a report by default and a failure under strict`(
        @TempDir dir: File,
    ) {
        val designDir = File(dir, "design").also { it.mkdirs() }
        writeReference(designDir, BLUE_ARGB)

        val relaxed = inParityMode(dir) { ViddikEngine.dynamicTests(listOf(component())) }
        relaxed.forEach { it.executable.execute() }
        val summary = File(dir, "reports/design/summary.txt").readText()
        assertTrue("0/1 within" in summary, summary)
        assertTrue("DIFF" in summary, summary)

        val strict = inParityMode(dir, strict = true) { ViddikEngine.dynamicTests(listOf(component())) }
        val failure = assertThrows<IllegalStateException> { strict.first().executable.execute() }
        assertTrue("Design mismatch for Engine - Parity" in failure.message.orEmpty(), failure.message)
        // The summary still has to be complete: nothing after the failing fixture wrote it.
        strict.last().executable.execute()
        assertTrue("0/1 within" in File(dir, "reports/design/summary.txt").readText())
    }

    @Test
    fun `a previous run's diff does not survive into the next`(
        @TempDir dir: File,
    ) {
        val designDir = File(dir, "design").also { it.mkdirs() }
        writeReference(designDir, BLUE_ARGB)
        inParityMode(dir) { ViddikEngine.dynamicTests(listOf(component())) }.forEach { it.executable.execute() }
        val diff = File(dir, "reports/design/Engine_Parity_DIFF.png")
        assertTrue(diff.exists())

        writeReference(designDir, RED_ARGB)
        inParityMode(dir) { ViddikEngine.dynamicTests(listOf(component())) }.forEach { it.executable.execute() }

        assertFalse(diff.exists(), "a diff that no longer reproduces must not sit next to the new summary")
    }

    private fun component() =
        ViddikComponent(
            name = "Parity",
            group = "Engine",
            width = WIDTH,
            height = HEIGHT,
        ) {
            Box(Modifier.fillMaxSize().background(Color.Red))
        }

    private fun parityAgainst(
        dir: File,
        tolerancePercent: Double = DEFAULT_DESIGN_TOLERANCE_PERCENT,
    ) = ViddikEngine.designParity(
        component = component(),
        designDir = dir,
        reportsDir = File(dir, "reports"),
        tolerancePercent = tolerancePercent,
    )

    private fun writeReference(
        dir: File,
        argb: Int,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, argb)
            }
        }
        check(ImageIO.write(image, "png", File(dir, REFERENCE_NAME))) { "no PNG writer" }
    }

    /**
     * Runs [block] with the system properties `viddikDesignParity` sets, pointed into [dir]: references
     * in `design/`, and the report — which the engine puts under `design/` of the reports directory —
     * in `reports/design/`. The two `design/` directories are different ones on purpose: the engine
     * must never write into the references' directory, and a test that shared it would not notice.
     */
    private fun <T> inParityMode(
        dir: File,
        strict: Boolean = false,
        block: () -> T,
    ): T {
        val properties =
            mapOf(
                "viddik.designParity" to "true",
                "viddik.designDir" to File(dir, "design").path,
                "viddik.reportsDir" to File(dir, "reports").path,
                "viddik.designStrict" to strict.toString(),
            )
        val previous = properties.keys.associateWith { System.getProperty(it) }
        properties.forEach { (key, value) -> System.setProperty(key, value) }
        try {
            return block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
