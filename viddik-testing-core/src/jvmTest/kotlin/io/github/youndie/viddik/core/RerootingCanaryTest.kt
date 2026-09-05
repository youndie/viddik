@file:OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import io.github.youndie.viddik.LocalViddikCapture
import io.github.youndie.viddik.viddikStableGlyphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Matrix44
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs

// Every way a subtree can be re-rooted, asked one question: does viddik's perspective term still reach
// the glyphs inside it?
//
// The term is what keeps glyph rasterization off the host font backend, and it is applied in one place
// — the scene canvas. Compose's rendering is a hierarchy, so anything that draws a subtree into a root,
// a surface or a coordinate space of its own can step outside it, and text inside that subtree stops
// being portable. Two such mechanisms were found by consumers' suites going red months apart
// (Dialog/Popup, then layers carrying an image filter); this table is what makes the third one show up
// here instead.
//
// The proxy: render each shape twice, with the term and without, and measure how much changes. Only
// glyph rasterization can react to it — geometry moves by ~1e-6 px — so a large number means the term
// reached the text, and zero means it did not. That needs one machine. What it cannot answer is whether
// the result is actually identical across operating systems; the `Canary` goldens answer that, on three
// OSes, and these two halves are meant to be read together.
//
// Numbers below are from 31.08.2026, CMP 1.12.0 / skiko 0.150.1, macOS/arm64. See issue #14.

private const val RW = 220
private const val RH = 150

@Suppress("ktlint:standard:argument-list-wrapping")
private val TERM =
    Matrix44(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1e-9f, 0f, 1f,
    )

private val probeText =
    TextStyle(
        fontSize = 15.sp,
        color = Color.Black,
        fontFamily = ViddikFontFamily,
        platformStyle = ViddikPlatformTextStyle,
    )

@Composable
private fun Label(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        BasicText("Refraction 1234", style = probeText)
    }
}

// A runtime-shader effect that returns its input untouched: glass libraries are moving to SkSL, and a
// no-op keeps the comparison about the layer rather than about what the shader draws.
private val PASSTHROUGH_SKSL =
    """
    uniform shader content;
    half4 main(float2 c) { return content.eval(c); }
    """.trimIndent()

private fun passthroughEffect() =
    ImageFilter
        .makeRuntimeShader(
            RuntimeShaderBuilder(RuntimeEffect.makeForShader(PASSTHROUGH_SKSL)),
            "content",
            null,
        ).asComposeRenderEffect()

/**
 * @param keepsTheTerm whether viddik's mechanism reaches text inside this shape, as measured. `false`
 *   means the shape is a hole and its fixtures need `Modifier.viddikStableGlyphs()` to be portable.
 */
private data class Mechanism(
    val name: String,
    val keepsTheTerm: Boolean,
    val content: @Composable (Boolean) -> Unit,
)

private val mechanisms: List<Mechanism> =
    listOf(
        Mechanism("no re-rooting at all", keepsTheTerm = true) { Label(Modifier.fillMaxSize()) },
        Mechanism("alpha layer", keepsTheTerm = true) {
            Label(Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f })
        },
        Mechanism(
            "Dialog",
            keepsTheTerm = true,
        ) { Dialog(onDismissRequest = {}) { Label(Modifier.size(180.dp, 90.dp)) } },
        Mechanism("Popup", keepsTheTerm = true) { Popup { Label(Modifier.size(180.dp, 90.dp)) } },
        Mechanism("Modifier.blur", keepsTheTerm = false) { stable ->
            Label(Modifier.fillMaxSize().blur(2.dp).stabilize(stable))
        },
        Mechanism("runtime-shader RenderEffect", keepsTheTerm = false) { stable ->
            Label(Modifier.fillMaxSize().graphicsLayer { renderEffect = passthroughEffect() }.stabilize(stable))
        },
        Mechanism("CompositingStrategy.Offscreen", keepsTheTerm = true) { stable ->
            Label(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .stabilize(stable),
            )
        },
        Mechanism("shadow and a non-rectangular clip", keepsTheTerm = true) { stable ->
            Label(
                Modifier
                    .fillMaxSize()
                    .shadow(8.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .stabilize(stable),
            )
        },
        Mechanism("a layer recorded and drawn back", keepsTheTerm = true) { stable -> LayerDrawnBack(stable) },
        Mechanism("a layer read back as an image", keepsTheTerm = false) { stable -> LayerAsImage(stable) },
    )

// Calibration: switching one string of 15sp text between the host's mask cache and Skia's path
// rasterizer moves ~76 000 units on this canvas. The geometric effect of the term alone is a few
// hundred at most, so anything above this is the switch and anything at zero is no switch at all.
private const val SWITCHED = 10_000L

private fun Modifier.stabilize(stable: Boolean) = if (stable) viddikStableGlyphs() else this

@Composable
private fun LayerDrawnBack(stable: Boolean) {
    val layer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
    ) {
        Label(Modifier.fillMaxSize().stabilize(stable))
    }
}

@Composable
private fun LayerAsImage(stable: Boolean) {
    val layer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                val bitmap = runBlocking { layer.toImageBitmap() }
                drawImage(bitmap)
            },
    ) {
        Label(Modifier.fillMaxSize().stabilize(stable))
    }
}

class RerootingCanaryTest {
    @Test
    fun `every mechanism is still on the side of the line it was measured on`() {
        val moved =
            mechanisms.mapNotNull { mechanism ->
                val reaches =
                    energy(render(true) { mechanism.content(false) }, render(false) { mechanism.content(false) })
                val switched = reaches >= SWITCHED
                if (switched == mechanism.keepsTheTerm) {
                    null
                } else if (mechanism.keepsTheTerm) {
                    "${mechanism.name}: the term no longer reaches text inside it ($reaches units of change, " +
                        "expected the ~76 000 of a rasterizer switch). This mechanism has become a hole: goldens " +
                        "with text inside it stop reproducing across OSes, and the Canary fixture for it will go " +
                        "red on the three-OS job. Fixtures need Modifier.viddikStableGlyphs() inside it."
                } else {
                    "${mechanism.name}: the term now reaches text inside it ($reaches units of change) where it " +
                        "did not before. Either Skia stopped factoring perspective out of this path, or Compose " +
                        "stopped re-rooting here — check whether viddikStableGlyphs() is still needed for it " +
                        "before anything else is concluded."
                }
            }

        assertEquals(emptyList<String>(), moved)
    }

    @Test
    fun `the modifier restores the mechanisms that lose the term, and is a no-op in the ones that do not`() {
        val wrong =
            mechanisms.mapNotNull { mechanism ->
                val difference =
                    energy(render(true) { mechanism.content(true) }, render(true) { mechanism.content(false) })
                when {
                    mechanism.keepsTheTerm && difference != 0L -> {
                        "${mechanism.name}: viddikStableGlyphs() changed the rendering by $difference units " +
                            "where the term already reached the glyphs. It is supposed to have nothing left to do " +
                            "here, so this is the modifier doing something other than what it claims."
                    }

                    !mechanism.keepsTheTerm && difference < SWITCHED -> {
                        "${mechanism.name}: viddikStableGlyphs() moved only $difference units, which is not a " +
                            "rasterizer switch. The one escape hatch this project offers for glass no longer " +
                            "works here, and fixtures using it are recording host-specific text again."
                    }

                    else -> {
                        null
                    }
                }
            }

        assertEquals(emptyList<String>(), wrong)
    }

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

    @OptIn(ExperimentalTestApi::class)
    private fun render(
        perspective: Boolean,
        content: @Composable () -> Unit,
    ): BufferedImage {
        var captured: BufferedImage? = null
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            runDesktopComposeUiTest(width = RW, height = RH) {
                setContent {
                    // CaptureEngine provides this for real; the probe renders the scene itself, so it
                    // has to, or viddikStableGlyphs() is a no-op and every column reads zero.
                    CompositionLocalProvider(LocalViddikCapture provides true) {
                        Box(Modifier.fillMaxSize().background(Color.White)) { content() }
                    }
                }
                waitForIdle()
                captured = draw((this as SkikoComposeUiTest).scene, perspective)
            }
        } finally {
            Dispatchers.resetMain()
        }
        return checkNotNull(captured)
    }

    private fun draw(
        scene: ComposeScene,
        perspective: Boolean,
    ): BufferedImage {
        val surface = Surface.makeRasterN32Premul(RW, RH)
        if (perspective) surface.canvas.concat(TERM)
        scene.measureAndLayout()
        scene.draw(surface.canvas.asComposeCanvas())
        val encoded = checkNotNull(surface.makeImageSnapshot().encodeToData())
        return ImageIO.read(ByteArrayInputStream(encoded.bytes))
    }
}
