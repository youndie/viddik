package ru.workinprogress.viddik.annotations

import androidx.compose.runtime.Composable

public const val AUTO_HEIGHT: Int = -1

/**
 * A [ViddikScreenshot] size that was not given, as opposed to one that was given as a number.
 *
 * The processor cannot tell "argument omitted" from "argument written out" on its own — KSP hands it
 * the annotation with defaults already substituted — so the default has to be a value no one would
 * write. Without it, falling back to `@Preview` would be indistinguishable from a fixture that
 * deliberately asked for the old default of 400.
 */
public const val UNSPECIFIED: Int = Int.MIN_VALUE

public data class ViddikComponent(
    public val name: String,
    public val group: String,
    public val width: Int = 400,
    public val height: Int = AUTO_HEIGHT,
    /**
     * Text scaling to render at, as `@Preview.fontScale` states it. Kept out of [width]/[height],
     * which stay pixel counts: a font scale changes how large text draws inside a canvas of a given
     * size, not the size of the canvas.
     */
    public val fontScale: Float = 1f,
    public val content: @Composable () -> Unit,
)
