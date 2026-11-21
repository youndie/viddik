package io.github.youndie.viddik.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

class DiffCropTest {
    @Test
    fun `nothing changed, nothing to crop`() {
        val image = solid(300, 200)

        assertNull(DiffCrop.render(image, solid(300, 200), ImageDiffer.diff(image, solid(300, 200))))
    }

    @Test
    fun `a small change is cut out with its margin and scaled up, and the panels are expected, actual, diff`() {
        val expected = solid(300, 200)
        val actual = solid(300, 200).apply { fill(100, 100, 4, 4, BLACK) }

        val crop = DiffCrop.render(expected, actual, ImageDiffer.diff(expected, actual))!!

        // 4 px plus a 16 px margin each side is 36 px, scaled by 96 / 36 = 2 to 72 px; three panels and
        // two 8 px gutters.
        assertEquals(72, crop.height)
        assertEquals(3 * 72 + 2 * 8, crop.width)
        val middle = 36
        assertEquals(WHITE, crop.getRGB(middle, middle), "the first panel is the expected image")
        assertEquals(BLACK, crop.getRGB(72 + 8 + middle, middle), "the second is the actual one")
        assertEquals(0xFFFF0000.toInt(), crop.getRGB(2 * (72 + 8) + middle, middle), "the third is the diff")
    }

    @Test
    fun `changes far apart get rows of their own, and near ones share a row`() {
        val near = listOf(DiffRegion(10, 10, 4, 4, 16), DiffRegion(40, 10, 4, 4, 16))
        val far = listOf(DiffRegion(10, 10, 4, 4, 16), DiffRegion(250, 150, 4, 4, 16))
        val many = (0 until 6).map { DiffRegion(it * 200, 0, 2, 2, 4) }

        assertEquals(1, DiffCrop.groups(near).size)
        assertEquals(2, DiffCrop.groups(far).size)
        assertEquals(MAX_CROPS, DiffCrop.groups(many).size, "past the limit a region joins the nearest row")
    }

    private fun solid(
        width: Int,
        height: Int,
    ) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { fill(0, 0, width, height, WHITE) }

    private fun BufferedImage.fill(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        argb: Int,
    ) {
        for (dy in 0 until height) for (dx in 0 until width) setRGB(x + dx, y + dy, argb)
    }
}
