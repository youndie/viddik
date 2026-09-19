package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.youndie.viddik.annotations.ViddikComponent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 40
private const val HEIGHT = 20

/** What [ViddikEngine.verify] names the golden of the fixture below. */
private const val GOLDEN_NAME = "Engine_Record.png"

private const val BLUE_ARGB = 0xFF0000FF.toInt()

/**
 * What recording writes, and — the point of these tests — what it leaves alone.
 *
 * Recording used to write every fixture it rendered, so a record on an unchanged tree produced a diff
 * made entirely of files the verification had just accepted. Measured on this repository's own suite
 * before the fix: 4 of 28 goldens rewritten, all 4 green under `viddikVerify`.
 *
 * The cases below never depend on two renders of the same fixture being byte-identical — they are on
 * this host, and that is exactly the assumption that does not survive another one. The golden is
 * always built out of the render the engine itself just produced, then moved by a known amount, so
 * what each case turns on is which side of the tolerance that amount is.
 *
 * `record`/`force` are passed rather than set through `VIDDIK_RECORD_MODE` and `viddik.forceRecord`,
 * so these read the same whether or not the suite around them is running in record mode.
 */
class ViddikRecordTest {
    @Test
    fun `a golden that does not exist yet is written`(
        @TempDir dir: File,
    ) {
        record(dir)

        assertTrue(File(dir, GOLDEN_NAME).exists(), "recording is still how a golden comes into being")
    }

    @Test
    fun `a golden the verification would accept is left alone`(
        @TempDir dir: File,
    ) {
        record(dir)
        // Every channel off by one: the render and the golden differ in every pixel and in none that
        // `DEFAULT_CHANNEL_TOLERANCE` counts, which is the residue a cross-OS golden carries.
        val nudged = nudgeGolden(dir, byChannel = 1, pixels = Int.MAX_VALUE)

        record(dir)

        assertArrayEquals(nudged, File(dir, GOLDEN_NAME).readBytes(), "the recording rewrote a golden that passes")
    }

    @Test
    fun `a golden inside the pixel budget is left alone`(
        @TempDir dir: File,
    ) {
        record(dir)
        // Far past the channel tolerance, but on too few pixels to fail `DEFAULT_MIN_MISMATCHED_PIXELS`.
        val nudged = nudgeGolden(dir, byChannel = 100, pixels = DEFAULT_MIN_MISMATCHED_PIXELS)

        record(dir)

        assertArrayEquals(nudged, File(dir, GOLDEN_NAME).readBytes(), "recording is judged by the same rule as verify")
    }

    @Test
    fun `a golden the verification would reject is rewritten`(
        @TempDir dir: File,
    ) {
        val blue = writeBlueGolden(dir)

        record(dir)

        assertFalse(
            blue.contentEquals(File(dir, GOLDEN_NAME).readBytes()),
            "a golden that does not match the render is what recording exists to replace",
        )
    }

    @Test
    fun `force rewrites a golden that passes`(
        @TempDir dir: File,
    ) {
        record(dir)
        val nudged = nudgeGolden(dir, byChannel = 1, pixels = Int.MAX_VALUE)

        record(dir, force = true)

        assertFalse(
            nudged.contentEquals(File(dir, GOLDEN_NAME).readBytes()),
            "--force is the way to replace the whole set after a renderer change",
        )
    }

    @Test
    fun `an unreadable golden is replaced rather than failing the run`(
        @TempDir dir: File,
    ) {
        val golden = File(dir, GOLDEN_NAME)
        golden.writeText("this is not a PNG")

        record(dir)

        assertTrue(ImageIO.read(golden) != null, "recording is how a corrupted golden is meant to be repaired")
    }

    private fun record(
        dir: File,
        force: Boolean = false,
    ) = ViddikEngine.verify(
        component =
            ViddikComponent(
                name = "Record",
                group = "Engine",
                width = WIDTH,
                height = HEIGHT,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            },
        snapshotsDir = dir,
        reportsDir = File(dir, "reports"),
        record = true,
        force = force,
    )

    /**
     * Moves the recorded golden by [byChannel] levels on at most [pixels] pixels and returns the bytes
     * it now holds, so a later assertion can say whether the file was touched again.
     */
    private fun nudgeGolden(
        dir: File,
        byChannel: Int,
        pixels: Int,
    ): ByteArray {
        val golden = File(dir, GOLDEN_NAME)
        val image = ImageIO.read(golden)
        var moved = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (moved >= pixels) break
                image.setRGB(x, y, shiftChannels(image.getRGB(x, y), byChannel))
                moved++
            }
        }
        check(ImageIO.write(image, "png", golden)) { "no PNG writer" }
        return golden.readBytes()
    }

    /** Down, so a channel already at 255 moves rather than saturating. */
    private fun shiftChannels(
        argb: Int,
        byChannel: Int,
    ): Int {
        var result = argb.toLong() and 0xFF000000L
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = ((argb shr shift) and 0xFF).coerceAtLeast(byChannel) - byChannel
            result = result or (channel.toLong() shl shift)
        }
        return result.toInt()
    }

    private fun writeBlueGolden(dir: File): ByteArray {
        val blue = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                blue.setRGB(x, y, BLUE_ARGB)
            }
        }
        val golden = File(dir, GOLDEN_NAME)
        check(ImageIO.write(blue, "png", golden)) { "no PNG writer" }
        return golden.readBytes()
    }
}
