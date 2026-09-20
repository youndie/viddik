package io.github.youndie.viddik.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val GLYPH_CHECK_PROPERTY = "viddik.glyphCheck"

/**
 * The capture refusing to photograph text its font cannot draw (#6).
 *
 * The character under test is the one that issue was filed about: `←` is absent from the bundled
 * Roboto, so the host draws it, its advance differs per machine, and everything to its right moves.
 * What that looked like before this check existed was two days spent on a button two pixels out of
 * place.
 */
class ViddikGlyphCheckTest {
    @Test
    fun `a character the bundled font cannot draw fails the capture`() {
        val failure =
            assertThrows<IllegalStateException> {
                withGlyphCheck("true") { capture("← Board") }
            }

        val message = failure.message.orEmpty()
        assertTrue("U+2190" in message, "the message should name the codepoint: $message")
        assertTrue("← Board" in message, "and the text it came from: $message")
    }

    @Test
    fun `text the bundled font covers is captured`() {
        withGlyphCheck("true") { capture("‹ Board — 42 items…") }
    }

    @Test
    fun `the check does not run unless it is asked for`() {
        // A consumer drawing with a font of their own must not be failed by a check against ours,
        // which is why this is opt-in rather than always on.
        withGlyphCheck(null) { capture("← Board") }
    }

    private fun capture(text: String) {
        captureComposable(width = 200, height = 60) {
            MaterialTheme(typography = viddikTypography()) { Text(text) }
        }
    }

    private fun withGlyphCheck(
        value: String?,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(GLYPH_CHECK_PROPERTY)
        if (value ==
            null
        ) {
            System.clearProperty(GLYPH_CHECK_PROPERTY)
        } else {
            System.setProperty(GLYPH_CHECK_PROPERTY, value)
        }
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(GLYPH_CHECK_PROPERTY)
            } else {
                System.setProperty(GLYPH_CHECK_PROPERTY, previous)
            }
        }
    }
}
