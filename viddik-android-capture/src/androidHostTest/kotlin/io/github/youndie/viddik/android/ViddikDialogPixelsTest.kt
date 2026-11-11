package io.github.youndie.viddik.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViddikDialogPixelsTest {
    /** What a screen shows for a straight ARGB [pixel] over an opaque background of one [level]. */
    private fun composite(
        pixel: Long,
        level: Int,
    ): Int {
        val alpha = ((pixel shr 24) and 0xFF).toInt()

        fun channel(shift: Int): Int {
            val source = ((pixel shr shift) and 0xFF).toInt()
            return (source * alpha + level * (255 - alpha) + 127) / 255
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun recovered(pixel: Long) = pixelFromComposites(composite(pixel, 0), composite(pixel, 255))

    @Test
    fun `an opaque pixel comes back as it is`() {
        assertEquals(0xFF336699.toInt(), recovered(0xFF336699))
    }

    @Test
    fun `a pixel the window did not draw comes back fully transparent`() {
        assertEquals(0, recovered(0x00000000))
    }

    @Test
    fun `a black scrim keeps its alpha, which is what a screenshot alone cannot say`() {
        assertEquals(0x52000000, recovered(0x52000000))
    }

    @Test
    fun `a translucent colour comes back within what the differ tolerates`() {
        val back = recovered(0x80336699)
        val expected = intArrayOf(0x80, 0x33, 0x66, 0x99)
        listOf(24, 16, 8, 0).forEachIndexed { index, shift ->
            val delta = ((back shr shift) and 0xFF) - expected[index]
            assertTrue(delta in -2..2, "channel at $shift is off by $delta: ${back.toUInt().toString(16)}")
        }
    }

    @Test
    fun `a status bar's icons go, and the scrim around them stays`() {
        val scrim = 7
        val icon = 9
        val sheet = 3
        val pixels =
            intArrayOf(
                scrim,
                scrim,
                icon,
                scrim,
                scrim, // the band
                scrim,
                sheet,
                sheet,
                sheet,
                scrim, // the first row of a rounded sheet
                sheet,
                sheet,
                sheet,
                sheet,
                sheet,
            )

        coverSystemBars(pixels, width = 5, height = 3, top = 1, bottom = 0)

        assertContentEquals(
            intArrayOf(
                scrim,
                scrim,
                scrim,
                scrim,
                scrim,
                scrim,
                sheet,
                sheet,
                sheet,
                scrim,
                sheet,
                sheet,
                sheet,
                sheet,
                sheet,
            ),
            pixels,
        )
    }

    @Test
    fun `a picture going under the status bar takes the row under the band`() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        coverSystemBars(pixels, width = 4, height = 2, top = 1, bottom = 0)

        assertContentEquals(intArrayOf(5, 6, 7, 8, 5, 6, 7, 8), pixels)
    }

    @Test
    fun `what is under the bottom bar goes`() {
        val pixels = fiveRows()
        coverSystemBars(pixels, width = 2, height = 5, top = 0, bottom = 2)
        assertContentEquals(intArrayOf(1, 1, 2, 2, 3, 3, 0, 0, 0, 0), pixels)
    }

    @Test
    fun `a pinned button reaching the bottom bar is cut, not smeared down it`() {
        val pixels = fiveRows()
        coverSystemBars(pixels, width = 2, height = 5, top = 0, bottom = 1)
        assertContentEquals(intArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 0, 0), pixels)
    }

    private fun fiveRows() = intArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5)

    @Test
    fun `a capture clear of the bars stays as it is`() {
        val pixels = intArrayOf(1, 2, 3, 4)
        coverSystemBars(pixels, width = 2, height = 2, top = 0, bottom = 0)
        assertContentEquals(intArrayOf(1, 2, 3, 4), pixels)
    }

    @Test
    fun `bars that leave nothing between them leave the capture alone`() {
        val pixels = intArrayOf(1, 2, 3)
        coverSystemBars(pixels, width = 1, height = 3, top = 2, bottom = 1)
        assertContentEquals(intArrayOf(1, 2, 3), pixels)
    }
}
