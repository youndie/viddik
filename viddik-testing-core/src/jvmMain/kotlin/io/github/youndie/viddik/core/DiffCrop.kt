package io.github.youndie.viddik.core

import java.awt.image.BufferedImage

/**
 * The changed part of a comparison at a size it can be read at (#79): for each group of nearby
 * [DiffResult.regions], one row of three panels — the expected image, the actual one and the diff — cut
 * to the group plus [CROP_MARGIN], side by side, at 1:1 and scaled up (nearest neighbour, whole
 * factors) when the group is small.
 *
 * A full screen pair is 1080×2400 twice, and a change of one or two pixels in it is invisible once the
 * picture is scaled down to fit a viewer, which is what an artifact viewer does and what a vision model
 * does with a large image. The three full-size PNGs stay beside it; this is the one to open first.
 */
public object DiffCrop {
    /**
     * The crop of [diff], comparing [expected] with [actual]; null when nothing differs. Pixels one of the
     * images does not have (a size change) are transparent in its panel.
     */
    public fun render(
        expected: BufferedImage,
        actual: BufferedImage,
        diff: DiffResult,
    ): BufferedImage? {
        val groups = groups(diff.regions)
        if (groups.isEmpty()) return null
        val canvasWidth = maxOf(expected.width, actual.width)
        val canvasHeight = maxOf(expected.height, actual.height)
        val panels = listOf(expected, actual, diff.diffImage)

        val rows =
            groups.map { group ->
                val box = group.grow(CROP_MARGIN).clip(canvasWidth, canvasHeight)
                val scale = scaleFor(box)
                box to scale
            }
        val width = rows.maxOf { (box, scale) -> 3 * box.width * scale + 2 * GUTTER }
        val height = rows.sumOf { (box, scale) -> box.height * scale } + GUTTER * (rows.size - 1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        var top = 0
        for ((box, scale) in rows) {
            panels.forEachIndexed { index, image ->
                val left = index * (box.width * scale + GUTTER)
                for (y in 0 until box.height * scale) {
                    val sourceY = box.y + y / scale
                    for (x in 0 until box.width * scale) {
                        val sourceX = box.x + x / scale
                        if (sourceX < image.width && sourceY < image.height) {
                            out.setRGB(left + x, top + y, image.getRGB(sourceX, sourceY))
                        }
                    }
                }
            }
            top += box.height * scale + GUTTER
        }
        return out
    }

    /**
     * The regions merged into at most [MAX_CROPS] boxes, largest first: a region joins a box when the two
     * are within [CROP_JOIN] px of each other, so one row shows a change and its neighbours, and changes a
     * screen apart get rows of their own. What is left after [MAX_CROPS] boxes joins the nearest one, so
     * every changed pixel is in some panel.
     */
    internal fun groups(regions: List<DiffRegion>): List<Box> {
        val boxes = mutableListOf<Box>()
        for (region in regions) {
            val box = Box(region.x, region.y, region.width, region.height)
            val near = boxes.indexOfFirst { it.gapTo(box) <= CROP_JOIN }
            when {
                near >= 0 -> {
                    boxes[near] = boxes[near].union(box)
                }

                boxes.size < MAX_CROPS -> {
                    boxes += box
                }

                else -> {
                    val nearest = boxes.indices.minBy { boxes[it].gapTo(box) }
                    boxes[nearest] = boxes[nearest].union(box)
                }
            }
        }
        // Joining can bring two boxes within reach of each other; settle that before drawing.
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    if (boxes[i].gapTo(boxes[j]) <= CROP_JOIN) {
                        boxes[i] = boxes[i].union(boxes[j])
                        boxes.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
        return boxes
    }

    private fun scaleFor(box: Box): Int =
        (MIN_CROP_SIDE / maxOf(1, minOf(box.width, box.height))).coerceIn(1, MAX_CROP_SCALE)

    internal data class Box(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height

        fun union(other: Box): Box {
            val left = minOf(x, other.x)
            val top = minOf(y, other.y)
            return Box(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
        }

        /** The larger of the horizontal and vertical gaps between the two, 0 when they touch or overlap. */
        fun gapTo(other: Box): Int =
            maxOf(0, maxOf(other.x - right, x - other.right), maxOf(other.y - bottom, y - other.bottom))

        fun grow(by: Int): Box = Box(x - by, y - by, width + 2 * by, height + 2 * by)

        fun clip(
            maxWidth: Int,
            maxHeight: Int,
        ): Box {
            val left = x.coerceIn(0, maxWidth)
            val top = y.coerceIn(0, maxHeight)
            return Box(left, top, right.coerceAtMost(maxWidth) - left, bottom.coerceAtMost(maxHeight) - top)
        }
    }
}

/** Context kept around a changed group in its crop, so the change can be told apart from what is beside it. */
public const val CROP_MARGIN: Int = 16

/** Regions this close (px) share a crop. */
public const val CROP_JOIN: Int = 48

/** At most this many rows in one crop image. */
public const val MAX_CROPS: Int = 4

/** A crop whose shorter side is under this many pixels is scaled up towards it. */
private const val MIN_CROP_SIDE = 96

private const val MAX_CROP_SCALE = 8

/** Transparent space between panels and rows. */
private const val GUTTER = 8
