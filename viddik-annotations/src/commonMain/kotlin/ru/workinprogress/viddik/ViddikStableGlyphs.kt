package ru.workinprogress.viddik

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether a viddik capture is what is drawing right now. `false` everywhere else, including in the
 * app itself — `CaptureEngine` is the only thing that provides `true`.
 *
 * Exists so a component can carry a capture-only adjustment without carrying its cost at runtime; see
 * [viddikStableGlyphs].
 */
public val LocalViddikCapture: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/**
 * Makes text drawn *inside* this subtree rasterize identically on every OS, even when the subtree
 * sits inside a layer carrying a `RenderEffect` — a `Modifier.blur`, a `graphicsLayer(renderEffect =
 * ...)`, a glass/backdrop effect.
 *
 * ### Why a fixture needs this at all
 *
 * viddik normally makes glyph rasterization platform-independent on its own, by handing the capture
 * canvas a matrix with a 1e-9 perspective term: Skia gives up on its glyph mask cache when the matrix
 * has perspective and fills glyph outlines with its own path rasterizer, which is identical in every
 * skiko build. Nothing to opt into, and it reaches every layer of the scene — except one.
 *
 * Skia factors the perspective back *out* of the matrix before rasterizing the contents of a layer
 * that carries an image filter, because image filters cannot work in a perspective space. Inside such
 * a layer the term is therefore absent, glyphs come from the host font backend (CoreText /
 * DirectWrite / FreeType) again, and the golden stops being portable. Measured macOS to Linux: text
 * under `blur(2.dp)` mismatches 1.40% of pixels where the same text without the effect mismatches
 * 0.00%.
 *
 * This modifier puts the term back where the capture root cannot reach — inside the layer. Same
 * measurement with it applied: 0.00%.
 *
 * ### Where to put it
 *
 * On the content that is being blurred, inside the effect rather than around it — the node the effect
 * reads, which for a backdrop is the one marked as the backdrop source:
 *
 * ```
 * Box(Modifier.blur(8.dp).viddikStableGlyphs()) { Text("under glass") }
 * Box(Modifier.layerBackdrop(backdrop).viddikStableGlyphs()) { Text("under glass") }
 * ```
 *
 * Applied around the effect instead of inside it, it does nothing at all: that is where the capture
 * root's own term already is, and where Skia already discards it.
 *
 * ### What it costs
 *
 * Outside a capture — which is to say in the app — it is one composition-local read and nothing else:
 * [LocalViddikCapture] is `false`, and the modifier returns the receiver untouched. That is why this
 * belongs in `viddik-annotations` and is safe to call from production code.
 *
 * During a capture it costs what perspective costs Skia: measured at 800x600, a blurred fixture goes
 * from 56 ms to 95 ms on Linux, and does not move on macOS.
 */
@Composable
public fun Modifier.viddikStableGlyphs(): Modifier =
    if (LocalViddikCapture.current) then(glyphPerspectiveNudge()) else this

/**
 * The platform half: concatenate a perspective term onto whatever canvas is drawing this subtree.
 *
 * Only the desktop capture harness has a use for it. On Android there is no viddik capture to be
 * inside, and the actual is the receiver unchanged rather than a differently-drawn UI.
 */
internal expect fun Modifier.glyphPerspectiveNudge(): Modifier
