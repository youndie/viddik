package ru.workinprogress.viddik.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViddikGlyphCoverageTest {
    @Test
    fun `bundled font covers latin cyrillic and common punctuation`() {
        assertEquals(emptySet<Int>(), ViddikGlyphCoverage.missingGlyphs("Привет, world — № 42 · 1 200 ₽ … ×"))
    }

    @Test
    fun `symbols drawn as text are reported, since the host would supply them`() {
        // ✕ as a close button and ✓ as a status marker are the realistic case: Roboto has neither,
        // so these render from whatever the machine has installed and the golden stops being portable.
        assertEquals(setOf(0x2713, 0x2715), ViddikGlyphCoverage.missingGlyphs("✓ ✕"))
    }

    @Test
    fun `coverage is read from the font itself, not hardcoded`() {
        val roboto = ViddikGlyphCoverage.codepointsOf(robotoBytes)
        assertTrue(roboto.size > 900, "expected the bundled Roboto's cmap, got ${roboto.size} codepoints")
        assertTrue(0x0416 in roboto) // Ж
        assertTrue(0x4E16 !in roboto) // 世 — not in a Latin/Cyrillic font
    }
}
