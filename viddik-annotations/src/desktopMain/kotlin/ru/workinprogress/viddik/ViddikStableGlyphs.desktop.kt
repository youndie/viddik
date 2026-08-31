package ru.workinprogress.viddik

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import org.jetbrains.skia.Matrix44

// The same 1e-9 persp1 term CaptureEngine puts on the scene canvas, spelled the same way (sixteen
// positional floats: skiko 0.150.1 made Matrix44 a value class whose array constructor is not public).
@Suppress("ktlint:standard:argument-list-wrapping")
private val GLYPH_PERSPECTIVE =
    Matrix44(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1e-9f, 0f, 1f,
    )

// Its exact inverse: the perturbation sits in a single off-diagonal cell, so E * E is the zero matrix
// and (I + E)(I - E) = I with no residual left behind for whatever draws next.
@Suppress("ktlint:standard:argument-list-wrapping")
private val GLYPH_PERSPECTIVE_INVERSE =
    Matrix44(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, -1e-9f, 0f, 1f,
    )

internal actual fun Modifier.glyphPerspectiveNudge(): Modifier = this then GlyphPerspectiveElement

private object GlyphPerspectiveElement : ModifierNodeElement<GlyphPerspectiveNode>() {
    override fun create() = GlyphPerspectiveNode()

    override fun update(node: GlyphPerspectiveNode) = Unit

    // A stable element with no parameters: two instances are always interchangeable, which is what
    // keeps Compose from tearing the node down and building it again on every recomposition.
    override fun hashCode() = GlyphPerspectiveElement::class.hashCode()

    override fun equals(other: Any?) = other === this
}

/**
 * Reads [LocalViddikCapture] at draw time rather than at composition time, so the modifier itself can
 * stay a plain, stable element.
 *
 * The gate has to live here for a reason that cost a JVM crash to find: written the obvious way — a
 * `@Composable fun Modifier.viddikStableGlyphs()` that reads the local and conditionally appends a
 * node — the factory produces a fresh modifier element on every composition. Something has to record
 * that subtree into a `GraphicsLayer`, and a layer whose modifier chain is rebuilt underneath it dies
 * in `SkRecordNoopSaveLayerDrawRestores` when the recording closes (`io.github.kyant0:backdrop`'s
 * `layerBackdrop`, deterministic on macOS/arm64 and Windows/x64, skiko 0.150.1). The canvas operations
 * below were never the problem: the same operations in a non-composable modifier never crashed.
 */
private class GlyphPerspectiveNode :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        if (!currentValueOf(LocalViddikCapture)) {
            drawContent()
            return
        }
        drawIntoCanvas { canvas ->
            // Concatenated in and back out rather than save/restore: the term has to apply on top of
            // whatever transform the layer is already drawing with, and the pair leaves the canvas
            // exactly as it was found.
            canvas.skiaCanvas.concat(GLYPH_PERSPECTIVE)
            drawContent()
            canvas.skiaCanvas.concat(GLYPH_PERSPECTIVE_INVERSE)
        }
    }
}
