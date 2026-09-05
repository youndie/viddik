package io.github.youndie.viddik.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import io.github.youndie.viddik.annotations.ViddikScreenshot
import io.github.youndie.viddik.core.ViddikFontFamily
import io.github.youndie.viddik.core.ViddikPlatformTextStyle
import io.github.youndie.viddik.viddikStableGlyphs
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// One golden per way a subtree can be re-rooted — drawn into a root, a surface or a coordinate space
// of its own — because that is the one thing that has ever broken cross-platform text here. viddik's
// determinism is applied at a single point, the scene canvas, and anything that re-roots can step
// outside it: Dialog and Popup did until the term moved to the scene, layers carrying an image filter
// still do. Both were found by a consumer's suite going red, months after shipping.
//
// These exist to be verified on ubuntu, macos and windows at once (`verify-goldens.yaml`). That is the
// half `RerootingCanaryTest` cannot cover: it can say whether the term reaches the glyphs on this
// machine, but "the same pixels on every OS" is only ever settled by three machines comparing files.
//
// Rules, learned from #11 the expensive way:
//
//  - every canary contains text. A canary without glyphs asserts that Skia's scan converter is
//    deterministic, which was never in question — the reporter's blur fixtures were green for exactly
//    that reason, and the bug looked like a backdrop-library problem for three weeks.
//  - the variant is chosen to keep the difference sharp. The same defect measures 1.40% under
//    blur(2.dp) and 0.05% under blur(8.dp): a wide blur spreads it until it hides under the channel
//    tolerance.
//  - the ones that carry viddikStableGlyphs() are the mechanisms measured to be holes. Removing it is
//    how you check the canary can still fail; RerootingCanaryTest pins that on one machine.

private val canaryText =
    TextStyle(
        fontSize = 15.sp,
        color = Color.Black,
        fontFamily = ViddikFontFamily,
        platformStyle = ViddikPlatformTextStyle,
    )

@Composable
private fun CanaryLabel(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        BasicText("Refraction 1234", style = canaryText)
    }
}

/** A no-op runtime-shader effect: glass libraries are moving to SkSL, and the layer is the subject here. */
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

// --- mechanisms that keep the term: no stabilizer, and they must stay portable on their own --------

@ViddikScreenshot(name = "Dialog", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryDialog() {
    Dialog(onDismissRequest = {}) {
        CanaryLabel(Modifier.size(180.dp, 90.dp))
    }
}

@ViddikScreenshot(name = "Popup", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryPopup() {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Popup {
            CanaryLabel(Modifier.size(180.dp, 90.dp))
        }
    }
}

@ViddikScreenshot(name = "Offscreen compositing", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryOffscreen() {
    CanaryLabel(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen })
}

@ViddikScreenshot(name = "Shadow and clip", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryShadowAndClip() {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        CanaryLabel(
            Modifier
                .fillMaxSize()
                .shadow(8.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
        )
    }
}

@ViddikScreenshot(name = "Layer drawn back", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryLayerDrawnBack() {
    val layer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
    ) {
        CanaryLabel(Modifier.fillMaxSize())
    }
}

// --- mechanisms measured to be holes: portable only with the stabilizer inside them ----------------

@ViddikScreenshot(name = "Blur", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryBlur() {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        CanaryLabel(Modifier.fillMaxSize().blur(2.dp).viddikStableGlyphs())
    }
}

@ViddikScreenshot(name = "Runtime shader effect", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryRuntimeShader() {
    CanaryLabel(
        Modifier
            .fillMaxSize()
            .graphicsLayer { renderEffect = passthroughEffect() }
            .viddikStableGlyphs(),
    )
}

@ViddikScreenshot(name = "Layer read back as an image", group = "Canary", width = 220, height = 150)
@Composable
fun CanaryLayerAsImage() {
    val layer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawImage(runBlocking { layer.toImageBitmap() })
            },
    ) {
        CanaryLabel(Modifier.fillMaxSize().viddikStableGlyphs())
    }
}
