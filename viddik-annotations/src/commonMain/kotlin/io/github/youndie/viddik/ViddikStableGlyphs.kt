package io.github.youndie.viddik

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
 *
 * The explicit `Modifier.` receiver in the body is not decoration: `then(glyphPerspectiveNudge())`
 * reads as if it appended one node, but the extension's implicit receiver is this same chain, so it
 * appends a *copy of the whole chain* plus the node. With a `layerBackdrop` in that chain the backdrop
 * is recorded twice and the JVM dies inside Skia when the recording closes (issue #11); with a `blur`
 * in it, the blur is silently applied twice. `ViddikStableGlyphsTest` pins both halves.
 */
public fun Modifier.viddikStableGlyphs(): Modifier = this then Modifier.glyphPerspectiveNudge()

/**
 * The platform half: a draw node that concatenates a perspective term onto whatever canvas is drawing
 * this subtree, and only while [LocalViddikCapture] says a capture is running.
 *
 * Deliberately a plain modifier over a node rather than a `@Composable` factory reading the local
 * itself. A composable factory recreates its element on every composition, and a modifier element
 * recreated while a `GraphicsLayer` is recording takes the JVM down inside Skia's record optimizer —
 * measured against `io.github.kyant0:backdrop` in issue #11, and pinned by `ViddikStableGlyphsTest`.
 * Reading the local from the node instead keeps the same "free outside a capture" behaviour without
 * that shape.
 *
 * Only the desktop capture harness has a use for it. On Android there is no viddik capture to be
 * inside, and the actual is the receiver unchanged rather than a differently-drawn UI.
 */
internal expect fun Modifier.glyphPerspectiveNudge(): Modifier
