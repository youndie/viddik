package io.github.youndie.viddik.core

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

// The pixel floor exists for cross-OS residue, and that residue is faint: goldens recorded on Linux
// amd64 and verified on macOS arm64 (N=50, 01.10.2026) differ by at most 13 px at a max channel delta
// of 47. A real edit of the same size is not faint — a full stop appended to a heading is 12-13 px at a
// delta of 223 — and a floor that only counted pixels let it through on 8 of 10 fixtures, at zero
// tolerance too. So only a pixel within this delta is spent from the floor; one past it fails the
// comparison whatever the count. Twice the measured residue, and still well short of a glyph edge.
public const val DEFAULT_FLOOR_CHANNEL_DELTA: Int = 96

public class DiffResult internal constructor(
    public val mismatchedPixels: Int,
    public val totalPixels: Int,
    /**
     * Mismatched pixels with a channel past the floor's delta ([DEFAULT_FLOOR_CHANNEL_DELTA] unless
     * [ImageDiffer.diff] was told otherwise), out-of-bounds ones included. [matches] never lets the
     * pixel floor absorb one of these.
     */
    public val pronouncedPixels: Int,
    /** The largest single-channel difference among the mismatched pixels; 255 for a size change. */
    public val maxChannelDelta: Int,
    private val lazyDiffImage: Lazy<BufferedImage>,
) {
    /**
     * The comparison as a picture — every mismatched (or out-of-bounds) pixel solid red, the rest as
     * rendered — built on first read and not before.
     *
     * A comparison that matches never asks for it, and that is nearly all of them: a passing
     * verification, and since #29 every fixture a recording decides to keep. Painting a full image
     * per fixture in order to drop it was most of what the differ spent.
     */
    public val diffImage: BufferedImage get() = lazyDiffImage.value

    public val mismatchPercent: Double get() = if (totalPixels == 0) 0.0 else mismatchedPixels * 100.0 / totalPixels

    /**
     * True when the share of mismatched pixels is within [tolerancePercent], or when there are at most
     * [minMismatchedPixels] of them and none is [pronounced][pronouncedPixels] — the floor is for
     * faint residue, not for a few pixels that changed outright.
     */
    public fun matches(
        tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT,
        minMismatchedPixels: Int = DEFAULT_MIN_MISMATCHED_PIXELS,
    ): Boolean =
        (mismatchedPixels <= minMismatchedPixels && pronouncedPixels == 0) || mismatchPercent <= tolerancePercent
}

private const val RED_MASK = 0xFFFF0000.toInt()
private const val MAX_CHANNEL = 0xFF

public object ImageDiffer {
    public fun diff(
        expected: BufferedImage,
        actual: BufferedImage,
        channelTolerance: Int = DEFAULT_CHANNEL_TOLERANCE,
        floorChannelDelta: Int = DEFAULT_FLOOR_CHANNEL_DELTA,
    ): DiffResult {
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val expectedPixels = Pixels(expected)
        val actualPixels = Pixels(actual)

        // The whole answer for a comparison that matches exactly, without reading a pixel twice. It
        // is the common case by a wide margin: on one host two renders of the same code are
        // byte-identical, and across OSes 8 of this repository's 10 goldens are.
        if (expectedPixels.sameShapeAs(actualPixels) && expectedPixels.contentEquals(actualPixels)) {
            return DiffResult(
                mismatchedPixels = 0,
                totalPixels = width * height,
                pronouncedPixels = 0,
                maxChannelDelta = 0,
                lazyDiffImage = lazy { paint(expectedPixels, actualPixels, width, height, channelTolerance) },
            )
        }

        var mismatched = 0
        var pronounced = 0
        var maxDelta = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (samePixel(expectedPixels, actualPixels, x, y, channelTolerance)) continue
                mismatched++
                // Only reached for a pixel that already differs, so the matching bulk of an image
                // pays nothing for it.
                val delta =
                    if (expectedPixels.contains(x, y) && actualPixels.contains(x, y)) {
                        channelDelta(expectedPixels.at(x, y), actualPixels.at(x, y))
                    } else {
                        MAX_CHANNEL
                    }
                if (delta > floorChannelDelta) pronounced++
                if (delta > maxDelta) maxDelta = delta
            }
        }

        return DiffResult(
            mismatchedPixels = mismatched,
            totalPixels = width * height,
            pronouncedPixels = pronounced,
            maxChannelDelta = maxDelta,
            lazyDiffImage = lazy { paint(expectedPixels, actualPixels, width, height, channelTolerance) },
        )
    }

    /**
     * One bulk read of an image, so the comparison is int arithmetic from there on.
     *
     * A golden arrives from `ImageIO.read` as `TYPE_4BYTE_ABGR`, which means every `getRGB(x, y)`
     * went through its `ColorModel` — twice per pixel, in the innermost loop.
     */
    private class Pixels(
        image: BufferedImage,
    ) {
        val width: Int = image.width
        val height: Int = image.height
        private val data: IntArray = image.getRGB(0, 0, width, height, null, 0, width)

        fun contains(
            x: Int,
            y: Int,
        ): Boolean = x < width && y < height

        fun at(
            x: Int,
            y: Int,
        ): Int = data[y * width + x]

        fun sameShapeAs(other: Pixels): Boolean = width == other.width && height == other.height

        fun contentEquals(other: Pixels): Boolean = data.contentEquals(other.data)
    }

    /**
     * A pixel one image has and the other does not counts as mismatched, which is what makes a
     * fixture that changed size fail loudly instead of being quietly compared on the overlap.
     */
    private fun samePixel(
        expected: Pixels,
        actual: Pixels,
        x: Int,
        y: Int,
        channelTolerance: Int,
    ): Boolean =
        expected.contains(x, y) &&
            actual.contains(x, y) &&
            pixelsMatch(expected.at(x, y), actual.at(x, y), channelTolerance)

    private fun paint(
        expected: Pixels,
        actual: Pixels,
        width: Int,
        height: Int,
        channelTolerance: Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                row[x] =
                    if (samePixel(expected, actual, x, y, channelTolerance)) {
                        actual.at(x, y)
                    } else {
                        RED_MASK
                    }
            }
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        return image
    }

    private fun pixelsMatch(
        expected: Int,
        actual: Int,
        channelTolerance: Int,
    ): Boolean {
        if (expected == actual) return true
        if (channelTolerance <= 0) return false
        // Spelled out rather than looped over `intArrayOf(24, 16, 8, 0)`, which allocated that array
        // per pixel inside the hottest loop this library has.
        return channelWithin(expected, actual, 24, channelTolerance) &&
            channelWithin(expected, actual, 16, channelTolerance) &&
            channelWithin(expected, actual, 8, channelTolerance) &&
            channelWithin(expected, actual, 0, channelTolerance)
    }

    private fun channelDelta(
        expected: Int,
        actual: Int,
    ): Int =
        maxOf(
            channelDistance(expected, actual, 24),
            channelDistance(expected, actual, 16),
            channelDistance(expected, actual, 8),
            channelDistance(expected, actual, 0),
        )

    private fun channelDistance(
        expected: Int,
        actual: Int,
        shift: Int,
    ): Int = abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))

    private fun channelWithin(
        expected: Int,
        actual: Int,
        shift: Int,
        tolerance: Int,
    ): Boolean = channelDistance(expected, actual, shift) <= tolerance
}
