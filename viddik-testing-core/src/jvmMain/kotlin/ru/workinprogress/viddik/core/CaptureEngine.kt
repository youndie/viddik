@file:OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package ru.workinprogress.viddik.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Matrix44
import org.jetbrains.skia.Surface
import ru.workinprogress.viddik.annotations.AUTO_HEIGHT
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
public fun captureComposable(
    width: Int = DEFAULT_WIDTH,
    height: Int = AUTO_HEIGHT,
    compositionLocals: List<ProvidedValue<*>> = emptyList(),
    fontScale: Float = 1f,
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
                // Only the *font* scale is overridden, never the density itself. A font scale changes how
                // large text draws inside a canvas of a given size, which is what @Preview.fontScale asks
                // for; changing the density would also change what a dp is worth, and the whole capture
                // path treats dp and pixel as the same unit (ViddikDensityTest pins that).
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    *compositionLocals.toTypedArray(),
                ) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .let { if (autoHeight) it else it.height(height.dp) }
                            .onGloballyPositioned { measuredHeightPx = it.size.height },
                    ) {
                        content()
                    }
                }
            }
            waitForIdle()

            // Render the scene ourselves, into a canvas carrying the perspective nudge, instead of
            // captureToImage() on a node: a matrix on the scene's canvas reaches every layer of it,
            // including Dialog/Popup, which Compose renders into their own roots — a modifier on the
            // content node never reached those.
            val scene = (this as SkikoComposeUiTest).scene
            val rendered = renderSceneWithPerspective(scene, width, canvasHeight)

            val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
            if (roots.size <= 1) {
                captured = rendered
            } else {
                // The dialog is drawn on top of the scene — crop it out by its semantics bounds.
                val dialogNode = onNode(isDialog()).fetchSemanticsNode()
                val bounds = dialogNode.boundsInWindow
                val left = bounds.left.toInt().coerceIn(0, rendered.width - 1)
                val top = bounds.top.toInt().coerceIn(0, rendered.height - 1)
                captured =
                    rendered.getSubimage(
                        left,
                        top,
                        dialogNode.size.width.coerceIn(1, rendered.width - left),
                        dialogNode.size.height.coerceIn(1, rendered.height - top),
                    )
                measuredHeightPx = dialogNode.size.height
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
    return full.getSubimage(
        0,
        0,
        width.coerceAtMost(full.width),
        measuredHeightPx.coerceIn(1, full.height),
    )
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
// Applied to the scene's canvas rather than to a node, so it reaches every layer: content inside
// compositing layers (verified with alpha layers and elevated Cards) and Dialog/Popup roots alike.
// It has to be the scene: a modifier on the content node cannot reach a Dialog, which Compose renders
// into a root of its own — measured on a downstream consumer's suite, where dialog and bottom-sheet
// fixtures were the only cross-OS failures left (36 of 422) until this moved up to the scene.
//
// Spelled as sixteen positional arguments rather than a spread `*floatArrayOf(...)`: skiko 0.150.1
// turned Matrix44 into a value class, whose array constructor is no longer public — the sixteen-float
// form is the one that compiles against both the old vararg constructor and the new one.
// laid out as the 4x4 grid it is, rather than one float per line
@Suppress("ktlint:standard:argument-list-wrapping")
private val PERSPECTIVE_NUDGE =
    Matrix44(
        // identity, except for persp1 in the bottom row
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1e-9f, 0f, 1f,
    )

// The scene is drawn into a canvas of our own with the perspective already applied: unlike a modifier
// on a node, this covers every layer of the scene, Dialog and Popup roots included.
private fun renderSceneWithPerspective(
    scene: ComposeScene,
    width: Int,
    height: Int,
): BufferedImage {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.concat(PERSPECTIVE_NUDGE)
    // Compose Multiplatform 1.12 dropped ComposeScene.render(canvas, nanoTime) in favour of the two
    // halves it used to combine. The frame time it took is not missed here: the test harness has
    // already been driven to idle by waitForIdle() before this runs, so there is no animation left to
    // advance — all that is needed is a settled layout and one draw.
    scene.measureAndLayout()
    scene.draw(surface.canvas.asComposeCanvas())
    val encoded = checkNotNull(surface.makeImageSnapshot().encodeToData()) { "Screenshot encoding failed" }
    return ImageIO.read(ByteArrayInputStream(encoded.bytes))
}

public const val DEFAULT_WIDTH: Int = 400

private const val MAX_AUTO_HEIGHT_CANVAS = 4000
