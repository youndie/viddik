package io.github.youndie.viddik.android

private const val OPAQUE = 255

/**
 * The window's own pixel, recovered from what the screen showed with that window over black and over white.
 *
 * A screenshot is composited: `shown = source + (1 - alpha) * behind`, with `source` premultiplied. Over
 * black that leaves `source` alone, and the difference between the two composites is what let the
 * background through, `1 - alpha`. So two screenshots give what PixelCopy gives from API 26: the straight
 * ARGB the window drew, with its transparency.
 *
 * @return the pixel as straight (not premultiplied) ARGB, the form `Bitmap.createBitmap(int[], …)` takes.
 */
internal fun pixelFromComposites(
    overBlack: Int,
    overWhite: Int,
): Int {
    fun channel(
        pixel: Int,
        shift: Int,
    ) = (pixel shr shift) and OPAQUE
    val shifts = intArrayOf(16, 8, 0)
    val letThrough = shifts.sumOf { channel(overWhite, it) - channel(overBlack, it) }
    val alpha = (OPAQUE - (letThrough + shifts.size / 2) / shifts.size).coerceIn(0, OPAQUE)
    if (alpha == 0) return 0
    return shifts.fold(alpha shl 24) { argb, shift ->
        val straight = ((channel(overBlack, shift) * OPAQUE + alpha / 2) / alpha).coerceAtMost(OPAQUE)
        argb or (straight shl shift)
    }
}

/**
 * Removes what the system put over a dialog's capture, in place.
 *
 * The bottom band (a navigation bar, a keyboard) goes transparent. Those pixels are the system's: a bar
 * colour the window's decor drew, or keys. Extending the row above would have kept the picture tidy and
 * lied about it: a pinned button reaching into the band came out with a block of its own colour below it.
 *
 * The top band is for a capture with a status bar in the picture at all: a screenshot, with icons and a
 * clock in it. Around them a status bar is see-through, so most of each row is already what the window drew,
 * and a row takes the colour most of it has, which leaves the scrim the scrim. A row no colour has the
 * majority of is a picture going under the bar, and takes the row just under the band. That used to be the
 * rule for every row, and a sheet ending right under the bar came out with a step above it, as wide as the
 * first row of its rounded top.
 *
 * @param top how many rows at the top are under a status bar in this capture.
 * @param bottom how many rows at the bottom are under a navigation bar or a keyboard.
 */
internal fun coverSystemBars(
    pixels: IntArray,
    width: Int,
    height: Int,
    top: Int,
    bottom: Int,
) {
    require(pixels.size == width * height) { "${pixels.size} pixels are not $width x $height" }
    // Nothing left between the bars: a capture of the bars, which covering would only hide.
    if (top + bottom >= height) return
    repeat(top) { row ->
        val start = row * width
        val majority = majorityColour(pixels, start, width)
        if (majority != null) {
            pixels.fill(majority, start, start + width)
        } else {
            pixels.copyInto(pixels, start, top * width, (top + 1) * width)
        }
    }
    pixels.fill(0, (height - bottom) * width, height * width)
}

/** The colour more than half of these [count] pixels have, or null when none does. */
private fun majorityColour(
    pixels: IntArray,
    start: Int,
    count: Int,
): Int? {
    val counts = HashMap<Int, Int>()
    for (index in start until start + count) counts.merge(pixels[index], 1, Int::plus)
    return counts.entries
        .maxBy { it.value }
        .takeIf { it.value * 2 > count }
        ?.key
}
