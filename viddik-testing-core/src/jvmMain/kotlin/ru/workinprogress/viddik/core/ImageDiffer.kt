package ru.workinprogress.viddik.core

import java.awt.image.BufferedImage

const val DEFAULT_TOLERANCE_PERCENT = 0.5

data class DiffResult(
    val diffImage: BufferedImage,
    val mismatchedPixels: Int,
    val totalPixels: Int,
) {
    val mismatchPercent: Double get() = if (totalPixels == 0) 0.0 else mismatchedPixels * 100.0 / totalPixels

    fun matches(tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT): Boolean = mismatchPercent <= tolerancePercent
}

private const val RED_MASK = 0xFFFF0000.toInt()

object ImageDiffer {
    fun diff(
        expected: BufferedImage,
        actual: BufferedImage,
    ): DiffResult {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val diffImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var mismatched = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val inExpected = x < expected.width && y < expected.height
                val inActual = x < actual.width && y < actual.height
                val same = inExpected && inActual && expected.getRGB(x, y) == actual.getRGB(x, y)
                if (same) {
                    diffImage.setRGB(x, y, actual.getRGB(x, y))
                } else {
                    mismatched++
                    diffImage.setRGB(x, y, RED_MASK)
                }
            }
        }

        return DiffResult(diffImage, mismatched, width * height)
    }
}
