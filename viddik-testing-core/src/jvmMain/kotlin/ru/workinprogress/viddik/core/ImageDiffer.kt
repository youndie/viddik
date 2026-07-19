package ru.workinprogress.viddik.core

import java.awt.image.BufferedImage
import kotlin.math.abs

const val DEFAULT_TOLERANCE_PERCENT = 0.5

// Lossless image codecs (e.g. WebP VP8L) are decoded by different native Skia/libwebp builds per
// platform (macOS vs Linux). Both decode results are valid, but intermediate color-transform/prediction
// math can round by ±1 per channel between builds — invisible to the eye, but enough to blow a detailed
// image (e.g. a card skin background) past a pixel-exact comparison. See Cards/CorporateCardLarge and
// Cards/CorporateCardSmall, which showed ~12-17% "mismatch" that was 100% off-by-one noise on every channel.
const val DEFAULT_CHANNEL_TOLERANCE = 2

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
        channelTolerance: Int = DEFAULT_CHANNEL_TOLERANCE,
    ): DiffResult {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val diffImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var mismatched = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val inExpected = x < expected.width && y < expected.height
                val inActual = x < actual.width && y < actual.height
                val same =
                    inExpected &&
                        inActual &&
                        pixelsMatch(expected.getRGB(x, y), actual.getRGB(x, y), channelTolerance)
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

    private fun pixelsMatch(
        expected: Int,
        actual: Int,
        channelTolerance: Int,
    ): Boolean {
        if (channelTolerance <= 0) return expected == actual
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            if (abs(e - a) > channelTolerance) return false
        }
        return true
    }
}
