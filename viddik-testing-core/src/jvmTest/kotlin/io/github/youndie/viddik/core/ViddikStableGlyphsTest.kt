package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import io.github.youndie.viddik.LocalViddikCapture
import io.github.youndie.viddik.viddikStableGlyphs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs
import androidx.compose.ui.draw.blur as uiBlur

/**
 * What `Modifier.viddikStableGlyphs()` is for, pinned without needing a second operating system.
 *
 * Text inside a layer that carries a `RenderEffect` is the one thing the capture root cannot make
 * portable: Skia factors the perspective term out of the matrix before rasterizing such a layer's
 * content, so the glyphs go back to the host font backend and the golden stops reproducing across
 * OSes (measured macOS to Linux: 1.40% of pixels under `blur(2.dp)`, 0.00% with this modifier).
 *
 * The local stand-in for "did the term reach the glyphs" is whether applying the modifier changes the
 * rendering at all. It can only change it by moving glyph rasterization from the host's mask cache to
 * Skia's path rasterizer — geometry moves by ~1e-6 px, which is nothing — so a large difference means
 * the mechanism engaged, and no difference means it did not.
 */
private val probeStyle =
    TextStyle(
        fontSize = 18.sp,
        color = Color.Black,
        fontFamily = ViddikFontFamily,
        platformStyle = ViddikPlatformTextStyle,
    )

class ViddikStableGlyphsTest {
    @Test
    fun `the modifier changes how text under a blur is rasterized`() {
        val plain = capture { BlurredText(stable = false) }
        val stabilized = capture { BlurredText(stable = true) }

        val changed = energy(plain, stabilized)
        // Calibration: switching one string of 18sp text between the two rasterizers moves ~10^5 units
        // on this canvas, while the geometric effect of the term alone is a few hundred at most.
        assert(changed > 10_000) {
            "Applying viddikStableGlyphs() inside a blurred layer changed the rendering by only " +
                "$changed units, which is not a mask-to-path switch. Either the modifier stopped " +
                "reaching the glyphs, or Skia/Compose changed how filtered layers are rasterized and " +
                "text under a blur no longer needs it — check a real two-OS run before deleting " +
                "anything: this test is a proxy, portability is the actual claim."
        }
    }

    @Test
    fun `outside a capture it costs nothing and changes nothing`() {
        // Same fixture, same capture, with the local forced back to false the way it is in an app.
        val plain = capture { BlurredText(stable = false) }
        val stabilizedButNotCapturing =
            capture(compositionLocals = listOf(LocalViddikCapture provides false)) { BlurredText(stable = true) }

        assertEquals(
            0L,
            energy(plain, stabilizedButNotCapturing),
            "with LocalViddikCapture false the modifier must be the receiver untouched, so that a " +
                "component can carry it without carrying it into production rendering",
        )
    }

    @Test
    fun `it survives a third-party glass library recording the subtree it is on`() {
        // The shape from issue #11, with the library that found it: kyant0's layerBackdrop records the
        // marked subtree into a GraphicsLayer, and this modifier sits on the very node it records.
        // Written as a @Composable modifier factory, that combination killed the JVM inside Skia's
        // record optimizer when the recording closed. There is nothing to assert beyond coming back
        // with an image: if this regresses, the whole test task dies with the process.
        val image = capture { GlassOverText() }

        assertEquals(200, image.width)
    }

    @Test
    fun `it appends one node instead of a second copy of the chain`() {
        // The quiet half of the same bug. Written as `then(glyphPerspectiveNudge())`, the extension's
        // implicit receiver is the chain itself, so the modifier appended a copy of everything before
        // it: a blur applied twice, a padding applied twice, a layerBackdrop recorded twice. Only the
        // last of those crashed loudly. Padding is the cheap detector — doubling it moves the text.
        val plain = capture { PaddedText(stable = false) }
        val stabilized = capture { PaddedText(stable = true) }

        assertEquals(
            0L,
            energy(plain, stabilized),
            "outside a filtered layer this modifier has nothing to change, so any difference here is " +
                "the chain being applied twice",
        )
    }

    @Composable
    private fun PaddedText(stable: Boolean) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Box(
                Modifier
                    .padding(24.dp)
                    .let { if (stable) it.viddikStableGlyphs() else it }
                    .background(Color.LightGray),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Refraction 1234", style = probeStyle)
            }
        }
    }

    @Composable
    private fun GlassOverText() {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .viddikStableGlyphs()
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Refraction 1234", style = probeStyle)
            }
            Box(
                Modifier.fillMaxSize().drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(24.dp) },
                    effects = { blur(8.dp.toPx()) },
                ),
            )
        }
    }

    @Composable
    private fun BlurredText(stable: Boolean) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .uiBlur(2.dp)
                    .let { if (stable) it.viddikStableGlyphs() else it }
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Refraction 1234", style = probeStyle)
            }
        }
    }

    private fun capture(
        compositionLocals: List<androidx.compose.runtime.ProvidedValue<*>> = emptyList(),
        content: @Composable () -> Unit,
    ): BufferedImage =
        captureComposable(width = 200, height = 160, compositionLocals = compositionLocals, content = content)

    /** Sum of absolute channel deltas: survives a blur, which spreads a difference rather than erasing it. */
    private fun energy(
        a: BufferedImage,
        b: BufferedImage,
    ): Long {
        var sum = 0L
        for (y in 0 until minOf(a.height, b.height)) {
            for (x in 0 until minOf(a.width, b.width)) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                if (p == q) continue
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    sum += abs(((p shr shift) and 0xFF) - ((q shr shift) and 0xFF))
                }
            }
        }
        return sum
    }
}
