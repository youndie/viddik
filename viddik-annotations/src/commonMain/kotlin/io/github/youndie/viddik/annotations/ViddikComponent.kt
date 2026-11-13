package io.github.youndie.viddik.annotations

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

/**
 * A [ViddikScreenshot] tolerance that was not given, for the same reason [UNSPECIFIED] exists: KSP
 * substitutes the default before the processor sees it, so the default has to be a value no one would
 * write. A tolerance is a share of pixels, so a negative one is exactly that.
 */
public const val UNSPECIFIED_TOLERANCE: Double = -1.0

/**
 * A [ViddikScreenshot.captureAtMillis] that was not given — the normal case, a fixture captured once
 * it is idle. Negative for the same reason [UNSPECIFIED_TOLERANCE] is: a moment on the clock after
 * the content is set is never before it.
 */
public const val UNSPECIFIED_CAPTURE_TIME: Long = -1L

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
    /**
     * Share of pixels this one fixture may differ by, or `null` — the normal case — to be judged by
     * whatever the run itself is set to (`viddik.tolerancePercent`, otherwise
     * `DEFAULT_TOLERANCE_PERCENT`).
     *
     * Nullable rather than defaulted to the engine's own number so that "this fixture states its own
     * budget" stays distinguishable from "this fixture says nothing": the engine's default belongs to
     * the run, and a component carrying a copy of it would pin a fixture to today's value.
     */
    public val tolerancePercent: Double? = null,
    /**
     * Milliseconds of the test clock after which this fixture is captured with the clock frozen, or
     * `null` — the normal case — to be captured once Compose is idle.
     *
     * For content that is never idle: an animation that runs for as long as it is composed, such as
     * a spinner turning on a frame loop. Waiting for idle never returns for it, so the capture
     * stops the clock, sets the content and advances by exactly this much instead, which gives the
     * same picture on every run. See `@ViddikScreenshot(captureAtMillis)`.
     */
    public val captureAtMillis: Long? = null,
    public val content: @Composable () -> Unit,
)
