# Goldens across operating systems

Record on macOS, verify on Linux CI, or the other way round: no Docker, no "record only on the
runner", no per-OS baselines. Every pull request to viddik verifies its own committed goldens on
`ubuntu-latest`, `macos-latest` (arm64) and `windows-latest` at once
(`.github/workflows/verify-goldens.yaml`).

That isn't free, because Skia's text rendering is platform-specific in two independent ways. viddik
fixes one of them for you and gives you the tool for the other.

## Glyph rasterization: fixed for you

Everything Skia draws except glyphs goes through its
own scan converter, identical in every skiko build; glyphs instead go to the host font backend
(CoreText / DirectWrite / FreeType), and no combination of `FontRasterizationSettings` makes those
three agree. `CaptureEngine` sidesteps the backend entirely: it hands the canvas a matrix carrying a
1e-9 perspective term, which is Skia's own documented condition for abandoning the glyph mask cache
and filling glyph outlines with its regular path rasterizer. Geometry shifts by ~1e-6 px, text keeps
full anti-aliasing, and rendering stops depending on the OS. Nothing to configure.

## Fonts: bundle one

Skia renders text through whatever fonts the host OS has installed, so a golden
recorded against the macOS system UI font can't match a bare Linux runner. Bundle a font file:

- **No font of your own?** Use the bundled Roboto (OFL, variable, single file for every weight):

  ```kotlin
  MaterialTheme(typography = viddikTypography()) { content() }
  ```

  That covers text styled through `MaterialTheme.typography`. A style a design system builds itself
  — `TextStyle(fontSize = ..., fontWeight = ...)` — bypasses it and draws in the host's font; give
  those `fontFamily = ViddikFontFamily` and `platformStyle = ViddikPlatformTextStyle`.

- **Already bundling your design system's font?** Keep it, and run the bytes through
  `normalizeVerticalMetrics()` when loading:

  ```kotlin
  val fontBytes = normalizeVerticalMetrics(resource("fonts/YourFont.ttf").readBytes())
  ```

  This one matters more than it sounds. Font backends read vertical metrics from *different tables of
  the same file* — FreeType and CoreText take `hhea`, DirectWrite takes `OS/2.usWin*`. In Roboto those
  disagree (1900/−500 vs 1946/512, i.e. ascent −12.988 vs −13.303 at 14px), so line height and
  baseline differ per OS and every line after the first in a paragraph drifts by a pixel — the single
  largest source of cross-platform diff we measured. `normalizeVerticalMetrics()` forces `hhea`,
  `OS/2.sTypo*` and `OS/2.usWin*` to agree and sets `USE_TYPO_METRICS`, so which table a backend
  prefers stops mattering. `ViddikFontFamily` already goes through it.

## Glyphs the font does not have

What still isn't portable: glyphs your bundled font doesn't have. A `世界`, an emoji, or a `✕`
used as a close button falls back to a *host* font — real CJK on a Mac, tofu boxes in a bare
container, Segoe UI Symbol on Windows. This one can't be fixed from outside Compose (a fallback font
registered with Skia is only consulted after the host's, and ParagraphBuilder pins one typeface per
style, so per-character family fallback never runs — both measured, see
[CLAUDE.md](../CLAUDE.md)), so viddik reports it instead:

```kotlin
check(ViddikGlyphCoverage.missingGlyphs(label).isEmpty()) { "host fonts would draw these: $label" }
```

`missingGlyphs(text, fontBytes = bundled Roboto)` reads the font's own `cmap`. Non-empty means that
text renders differently per machine — draw the icon as an icon, or bundle a font that covers it.

A capture can refuse such text instead of photographing it:

```kotlin
viddik {
    glyphCheck = true                       // fail when the font cannot draw what the fixture says
    glyphCheckFont = "src/main/res/font/plex.ttf"   // ...against your own font, if you bundle one
}
```

Off by default, because the check reads one font and your fixtures may legitimately draw with
another. With it on, a fixture drawing `←` fails with `Nothing in the font draws U+2190 (←), so the
host would` — instead of a golden that is stable on the machine that recorded it and 0.06% different
on the next one, with the moved pixels sitting *after* the character rather than on it. The bundled
Roboto covers `‹ « < × … •` and none of `← → ↑ ↓ ✕ ▸`.

## How a comparison decides

A golden matches when at most **0.05%** of its pixels differ (`tolerancePercent`), a pixel counting as
different only past **±2** on some channel (`channelTolerance`). For scale: adding one character to a
button label moves 1.32% of the pixels.

Beside the share there is a **floor**, so a small fixture is not failed by the handful of stray
pixels a large one absorbs: up to **16** mismatched pixels pass whatever the size
(`minMismatchedPixels`), as long as every one of them is within a channel delta of **96**
(`floorChannelDelta`). The floor is for cross-OS residue, which is faint — Linux-recorded goldens
verified on macOS differ by at most 13 px at a delta of 47. An edit of the same size is not: a full
stop appended to a heading is 12 px at a delta of 223, and the floor does not absorb it.

The share counts every pixel alike, though, so on a full 360×640 screen those 12 px (0.005%) still
pass at the default 0.05%. To catch changes that small, set `tolerancePercent = 0.0`: the floor then
does all the forgiving, and Linux goldens still verify on macOS (measured in
[screenshot-bench](https://github.com/youndie/screenshot-bench)).

```kotlin
viddik {
    tolerancePercent = 0.0   // default 0.05 (percent of pixels)
    channelTolerance = 2     // default
    minMismatchedPixels = 16 // default; 0 turns the floor off
    floorChannelDelta = 96   // default; 255 makes the floor count pixels only
}
```

Without the plugin these are the `viddik.tolerancePercent`, `viddik.channelTolerance`,
`viddik.minMismatchedPixels` and `viddik.floorChannelDelta` system properties. The same numbers decide
what a record writes: a golden the comparison accepts stays on disk unless `--force` says otherwise.

## Text under blur and glass

Three rendering paths are not portable, and they have one thing in common: the layer's content is
rasterized in a space the capture root never reaches. **`Modifier.blur`**, **a runtime-shader
`RenderEffect`** (which is what glass libraries are built on), and **a layer read back with
`toImageBitmap()`**. Everything else that re-roots a subtree is fine and checked as such — `Dialog`,
`Popup`, `CompositingStrategy.Offscreen`, a shadow with a non-rectangular clip, a plainly recorded
layer: the `Canary/*` fixtures verify all of them on ubuntu, macos and windows per pull request.
Skia factors the perspective out of the canvas matrix before rasterizing such a layer's content
(image filters cannot work in a perspective space), which switches off exactly the mechanism that
makes glyphs platform-independent, so they go back to the host font backend. Measured macOS to Linux,
at viddik's own defaults: text under `blur(2.dp)` mismatches 1.40% of pixels, the same text with no
effect 0.00%, geometry under the same blur 0.00%.

**The fix is `Modifier.viddikStableGlyphs()`**, which puts the term back inside the layer. Same
measurement with it applied: 0.00%.

```kotlin
Box(Modifier.blur(8.dp).viddikStableGlyphs()) { Text("under glass") }
Box(Modifier.layerBackdrop(backdrop).viddikStableGlyphs()) { Text("under glass") }
```

It goes on the content being blurred, *inside* the effect rather than around it — around it is where
the capture root's own term already is, and where Skia already discards it. That means it lives in
whatever composable draws the glass, production code included, which is why it ships in
`viddik-annotations` (safe to depend on from `main`) and does nothing at all unless a viddik capture
is what is drawing: outside one it is a single composition-local read and returns the receiver
untouched. `CaptureEngine` can't apply it for you — Compose exposes no hook into how a layer draws
(`GraphicsLayer` is final, `SkiaBackedCanvas` internal); [CLAUDE.md](../CLAUDE.md) has that
measurement too.

Where that placement isn't possible — a third-party glass component you don't control — raising the
global threshold to cover one such fixture would un-check every other one, so a fixture can carry its
own budget instead:

```kotlin
@ViddikScreenshot(name = "Segmented - three ways", group = "Glass", tolerancePercent = 6.0)
```

It overrides both the default and the module's `tolerancePercent` for that fixture alone, and applies to
every entry the fixture expands to (`darkVariant`, `@PreviewParameter` values, a multipreview). The
failure message says when the threshold that let something through was the fixture's own.

Two things it is not for. It isn't a way to quiet a fixture that has started failing — that is a
regression until measured otherwise, and the number written here should be one you measured on the
platforms you actually verify on. And it isn't a per-fixture off switch: anything outside 0–100 is a
build error, and 100 itself compiles with a warning, because a fixture that cannot fail is a green
check that checks nothing.
