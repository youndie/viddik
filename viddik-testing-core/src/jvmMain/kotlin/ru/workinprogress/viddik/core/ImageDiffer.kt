package ru.workinprogress.viddik.core

import java.awt.image.BufferedImage
import kotlin.math.abs

// Cross-platform noise used to run 0.08%-1.01% (host glyph rasterizer + per-OS font metrics), which is
// why this used to be 0.5. Both causes are fixed at the source now — normalized font metrics plus
// CaptureEngine's path rasterization — and goldens recorded on Windows verify against a Linux run with
// zero differing pixels, so the budget is back to "a handful of stray pixels", not "half a percent of
// the screen". For scale: adding one character to a button label moves 1.32% of the pixels.
public const val DEFAULT_TOLERANCE_PERCENT: Double = 0.05

// Lossless image codecs (e.g. WebP VP8L) are decoded by different native Skia/libwebp builds per
// platform (macOS vs Linux). Both decode results are valid, but intermediate color-transform/prediction
// math can round by ±1 per channel between builds — invisible to the eye, but enough to blow a detailed
// image (e.g. a card skin background) past a pixel-exact comparison. See Cards/CorporateCardLarge and
// Cards/CorporateCardSmall, which showed ~12-17% "mismatch" that was 100% off-by-one noise on every channel.
public const val DEFAULT_CHANNEL_TOLERANCE: Int = 2

// A percentage alone is unfair to small fixtures: the same handful of glyph-outline quantization
// pixels is 0.01% of a full screen and 0.10% of an 100x80 button, so a threshold strict enough for
// the big one fails the small one for no reason. Whichever bound is more generous wins.
public const val DEFAULT_MIN_MISMATCHED_PIXELS: Int = 16

public data class DiffResult(
    val diffImage: BufferedImage,
    val mismatchedPixels: Int,
    val totalPixels: Int,
) {
    val mismatchPercent: Double get() = if (totalPixels == 0) 0.0 else mismatchedPixels * 100.0 / totalPixels

    public fun matches(
        tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT,
        minMismatchedPixels: Int = DEFAULT_MIN_MISMATCHED_PIXELS,
    ): Boolean = mismatchedPixels <= minMismatchedPixels || mismatchPercent <= tolerancePercent
}

private const val RED_MASK = 0xFFFF0000.toInt()

public object ImageDiffer {
    public fun diff(
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
