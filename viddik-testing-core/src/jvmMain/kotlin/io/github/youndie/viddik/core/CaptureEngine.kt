@file:OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.youndie.viddik.core

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.LocalViddikCapture
import io.github.youndie.viddik.annotations.AUTO_HEIGHT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Matrix44
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
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
                    // Provided before the caller's own locals, so a caller can still override it —
                    // ViddikStableGlyphsTest turns it off to check the modifier costs nothing when no
                    // capture is running.
                    LocalViddikCapture provides true,
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

            if (glyphCheckEnabled) {
                val drawn = mutableListOf<String>()
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .forEach { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.forEach { drawn += it.text }
                    }
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .forEach { node ->
                        node.config.getOrNull(SemanticsProperties.EditableText)?.let { drawn += it.text }
                    }
                failOnUncoveredGlyphs(drawn)
            }

            val scene = (this as SkikoComposeUiTest).scene

            val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
            // A second root means a Dialog or a Popup. Only a dialog is worth cropping to: it is a
            // window of its own and the scene around it is empty scrim. A popup — a dropdown menu, a
            // tooltip — is positioned inside the window and belongs in the image together with what it
            // is anchored to, so the whole scene is the capture. Asking for a dialog node
            // unconditionally is what this used to do, and it failed every popup fixture outright with
            // a message about dialogs (found by the Canary/Popup fixture).
            //
            // Read before the render rather than after it, because the answer decides how tall a
            // surface the render needs.
            val dialogNodes = onAllNodes(isDialog()).fetchSemanticsNodes()
            val dialogCapture = roots.size > 1 && dialogNodes.isNotEmpty()

            // An auto-height capture keeps `measuredHeightPx` of what it draws and throws the rest
            // away (the crop below), so that is all the surface has to be. The *scene* stays at
            // `canvasHeight`, so nothing about the layout changes — only the raster target, and with
            // it the PNG encode and the decode back, which is where the time was going: measured on
            // this repository's suite, 1353 ms of capture time became 764 ms, with all 28 goldens
            // byte-identical (issue #31). A dialog is the exception and keeps the whole canvas: it is
            // centred in the window rather than laid out from the top, and auto-height does not
            // measure it reliably in the first place.
            val surfaceHeight =
                if (autoHeight && !dialogCapture) {
                    measuredHeightPx.coerceIn(1, canvasHeight)
                } else {
                    canvasHeight
                }

            // Render the scene ourselves, into a canvas carrying the perspective nudge, instead of
            // captureToImage() on a node: a matrix on the scene's canvas reaches every layer of it,
            // including Dialog/Popup, which Compose renders into their own roots — a modifier on the
            // content node never reached those.
            val rendered = renderSceneWithPerspective(scene, width, surfaceHeight)

            if (!dialogCapture) {
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

private const val GLYPH_CHECK_PROPERTY = "viddik.glyphCheck"
private const val GLYPH_CHECK_FONT_PROPERTY = "viddik.glyphCheckFont"

private val glyphCheckEnabled: Boolean
    get() = System.getProperty(GLYPH_CHECK_PROPERTY)?.toBooleanStrictOrNull() == true

/** The font the check reads, `viddik.glyphCheckFont` for a consumer that bundles its own. */
private val glyphCheckFont: ByteArray
    get() = System.getProperty(GLYPH_CHECK_FONT_PROPERTY)?.let { File(it).readBytes() } ?: robotoBytes

/**
 * Refuses to photograph text whose font cannot draw it.
 *
 * A glyph the font lacks is resolved by the host — Segoe UI Symbol here, DejaVu there — and the
 * result is a golden that is stable on the machine that recorded it and different on the next one,
 * discovered later as a fraction of a percent of drifting pixels somewhere near, but not at, the
 * character responsible. Issue #6 is two days spent on exactly that: the diff pointed at a button,
 * the cause was a `←` in the link beside it pushing everything two pixels left.
 *
 * Off unless asked for, because the check can only read one font and a consumer may legitimately
 * draw with another: pointing it at the wrong one would fail fixtures that are perfectly portable.
 * `viddik { glyphCheck = true }` turns it on for a module themed with `viddikTypography()`;
 * `glyphCheckFont` points it at the font a module bundles itself.
 */
private fun failOnUncoveredGlyphs(drawn: List<String>) {
    val font = glyphCheckFont
    val offenders = drawn.associateWith { ViddikGlyphCoverage.missingGlyphs(it, font) }.filterValues { it.isNotEmpty() }
    if (offenders.isEmpty()) return

    val codepoints =
        offenders.values
            .flatten()
            .toSortedSet()
            .joinToString { "U+%04X (%s)".format(it, String(Character.toChars(it))) }
    val where = offenders.keys.joinToString(", ") { "\"${it.take(60)}\"" }
    error(
        "Nothing in the font draws $codepoints, so the host would: $where. A golden recorded that " +
            "way is stable here and different on the next machine. Draw the character as an icon, " +
            "bundle a font that covers it, or point $GLYPH_CHECK_FONT_PROPERTY at the font this " +
            "module actually uses.",
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
