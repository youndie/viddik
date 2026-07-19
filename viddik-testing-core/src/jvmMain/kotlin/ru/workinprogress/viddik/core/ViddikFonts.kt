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
private val robotoBytes: ByteArray by lazy {
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream("fonts/Roboto-Variable.ttf")) {
        "Bundled font resource not found: fonts/Roboto-Variable.ttf"
    }.use { it.readBytes() }
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

// FontRasterizationSettings.PlatformDefault deliberately differs per host OS (e.g. FontHinting.Normal
// on macOS vs FontHinting.Slight on Linux) — even with the identical bundled font, this alone leaves
// a small (~1-2%) pixel diff between a macOS dev machine and a Linux CI runner. Forcing every axis
// off (no hinting, no AA, no subpixel positioning) removes the platform-dependent rasterization step
// entirely, down to raw font-outline coverage. Measured empirically — this is not a guess:
//   PlatformDefault (varies per OS):                                0.68%-2.26% mismatch
//   forced AntiAlias + Slight hinting (i.e. Linux's own default):   0.66%-2.24% mismatch (WORSE —
//     "Slight" hinting still runs a platform-specific outline-adjustment algorithm; telling both
//     platforms to use the same *named* setting doesn't make FreeType and CoreText agree)
//   forced None smoothing + None hinting (this):                   0.08%-0.27% mismatch (best found)
@OptIn(ExperimentalTextApi::class)
val ViddikPlatformTextStyle: PlatformTextStyle by lazy {
    PlatformTextStyle(
        spanStyle = null,
        paragraphStyle =
            PlatformParagraphStyle(
                fontRasterizationSettings =
                    FontRasterizationSettings(
                        smoothing = FontSmoothing.None,
                        hinting = FontHinting.None,
                        subpixelPositioning = false,
                        autoHintingForced = false,
                    ),
            ),
    )
}

private const val CONSISTENT_RENDERING_PROPERTY = "viddik.consistentRendering"

// Opt-in, not on by default: forcing the bundled font + disabling AA/hinting makes text look visibly
// worse (aliased/jagged) in the recorded PNGs and in the live ViddikShowroom browser — a real
// tradeoff, not a strict improvement. Two legitimate workflows exist, pick one per project:
//   - Leave this off (default) and record goldens on the same CI runner that verifies them (see
//     .github/workflows/record-viddik-snapshots.yaml in this repo for the pattern) — full native
//     text rendering quality, but goldens are only valid for the exact environment they were
//     recorded on.
//   - Turn this on (-Dviddik.consistentRendering=true, or call viddikTypography() directly regardless
//     of the flag) to trade rendering fidelity for goldens that are actually portable between a dev
//     machine and CI.
object ViddikConsistentRendering {
    val isEnabled: Boolean
        get() = System.getProperty(CONSISTENT_RENDERING_PROPERTY)?.toBooleanStrictOrNull() == true
}

// Rebuilds every Material3 text style from `base` (default: Typography()'s own tokens) with the
// bundled font family + forced rasterization settings applied — the same 15-style boilerplate this
// module's own DemoViddik.kt used to duplicate. Callers decide when to use it (typically gated on
// ViddikConsistentRendering.isEnabled, as DemoViddik.kt does) rather than this being applied
// automatically, since it can't be forced transparently: a fixture's own MaterialTheme(typography =
// ...) always wins over anything CaptureEngine could try to provide from outside it.
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
