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
// image (e.g. a card skin background) past a pixel-exact comparison. Two such fixtures, measured, showed
// ~12-17% "mismatch" that was 100% off-by-one noise on every channel.
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

// The share (tolerancePercent) grows with the canvas: at 0.05% a 360x800 screen absorbs 144 px, enough
// for a 10dp status dot to turn from green to red (90 px at delta 199), which the same edit on a one-row
// fixture fails at 0.45% (#64). The budget is an absolute number of pronounced pixels the share may
// absorb, so the fixture's size stops deciding how large an edit gets through. 16 by default from 0.8,
// the value this repository's own goldens held on Linux, macOS and Windows while it was opt-in; before
// that it was unlimited, and a golden an edit had already gone past unnoticed fails its first verify
// under it. A fixture that states its own tolerancePercent is exempt (ViddikEngine.verify), because the
// residue it pays for, text under a RenderEffect, is the pronounced kind.
public const val DEFAULT_PRONOUNCED_PIXEL_BUDGET: Int = 16

/** No budget: the share absorbs pronounced pixels like any others, which was the comparison before 0.8. */
public const val UNLIMITED_PRONOUNCED_PIXELS: Int = Int.MAX_VALUE

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
    private val lazyRegions: Lazy<List<DiffRegion>> = lazy { emptyList() },
) {
    /**
     * Where the mismatched pixels are, as rectangles: pixels within [REGION_CELL] px of each other
     * share one, largest (by mismatched pixels) first. Built on first read, like [diffImage].
     *
     * A count says how much changed and a percentage how much of the canvas that is, and neither says
     * where. A person reads that off the diff image; a report, a crop or an agent needs it as numbers:
     * one rectangle over a button means the button changed, a few hundred scattered over every line
     * of text means the glyphs did.
     */
    public val regions: List<DiffRegion> get() = lazyRegions.value

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
     * True when there are at most [minMismatchedPixels] mismatched pixels and none is
     * [pronounced][pronouncedPixels] — the floor is for faint residue, not for a few pixels that
     * changed outright — or when the share of mismatched pixels is within [tolerancePercent] and at
     * most [pronouncedPixelBudget] of them are pronounced. The budget is a count, not a share, so the
     * size of an edit the share lets through no longer grows with the canvas.
     */
    public fun matches(
        tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT,
        minMismatchedPixels: Int = DEFAULT_MIN_MISMATCHED_PIXELS,
        pronouncedPixelBudget: Int = DEFAULT_PRONOUNCED_PIXEL_BUDGET,
    ): Boolean =
        (mismatchedPixels <= minMismatchedPixels && pronouncedPixels == 0) ||
            (mismatchPercent <= tolerancePercent && pronouncedPixels <= pronouncedPixelBudget)
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

        val cells = RegionCells(width, height)
        var mismatched = 0
        var pronounced = 0
        var maxDelta = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (samePixel(expectedPixels, actualPixels, x, y, channelTolerance)) continue
                mismatched++
                cells.add(x, y)
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
            lazyRegions = lazy { cells.regions() },
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

/**
 * A rectangle of a [DiffResult] holding [pixels] mismatched pixels, in the coordinates of the larger of
 * the two images (a size change's non-overlap included).
 */
public data class DiffRegion(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val pixels: Int,
)

/**
 * How close two mismatched pixels have to be to share a region: they are binned into cells this size,
 * and cells touching each other (diagonally too) are one region. 16 px keeps a changed word one region
 * and two buttons a screen apart two.
 */
public const val REGION_CELL: Int = 16

/**
 * The bins behind [DiffResult.regions]. Each cell keeps the exact bounding box of its pixels, so a region
 * is pixel-accurate however coarse the cells; the arrays are allocated by the first mismatched pixel, so
 * a comparison that matches pays nothing.
 */
private class RegionCells(
    width: Int,
    height: Int,
) {
    private val columns = (width + REGION_CELL - 1) / REGION_CELL
    private val rows = (height + REGION_CELL - 1) / REGION_CELL
    private var count: IntArray? = null
    private lateinit var minX: IntArray
    private lateinit var minY: IntArray
    private lateinit var maxX: IntArray
    private lateinit var maxY: IntArray

    fun add(
        x: Int,
        y: Int,
    ) {
        var counts = count
        if (counts == null) {
            counts = IntArray(columns * rows)
            count = counts
            minX = IntArray(counts.size) { Int.MAX_VALUE }
            minY = IntArray(counts.size) { Int.MAX_VALUE }
            maxX = IntArray(counts.size) { -1 }
            maxY = IntArray(counts.size) { -1 }
        }
        val cell = (y / REGION_CELL) * columns + x / REGION_CELL
        counts[cell]++
        if (x < minX[cell]) minX[cell] = x
        if (y < minY[cell]) minY[cell] = y
        if (x > maxX[cell]) maxX[cell] = x
        if (y > maxY[cell]) maxY[cell] = y
    }

    fun regions(): List<DiffRegion> {
        val counts = count ?: return emptyList()
        val seen = BooleanArray(counts.size)
        val regions = mutableListOf<DiffRegion>()
        val stack = ArrayDeque<Int>()
        for (start in counts.indices) {
            if (counts[start] == 0 || seen[start]) continue
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1
            var pixels = 0
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                pixels += counts[cell]
                left = minOf(left, minX[cell])
                top = minOf(top, minY[cell])
                right = maxOf(right, maxX[cell])
                bottom = maxOf(bottom, maxY[cell])
                val column = cell % columns
                val row = cell / columns
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val c = column + dx
                        val r = row + dy
                        if (c !in 0 until columns || r !in 0 until rows) continue
                        val next = r * columns + c
                        if (counts[next] == 0 || seen[next]) continue
                        seen[next] = true
                        stack.addLast(next)
                    }
                }
            }
            regions += DiffRegion(left, top, right - left + 1, bottom - top + 1, pixels)
        }
        return regions.sortedByDescending { it.pixels }
    }
}
