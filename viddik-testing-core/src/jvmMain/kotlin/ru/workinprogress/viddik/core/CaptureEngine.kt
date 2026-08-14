@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.workinprogress.viddik.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Matrix44
import ru.workinprogress.viddik.annotations.AUTO_HEIGHT
import java.awt.image.BufferedImage

@OptIn(ExperimentalTestApi::class)
fun captureComposable(
    width: Int = DEFAULT_WIDTH,
    height: Int = AUTO_HEIGHT,
    compositionLocals: List<ProvidedValue<*>> = emptyList(),
    content: @Composable () -> Unit,
): BufferedImage {
    val autoHeight = height == AUTO_HEIGHT
    val canvasHeight = if (autoHeight) MAX_AUTO_HEIGHT_CANVAS else height

    var captured: BufferedImage? = null
    var measuredHeightPx = 0

    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        runDesktopComposeUiTest(width = width, height = canvasHeight) {
            setContent {
                CompositionLocalProvider(*compositionLocals.toTypedArray()) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .let { if (autoHeight) it else it.height(height.dp) }
                            .deterministicGlyphRasterization()
                            .onGloballyPositioned { measuredHeightPx = it.size.height },
                    ) {
                        content()
                    }
                }
            }
            waitForIdle()

            val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
            if (roots.size <= 1) {
                captured = onRoot().captureToImage().toAwtImage()
            } else {
                val dialogNode = onNode(isDialog())
                captured = dialogNode.captureToImage().toAwtImage()
                measuredHeightPx = dialogNode.fetchSemanticsNode().size.height
            }
        }
    } finally {
        Dispatchers.resetMain()
    }

    val full = captured ?: error("Screenshot capture produced no image")
    if (!autoHeight) return full

    check(measuredHeightPx < MAX_AUTO_HEIGHT_CANVAS) {
        "Content is taller than the auto-height ceiling ($MAX_AUTO_HEIGHT_CANVAS px) — pass an explicit " +
            "height to @ViddikScreenshot instead of relying on auto-height."
    }
    return full.getSubimage(0, 0, width, measuredHeightPx.coerceAtLeast(1))
}

// Everything Skia draws except glyphs goes through its own scan converter, which is identical in every
// skiko build — shapes, gradients and shadows already come out byte-identical across platforms (verified).
// Glyphs are the exception: they are rasterized by the host font backend (CoreText / DirectWrite /
// FreeType), which no FontRasterizationSettings combination can reconcile. SkStrikeSpec::ShouldDrawAsPath()
// does have an escape hatch though — it gives up on the glyph mask cache when the canvas matrix carries
// perspective ("we don't cache perspective"), and fills the outlines with Skia's own rasterizer instead.
// A persp1 term of 1e-9 flips that switch: geometry shifts by ~1e-6 px (far below anything representable),
// while glyph rendering becomes platform-independent. Unconditional — unlike bundling a font, this costs
// nothing and changes nothing a reviewer would notice (it is not the same as disabling anti-aliasing).
//
// Caveat: this reaches everything drawn under this node, including content inside compositing layers
// (verified with alpha layers and elevated Cards) — but NOT a Dialog, which Compose renders into its own
// root. Fixtures that capture a dialog still depend on the host font backend.
// laid out as the 4x4 grid it is, rather than one float per line
@Suppress("ktlint:standard:argument-list-wrapping")
private val PERSPECTIVE_NUDGE =
    Matrix44(
        *floatArrayOf(
            // identity, except for persp1 in the bottom row
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1e-9f, 0f, 1f,
        ),
    )

private fun Modifier.deterministicGlyphRasterization(): Modifier =
    drawWithContent {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.concat(PERSPECTIVE_NUDGE)
        drawContent()
        canvas.restore()
    }

const val DEFAULT_WIDTH = 400

private const val MAX_AUTO_HEIGHT_CANVAS = 4000
