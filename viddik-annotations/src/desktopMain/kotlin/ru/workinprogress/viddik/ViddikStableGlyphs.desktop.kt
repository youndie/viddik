package ru.workinprogress.viddik

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
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

// save/concat/draw/restore rather than a graphicsLayer transform: the point is to have the term in the
// canvas matrix at the moment the glyphs are rasterized, which is inside the layer being recorded —
// a layer transform would be applied when that layer is composited, which is exactly the place Skia
// has already discarded the perspective.
internal actual fun Modifier.glyphPerspectiveNudge(): Modifier =
    drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.skiaCanvas.save()
            canvas.skiaCanvas.concat(GLYPH_PERSPECTIVE)
            drawContent()
            canvas.skiaCanvas.restore()
        }
    }
