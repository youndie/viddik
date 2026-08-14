package ru.workinprogress.viddik.core

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// Bundled Roboto (OFL license, see fonts/Roboto-OFL.txt — variable font, single file covers every
// weight) so screenshot capture never depends on whatever fonts happen to be installed on the host
// OS. Font(identity, data: ByteArray, weight, style) lives in androidx.compose.ui.text.platform, NOT
// androidx.compose.ui.text.font (where the Android resId-based Font(resId: Int, ...) overload
// lives) — importing the wrong package silently resolves to the resId overload instead and fails
// with a confusing "String but Int expected" error. Weight selection works via the variable font's
// own "wght" axis (Font's variationSettings default to FontVariation.Settings(weight, style)) — no
// separate static-per-weight files needed.
internal val robotoBytes: ByteArray by lazy {
    normalizeVerticalMetrics(
        checkNotNull(object {}.javaClass.classLoader.getResourceAsStream("fonts/Roboto-Variable.ttf")) {
            "Bundled font resource not found: fonts/Roboto-Variable.ttf"
        }.use { it.readBytes() },
    )
}

// Skia's font backends read vertical metrics from DIFFERENT tables of the same file: FreeType (Linux)
// and CoreText (macOS) take hhea, DirectWrite (Windows) takes OS/2.usWin*. In Roboto that is
// 1900/-500 vs 1946/512 — measured as ascent -12.988281 vs -13.302734 at 14px — so line height and
// baseline differ per OS and every line after the first drifts by a pixel. Force all three sources to
// hhea's values and set USE_TYPO_METRICS, so which table a backend prefers stops mattering. Applies to
// any font a consumer brings, not just the bundled one.
fun normalizeVerticalMetrics(font: ByteArray): ByteArray {
    val bytes = font.copyOf()

    fun u8(o: Int) = bytes[o].toInt() and 0xFF

    fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)

    fun s16(o: Int) = u16(o).toShort().toInt()

    fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()

    fun setU16(
        o: Int,
        v: Int,
    ) {
        bytes[o] = ((v shr 8) and 0xFF).toByte()
        bytes[o + 1] = (v and 0xFF).toByte()
    }

    fun setU32(
        o: Int,
        v: Long,
    ) {
        setU16(o, ((v shr 16) and 0xFFFF).toInt())
        setU16(o + 2, (v and 0xFFFF).toInt())
    }

    fun record(tag: String): Int = (0 until u16(4)).map { 12 + it * 16 }.first { String(bytes, it, 4, Charsets.ISO_8859_1) == tag }

    fun offset(tag: String) = u32(record(tag) + 8).toInt()

    fun checksum(
        from: Int,
        length: Int,
    ): Long {
        var sum = 0L
        for (i in from until from + ((length + 3) / 4) * 4 step 4) {
            var word = 0L
            for (k in 0 until 4) {
                word = (word shl 8) or (if (i + k < bytes.size) (bytes[i + k].toInt() and 0xFF).toLong() else 0L)
            }
            sum = (sum + word) and 0xFFFFFFFFL
        }
        return sum
    }

    val hhea = offset("hhea")
    val os2 = offset("OS/2")
    val ascender = s16(hhea + 4)
    val descender = s16(hhea + 6)
    setU16(hhea + 8, 0) // hhea.lineGap
    setU16(os2 + 68, ascender and 0xFFFF) // OS/2.sTypoAscender
    setU16(os2 + 70, descender and 0xFFFF) // OS/2.sTypoDescender
    setU16(os2 + 72, 0) // OS/2.sTypoLineGap
    setU16(os2 + 74, ascender) // OS/2.usWinAscent
    setU16(os2 + 76, -descender) // OS/2.usWinDescent
    setU16(os2 + 62, u16(os2 + 62) or 0x80) // OS/2.fsSelection: USE_TYPO_METRICS
    for (tag in listOf("hhea", "OS/2")) {
        val rec = record(tag)
        setU32(rec + 4, checksum(u32(rec + 8).toInt(), u32(rec + 12).toInt()))
    }
    val head = offset("head")
    setU32(head + 8, 0) // head.checkSumAdjustment must be zero while summing the whole file
    setU32(head + 8, (0xB1B0AFBAL - checksum(0, bytes.size)) and 0xFFFFFFFFL)
    return bytes
}

val ViddikFontFamily: FontFamily by lazy {
    FontFamily(
        Font("Roboto-Thin", robotoBytes, FontWeight.Thin),
        Font("Roboto-ExtraLight", robotoBytes, FontWeight.ExtraLight),
        Font("Roboto-Light", robotoBytes, FontWeight.Light),
        Font("Roboto-Regular", robotoBytes, FontWeight.Normal),
        Font("Roboto-Medium", robotoBytes, FontWeight.Medium),
        Font("Roboto-SemiBold", robotoBytes, FontWeight.SemiBold),
        Font("Roboto-Bold", robotoBytes, FontWeight.Bold),
        Font("Roboto-ExtraBold", robotoBytes, FontWeight.ExtraBold),
        Font("Roboto-Black", robotoBytes, FontWeight.Black),
    )
}

// Hinting must stay None — every "named" hinting level runs a platform-specific outline-adjustment
// algorithm, so telling FreeType and CoreText to both use "Slight" does not make them agree.
// Smoothing, on the other hand, must stay ON. That is the opposite of what it looks like from the
// outside, and it only holds together with the path rasterization forced by
// Modifier.deterministicGlyphRasterization() in CaptureEngine: once glyphs go through Skia's own
// scan converter, anti-aliased coverage comes out bit-identical across platforms, while an aliased
// mask turns every sub-pixel disagreement into a full 0<->255 pixel flip that no tolerance absorbs.
// Measured Windows vs Linux (fontlab harness, 8 fixtures, exact-pixel mismatch):
//   PlatformDefault:                                   0.76%-6.08%
//   None smoothing + None hinting (the previous default): 0.27%-5.65%
//   AntiAlias + None hinting, no path rasterization:   0.82%-8.66% (worse — mask AA differs per OS)
//   AntiAlias + None hinting + normalized metrics + path rasterization: 0.000% on 6 of 8 fixtures
//     (byte-identical PNGs), 3 px within channel tolerance on the 7th, font fallback on the 8th
@OptIn(ExperimentalTextApi::class)
val ViddikPlatformTextStyle: PlatformTextStyle by lazy {
    PlatformTextStyle(
        spanStyle = null,
        paragraphStyle =
            PlatformParagraphStyle(
                fontRasterizationSettings =
                    FontRasterizationSettings(
                        smoothing = FontSmoothing.AntiAlias,
                        hinting = FontHinting.None,
                        subpixelPositioning = false,
                        autoHintingForced = false,
                    ),
            ),
    )
}

// Rebuilds every Material3 text style from `base` (default: Typography()'s own tokens) with the
// bundled font family + forced rasterization settings applied — the same 15-style boilerplate this
// module's own DemoViddik.kt used to duplicate. Call this from your theme when the project has no
// bundled font of its own; it can't be forced transparently, since a fixture's own
// MaterialTheme(typography = ...) always wins over anything CaptureEngine could provide from outside
// it. A project that already ships its own font file should keep using it and run the bytes through
// normalizeVerticalMetrics() instead — substituting Roboto into a golden of a UI that doesn't use
// Roboto is worse than useless.
fun viddikTypography(base: Typography = Typography()): Typography =
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        displayMedium = base.displayMedium.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        displaySmall = base.displaySmall.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        headlineLarge = base.headlineLarge.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        headlineMedium =
            base.headlineMedium.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        headlineSmall = base.headlineSmall.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        titleLarge = base.titleLarge.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        titleMedium = base.titleMedium.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        titleSmall = base.titleSmall.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        bodyLarge = base.bodyLarge.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        bodyMedium = base.bodyMedium.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        bodySmall = base.bodySmall.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        labelLarge = base.labelLarge.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        labelMedium = base.labelMedium.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
        labelSmall = base.labelSmall.copy(fontFamily = ViddikFontFamily, platformStyle = ViddikPlatformTextStyle),
    )
