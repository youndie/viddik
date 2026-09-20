package io.github.youndie.viddik.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val RED = 0xFFFF0000.toInt()

/**
 * What the differ counts, pinned before it was rewritten for speed (#33) and unchanged by that
 * rewrite. The cases that matter are the ones no fixture exercises on its own: a size difference,
 * the exact edge of the channel tolerance, and what the diff image is allowed to paint.
 */
class ImageDifferTest {
    @Test
    fun `identical images have nothing to report`() {
        val result = ImageDiffer.diff(solid(4, 4, RED), solid(4, 4, RED))

        assertEquals(0, result.mismatchedPixels)
        assertEquals(16, result.totalPixels)
        assertEquals(0.0, result.mismatchPercent)
    }

    @Test
    fun `a channel inside the tolerance is not a mismatch, one past it is`() {
        val golden = solid(2, 2, argb(100, 100, 100))

        assertEquals(
            0,
            ImageDiffer.diff(golden, solid(2, 2, argb(102, 100, 100)), channelTolerance = 2).mismatchedPixels,
        )
        assertEquals(
            4,
            ImageDiffer.diff(golden, solid(2, 2, argb(103, 100, 100)), channelTolerance = 2).mismatchedPixels,
        )
    }

    @Test
    fun `zero tolerance is exact equality`() {
        val golden = solid(2, 2, argb(100, 100, 100))

        assertEquals(
            4,
            ImageDiffer.diff(golden, solid(2, 2, argb(101, 100, 100)), channelTolerance = 0).mismatchedPixels,
        )
        assertEquals(
            0,
            ImageDiffer.diff(golden, solid(2, 2, argb(100, 100, 100)), channelTolerance = 0).mismatchedPixels,
        )
    }

    @Test
    fun `alpha counts like any other channel`() {
        val golden = solid(2, 2, 0xFF808080.toInt())

        assertEquals(4, ImageDiffer.diff(golden, solid(2, 2, 0xF0808080.toInt())).mismatchedPixels)
    }

    @Test
    fun `a pixel outside the smaller image counts as mismatched`() {
        // The union of the two sizes is what gets walked, so a taller render is not silently cropped
        // to the golden — that is what makes a resized fixture fail loudly instead of passing.
        val result = ImageDiffer.diff(solid(2, 2, RED), solid(2, 3, RED))

        assertEquals(6, result.totalPixels)
        assertEquals(2, result.mismatchedPixels)
        assertEquals(3, result.diffImage.height, "the diff image covers the union, not the golden")
    }

    @Test
    fun `the diff image paints the mismatched pixels red and keeps the rest`() {
        val golden = solid(2, 1, OPAQUE_BLACK)
        val actual = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        actual.setRGB(0, 0, OPAQUE_BLACK)
        actual.setRGB(1, 0, 0xFF00FF00.toInt())

        val result = ImageDiffer.diff(golden, actual)

        assertEquals(1, result.mismatchedPixels)
        assertEquals(OPAQUE_BLACK, result.diffImage.getRGB(0, 0))
        assertEquals(RED, result.diffImage.getRGB(1, 0))
    }

    @Test
    fun `a matching comparison still hands out a usable diff image`() {
        // Nothing reads it on the matching path, but it is public API and must not be a null or a
        // blank canvas if someone does.
        val result = ImageDiffer.diff(solid(2, 1, RED), solid(2, 1, RED))

        assertEquals(RED, result.diffImage.getRGB(0, 0))
        assertTrue(result.matches())
    }

    @Test
    fun `matches is decided by either bound`() {
        val golden = solid(100, 100, OPAQUE_BLACK)
        val actual = solid(100, 100, OPAQUE_BLACK)
        for (x in 0 until 20) actual.setRGB(x, 0, RED)

        val result = ImageDiffer.diff(golden, actual)

        assertEquals(20, result.mismatchedPixels)
        assertTrue(result.matches(tolerancePercent = 0.5, minMismatchedPixels = 0), "0.2% is inside 0.5%")
        assertTrue(result.matches(tolerancePercent = 0.0, minMismatchedPixels = 20), "20 px is inside the pixel bound")
        assertTrue(!result.matches(tolerancePercent = 0.1, minMismatchedPixels = 19), "neither bound covers it")
    }

    private fun solid(
        width: Int,
        height: Int,
        argb: Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, argb)
            }
        }
        return image
    }

    private fun argb(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
