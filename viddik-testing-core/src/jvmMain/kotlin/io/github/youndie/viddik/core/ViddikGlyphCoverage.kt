package io.github.youndie.viddik.core

// A glyph the text's font doesn't have is the one thing about text rendering viddik can't make
// portable. Skia resolves it through the paragraph's FontCollection, whose fallback lookup ends at the
// host's installed fonts — so a ✕ used as a close button is Segoe UI Symbol on Windows, DejaVu in a
// bare Linux container, and something else again on macOS. Measured on this repo's own fixture: 21%
// mismatch for one line of text.
//
// It cannot be fixed from outside Compose, and that was established by experiment, not assumption:
//   - Registering extra fonts with Compose's fallback provider doesn't help. skparagraph resolves a
//     missing glyph through the *default* font manager, and skiko's FontMgrWithFallback (the one
//     Compose installs) consults the host first and registered fonts only "as a last resort".
//   - Listing several families on the text style doesn't help either: ParagraphBuilder pins a single
//     typeface (`res.typeface = resolved.typeface`), so per-character family fallback never runs.
//   - Replacing the FontCollection's font managers via reflection does remove the host from the chain,
//     but Skia then has no character matching at all (a plain TypefaceFontProvider doesn't implement
//     it) — every uncovered glyph becomes tofu — and registering per-weight variable-font instances
//     with Typeface.makeClone() reintroduces platform differences of its own. Measured: worse than
//     doing nothing.
//
// What is left is to know about it early. missingGlyphs() reads the font's cmap and tells you exactly
// which characters will be drawn by the host, so a fixture can fail with "nothing in the bundled font
// draws U+2715" instead of a 0.1% pixel diff discovered on someone else's machine. The fix is then a
// project decision: draw the icon as an icon, or bundle a font that covers the character.
public object ViddikGlyphCoverage {
    // Codepoints in `text` that `fontBytes` (the bundled Roboto by default) cannot draw, ignoring
    // whitespace and control characters. Non-empty means: this text's rendering depends on the host.
    public fun missingGlyphs(
        text: String,
        fontBytes: ByteArray = robotoBytes,
    ): Set<Int> {
        val covered = codepointsOf(fontBytes)
        return text
            .codePoints()
            .toArray()
            .filterNot { Character.isWhitespace(it) || Character.isISOControl(it) }
            .filterNot { it in covered }
            .toSet()
    }

    public fun codepointsOf(fontBytes: ByteArray): Set<Int> = cmapCodepoints(fontBytes)
}

// Minimal cmap reader — formats 4 (BMP) and 12 (full range) are the only ones a modern TTF/OTF ships,
// and a font with neither is one Skia couldn't map either.
private fun cmapCodepoints(font: ByteArray): Set<Int> {
    fun u8(offset: Int) = font[offset].toInt() and 0xFF

    fun u16(offset: Int) = (u8(offset) shl 8) or u8(offset + 1)

    fun u32(offset: Int) = (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()

    val cmap =
        (0 until u16(4))
            .map { 12 + it * 16 }
            .firstOrNull { String(font, it, 4, Charsets.ISO_8859_1) == "cmap" }
            ?.let { u32(it + 8).toInt() }
            ?: return emptySet()

    var subtable = -1
    var format = -1
    for (i in 0 until u16(cmap + 2)) {
        val record = cmap + 4 + i * 8
        val platform = u16(record)
        val encoding = u16(record + 2)
        val candidate = cmap + u32(record + 4).toInt()
        val candidateFormat = u16(candidate)
        val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
        // format 12 wins when both are present: it is the one that covers astral planes
        if (unicode && (candidateFormat == 4 || candidateFormat == 12) && format != 12) {
            subtable = candidate
            format = candidateFormat
        }
    }
    if (subtable < 0) return emptySet()

    val codepoints = mutableSetOf<Int>()
    if (format == 4) {
        val segments = u16(subtable + 6) / 2
        for (segment in 0 until segments) {
            val end = u16(subtable + 14 + segment * 2)
            val start = u16(subtable + 16 + segments * 2 + segment * 2)
            if (end != 0xFFFF) codepoints += start..end
        }
    } else {
        for (group in 0 until u32(subtable + 12).toInt()) {
            val record = subtable + 16 + group * 12
            codepoints += u32(record).toInt()..u32(record + 4).toInt()
        }
    }
    return codepoints
}
