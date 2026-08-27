package ru.workinprogress.viddik.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the one assumption that lets `@Preview`'s dp be read straight into [captureComposable]'s
 * pixels: the harness renders at density 1, so 1.dp is 1px.
 *
 * The equality was accidental before `@Preview` support — nothing set the density, nothing read it,
 * and `CaptureEngine` happened to pass a pixel count into `Modifier.width(...dp)` without the two ever
 * being reconciled. Now `widthDp` from a fixture's `@Preview` becomes a capture width, so the day the
 * default changes is the day every golden silently moves. This test is what makes that day loud.
 *
 * It is deliberately an assertion rather than a `LocalDensity` override: forcing a density would move
 * the goldens now, on the guess that the current one isn't 1. Measuring first is the cheaper order.
 */
class ViddikDensityTest {
    @Test
    fun `the capture harness renders at density 1 so a dp is a pixel`() {
        var observed: Density? = null

        captureComposable(width = 8, height = 8) {
            observed = LocalDensity.current
            Box(Modifier.size(8.dp()))
        }

        val density = checkNotNull(observed) { "the fixture never composed" }
        assertEquals(
            1f,
            density.density,
            "@Preview.widthDp is read into captureComposable's pixel width on the assumption that " +
                "1.dp == 1px. That no longer holds, so every size read off a @Preview is now wrong by " +
                "this factor.",
        )
        assertEquals(1f, density.fontScale, "an unscaled capture should not be scaling text either")
    }

    @Test
    fun `a font scale reaches the fixture without disturbing the density`() {
        var observed: Density? = null

        captureComposable(width = 8, height = 8, fontScale = 1.5f) {
            observed = LocalDensity.current
            Box(Modifier.size(8.dp()))
        }

        val density = checkNotNull(observed) { "the fixture never composed" }
        assertEquals(1.5f, density.fontScale, "@Preview.fontScale should reach the composition")
        assertEquals(
            1f,
            density.density,
            "scaling text must not scale the canvas: @PreviewFontScale would otherwise resize every " +
                "golden it produces, and dp would stop being a pixel.",
        )
    }
}

// Written out rather than imported so the test reads as "8 device-independent pixels", which is the
// unit the assertion above is about.
private fun Int.dp() = androidx.compose.ui.unit.Dp(toFloat())
