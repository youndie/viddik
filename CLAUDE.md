# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A screenshot-testing toolkit for Compose Multiplatform, analogous to Showkase (component catalog) +
Paparazzi (screenshot capture), but rendering through a real Compose Desktop/Skiko JVM window instead of
Android/LayoutLib — no emulator, no AVD, works headless in CI. Originally built inside a larger
monorepo as `screenshot-annotations`/`screenshot-processor`/`screenshot-testing-core`, extracted here as
its own project so unrelated repos can depend on it as an external artifact instead of `project(...)`.
Renamed `screenshot-*` → `viddik-*` (modules, packages, classes) as part of that extraction — see git
history for the exact rename map if cross-referencing old code/docs that still say
`DesktopScreenshot`/`ScreenshotComponent`/etc.

## Build & Test Commands

```bash
./gradlew build                                  # Build all 3 modules
./gradlew :viddik-testing-core:jvmTest            # Self-test suite (DemoViddik.kt) — NOT `test`, the
                                                   # module's jvm() target is unnamed so Gradle names the
                                                   # task jvmTest, not test (that only applies to plain
                                                   # kotlin("jvm") consumer modules like dev:uikit-sandbox)
./gradlew ktlintCheck                             # Style check (all 3 modules; jvmTest sourceSet in
                                                   # viddik-testing-core is deliberately excluded, see
                                                   # its build.gradle.kts — KSP-generated code lives there)
./gradlew ktlintFormat                            # Auto-fix style violations
./gradlew dokkaGenerate                           # Aggregated HTML docs at build/dokka/html/index.html
./gradlew publishToMavenLocal                     # Publish all 3 modules for local consumers to pick up
./gradlew :viddik-processor:publishToMavenLocal   # Single module, e.g. after a processor-only change
VIDDIK_RECORD_MODE=true ./gradlew :viddik-testing-core:jvmTest --tests "*runAllScreenshots*"
                                                   # Re-record the self-test golden PNGs (src/jvmTest/snapshots/)
```

CI: `.github/workflows/verify-goldens.yaml` runs `:viddik-testing-core:jvmTest` on every pull request
across `ubuntu-latest` / `macos-latest` / `windows-latest` (`fail-fast: false`, uploads the
`_DIFF.png` artifacts on failure), and `publish-viddik-snapshot.yaml` publishes on push to `main`.
Dependencies are batched weekly by Renovate (`renovate.json5`) — Kotlin and KSP move together, and
anything under `org.jetbrains.compose` is labelled `goldens-may-change` because it can move a pixel:
those PRs need a re-record and a look at the diff, and the three-OS check failing on them is the
signal working, not a flake.

Downstream consumers resolve `ru.workinprogress:viddik-*` via `mavenLocal()` — after any change here,
`publishToMavenLocal` before rebuilding them. Versions are bumped by hand in
`gradle.properties` (`viddik.version`, currently `0.1.1`); Gradle/consumers cache by exact version+build
hash so a republish under the same version is picked up by build cache invalidation, not by version
diffing — if a consumer's build looks stale after a republish, `--no-build-cache` or bump the version.

## Module Structure

Dependency order: `viddik-annotations` (no deps on the others) → `viddik-testing-core` (depends on
`viddik-annotations`, KSP-processed by `viddik-processor` in its own `jvmTest`) / any consumer module
(depends on both `viddik-annotations` + `viddik-testing-core`, KSP-processed by `viddik-processor`).

- **viddik-annotations** — Kotlin Multiplatform (`android()` + `jvm("desktop")`), Compose Multiplatform
  UI only (LazyColumn/Text/clickable — no platform APIs), so adding targets here is unconstrained.
  - `ViddikScreenshot` (`annotations/ViddikScreenshot.kt`) — the marker annotation (`name`, `group`,
    `width` default 400, `height` default `AUTO_HEIGHT`, `darkVariant`).
  - `ViddikComponent` (`annotations/ViddikComponent.kt`) — runtime data class the processor emits into
    the generated registry (`name`, `group`, `width`, `height`, `content: @Composable () -> Unit`).
    `AUTO_HEIGHT = -1` lives here too.
  - `ViddikPreviewLabel` (`annotations/ViddikPreviewLabel.kt`) — optional interface
    (`val previewLabel: String`) a `@PreviewParameter` provider's value type can implement for a
    descriptive golden-file name instead of a bare index; see the processor bullet below for the
    fallback behavior.
  - `ViddikShowroom` (`ViddikShowroom.kt`) — the portable component browser: list grouped by `group`,
    click navigates to a full-screen detail view with a `← group/name` back row. Used both as the
    interactive desktop browser (`ViddikShowroom(GeneratedViddikRegistry.components)` in a
    `JavaExec`-launched window) and self-tested as an ordinary screenshot in `DemoViddik.kt`.
  - `LocalViddikDarkTheme` (`ViddikTheme.kt`) — `compositionLocalOf { false }`. There's no real "system
    dark mode" on a JVM/desktop test harness, so `darkVariant = true` fixtures must read this local
    themselves and branch their own `MaterialTheme` (see `DemoViddik.kt`'s `SampleTextPreview`) — the
    processor only wraps the dark-variant content in `CompositionLocalProvider(LocalViddikDarkTheme
    provides true) { ... }`, it doesn't force a theme.

- **viddik-processor** — Plain `kotlin("jvm")`, KSP `SymbolProcessor`. Publication needs an explicit
  `publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }`
  block in its own `build.gradle.kts` — `maven-publish`/the `viddik.publishing` convention plugin does
  NOT auto-create one for a plain-jvm module the way it does for `kotlin("multiplatform")` targets.
  - `ViddikProcessorProvider` — `SymbolProcessorProvider`, registered via
    `src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
    (a one-line file naming `ru.workinprogress.viddik.processor.ViddikProcessorProvider` — **if you
    rename this class, this file must be updated too**, or KSP fails at runtime with `Provider ... not
    found` even though everything compiles fine). Reads the `viddik.generateTests` KSP option
    (`environment.options["viddik.generateTests"] != "false"`) — consumers that only want the browser
    registry and not JUnit5 test generation (e.g. an Android app module) set
    `ksp { arg("viddik.generateTests", "false") }`.
  - `ViddikSymbolProcessor` — scans `@ViddikScreenshot`-annotated functions, one-shot (`invoked` guard,
    since KSP calls `process()` repeatedly across rounds). Rules enforced: must also be `@Composable`;
    all parameters must have defaults, **except** exactly one parameter annotated `@PreviewParameter`
    (mirrors Compose tooling's own convention) — that's the sole non-default-param exception.
    - Static entries (no `@PreviewParameter`) generate one `add(ViddikComponent(...))` call per
      annotation, plus a second one suffixed `" Dark"` wrapped in `CompositionLocalProvider` when
      `darkVariant = true`.
    - Parameterized entries can't be resolved at compile time (`PreviewParameterProvider.values` is a
      runtime `Sequence<T>`) — the processor instead generates code that instantiates the provider
      class **at runtime** and `.mapIndexed`s over `.values`, so KSP only ever needs the provider's
      qualified class name, not its contents. Label naming, precedence:
      `(param as? ViddikPreviewLabel)?.previewLabel ?: param.toString()`, `.take(60)`, **then the loop
      index is always appended** (`" #" + index`) regardless — this was a real bug fix: several
      variants of one downstream consumer's list-item state had identical `toString()` output in their
      first 60 characters and collapsed into one golden file before the index suffix was added
      unconditionally.
    - Generates `GeneratedViddikRegistry` (object, `val components: List<ViddikComponent>`) always, and
      `GeneratedViddikTests` (a `@TestFactory` JUnit5 class calling `ViddikEngine.dynamicTests(...)`)
      only when `generateTests` is true. Both go in package `ru.workinprogress.viddik.generated`.
    - KotlinPoet `CodeBlock`s use `·` (middle dot) as escaped literal spaces in generated string
      concatenation — plain spaces get collapsed/reformatted by KotlinPoet's own indentation logic.

- **viddik-testing-core** — Kotlin Multiplatform, but only ever declares a plain unnamed `jvm()` target
  (do not swap in a named `jvm("desktop")` here or the module's own `jvmMain`/`jvmTest` source-set
  folders stop matching and `compileKotlinJvm` silently goes `NO-SOURCE` — this exact mistake shipped
  once via a shared `wip.kmp-base-library` convention plugin that force-added Android/iOS/wasmJs
  targets this AWT-only module can't actually support). JUnit5 is isolated to this module — nothing
  else in the project has an opinion on test framework.
  - `CaptureEngine.captureComposable(width, height, compositionLocals, content)` — the actual capture,
    via `runDesktopComposeUiTest`. Requires `Dispatchers.setMain(UnconfinedTestDispatcher())` before
    composing (Compose Desktop UI tests don't auto-install a Main dispatcher the way Android
    instrumented/Robolectric tests do; anything using `collectAsStateWithLifecycle` or similar throws
    without it). `height == AUTO_HEIGHT` renders into a tall fixed canvas (`MAX_AUTO_HEIGHT_CANVAS =
    4000px`), measures actual content height via `onGloballyPositioned`, and crops the final image —
    avoids hand-picking a `height = 680`-style magic number per fixture. Content that opens
    `Dialog`/`Popup` produces a second semantics root — `onRoot()` throws `"Expected exactly '1' node
    but found '2'"` in that case, so the engine checks `onAllNodes(isRoot())` first and falls back to
    `onNode(isDialog())` for both the capture and the measured height. Auto-height is NOT reliable for
    dialog content (measured height has been observed as either the full canvas or an under-measured
    placeholder depending on what `isDialog()` matches in a given dialog tree) — fixtures that open a
    dialog directly or indirectly should always pass an explicit `height`. The capture root also
    gets `Modifier.deterministicGlyphRasterization()` unconditionally — a 1e-9 perspective term on the
    canvas matrix, which makes Skia rasterize glyph outlines with its own path rasterizer instead of
    the host font backend. That is what makes goldens portable across OSes; the same caveat as above
    applies to dialogs, whose separate root the modifier does not reach.
  - `ImageDiffer` — pixel-for-pixel diff, paints every mismatched (or out-of-bounds) pixel solid red in
    the output `DiffResult.diffImage` so the artifact is a readable visual diff, not just a boolean.
    `DiffResult.matches(tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT)` — NOT a `val`, a
    function. `DEFAULT_TOLERANCE_PERCENT = 0.05` plus `DEFAULT_CHANNEL_TOLERANCE = 2` (per-channel
    slack, originally for lossless-codec decode differences between platform Skia builds, also covers
    the last few glyph-outline quantization pixels). It was 0.5 while cross-platform text rendering
    was unfixed — see "Cross-platform golden portability" for why it no longer needs to be.
  - `ViddikEngine` — the record/verify harness (Paparazzi-equivalent). `VIDDIK_RECORD_MODE` env var
    (not a Gradle property — set it in the shell/CI step) toggles write-golden vs compare-and-fail.
    `viddik.snapshotsDir`/`viddik.reportsDir` **system properties** (not env vars) override the
    defaults (`src/desktopTest/snapshots`, `build/reports/screenshots`) — needed because different
    consumer modules name their JVM target differently (`jvm()` vs `jvm("desktop")`), and
    `GeneratedViddikTests` calls `dynamicTests(...)` with no parameters, so there's no way to thread an
    override through generated code; every consumer's `build.gradle.kts` sets these explicitly via
    `systemProperty(...)` on its `Test` task — see `viddik-testing-core/build.gradle.kts` itself for
    the pattern (`src/jvmTest/snapshots`, since this module's own target is unnamed `jvm()`). Same
    system-property override pattern for the diff tolerance: `viddik.tolerancePercent`.
  - `ViddikFonts.kt` — everything here is `by lazy` top-level `val`s/plain functions in `jvmMain` (not
    `jvmTest`), so any consumer can use them, not just the self-test:
    - `ViddikFontFamily` — bundled Roboto, OFL, `src/jvmMain/resources/fonts/Roboto-Variable.ttf` (a
      single variable font covers every weight; Skia resolves the requested weight from the "wght"
      axis automatically via `FontVariation.Settings(weight, style)`, no static per-weight files
      needed). **Gotcha that cost real debugging time**: the ByteArray-loading `Font(identity, data,
      weight, style)` overload lives in `androidx.compose.ui.text.platform`, NOT
      `androidx.compose.ui.text.font` (where the Android `Font(resId: Int, ...)` overload lives) —
      importing the wrong package resolves to the resId overload silently and fails with a confusing
      "String but Int expected" compile error, not an unresolved-reference error.
    - `ViddikPlatformTextStyle` — forces `FontRasterizationSettings(smoothing = AntiAlias, hinting =
      None, subpixelPositioning = false)`. Hinting must stay `None` (every named level runs a
      platform-specific outline adjustment); smoothing must stay **on**, which is counterintuitive
      and only correct because `CaptureEngine` forces path rasterization — see "Cross-platform golden
      portability" for the numbers before changing either.
    - `normalizeVerticalMetrics(font: ByteArray): ByteArray` — rewrites `hhea` / `OS/2.sTypo*` /
      `OS/2.usWin*` in a TTF so all three agree, sets `USE_TYPO_METRICS`, and recomputes the affected
      table checksums plus `head.checkSumAdjustment` (fonts load fine without valid checksums in
      practice, but DirectWrite is the one backend known to care). Public on purpose: consumers
      bundling their own font need it as much as the bundled Roboto does.
    - `viddikTypography(base: Typography = Typography())` — rebuilds all 15 Material3 text styles
      from `base` with `ViddikFontFamily` + `ViddikPlatformTextStyle` applied. Lowercase name
      deliberately (ktlint's `function-naming` rule flags PascalCase for anything not annotated
      `@Composable`, and this isn't one — the `.editorconfig` exception only covers `@Composable`).
    - Typography can't be forced onto a fixture from outside — `CaptureEngine` renders whatever
      `content()` the fixture passes in, and if that fixture calls its own `MaterialTheme(typography =
      ...)`, that always wins over anything provided further out. So every consumer calls
      `viddikTypography()` (or bundles its own normalized font) itself. Glyph rasterization is the
      opposite: `CaptureEngine` applies it to the capture root unconditionally, nothing to opt into.
  - `ViddikGlyphCoverage.kt` — `missingGlyphs(text, fontBytes = robotoBytes)` / `codepointsOf(font)`,
    a minimal cmap (format 4 and 12) reader. Pure diagnostic, no Compose involvement: it answers "will
    this string reach the host's fonts?" before a golden encodes the answer.
  - `DemoViddik.kt` (jvmTest) — the project's own self-test AND a living usage example: static fixture,
    dark-variant fixture, a `ViddikShowroom` self-screenshot, and a `@PreviewParameter` fixture using a
    bare `String` (which can't implement `ViddikPreviewLabel`, demonstrating the `toString()` fallback
    naming path). `demoTypography` is `viddikTypography()` unconditionally — the demo has no font of
    its own, and that is the only reason these goldens reproduce on any OS. The committed PNGs were
    recorded on Windows and verify green on Linux (and vice versa), so a failure here is a real
    regression, not rendering noise; re-record with `VIDDIK_RECORD_MODE=true` and visually check the
    PNG (and the `_DIFF.png` in `build/reports/screenshots/`) before trusting either outcome.

## Cross-platform golden portability

Solved — goldens record on one OS and verify on another. Getting here took a full measurement pass
(Windows native vs Linux in Docker, 17 rendering variants × 8 fixtures); the numbers below are
measured on this codebase, not guessed, and the dead ends are recorded so they don't get retried.
macOS was the one backend that couldn't be measured locally, and `verify-goldens.yaml` closed that
gap on the first PR: Windows-recorded goldens pass on `macos-latest` (CoreText, and arm64 — skiko
ships a separate native library per architecture) as well as on Linux and Windows.

Two independent causes, both now fixed at the source:

1. **Vertical font metrics.** Backends read them from *different tables of the same file*: FreeType
   (Linux) and CoreText (macOS) take `hhea`, DirectWrite (Windows) takes `OS/2.usWin*`. Roboto's
   disagree — 1900/−500 vs 1946/512, i.e. `skia.Font(...).metrics.ascent` = −12.988281 on Linux vs
   −13.302734 on Windows at 14px. Line height and baseline therefore differ per OS, and every line
   after the first in a paragraph drifts by a pixel. Fixed by `normalizeVerticalMetrics()`
   (`ViddikFonts.kt`), applied to the bundled font and exported for consumers' own fonts.
   Horizontal shaping was never the problem: skparagraph computes advances from the font tables, and
   `TextLayoutResult.getLineRight()` came out identical to four decimals on both OSes even though the
   raw scaler advances differ (FreeType rounds them to integers, DirectWrite doesn't).
2. **Glyph rasterization.** The host font backend rasterizes glyphs; everything else Skia draws goes
   through its own scan converter and was already byte-identical across platforms (verified with a
   text-free fixture: 0 differing pixels). Fixed by `Modifier.deterministicGlyphRasterization()`
   (`CaptureEngine.kt`), unconditional — see its comment for the `SkStrikeSpec::ShouldDrawAsPath`
   mechanism.

Measured, Windows vs Linux, exact-pixel mismatch across the harness fixtures:

| Configuration | Mismatch |
|---|---|
| Host fonts, platform-default rasterization | 1.93%–27.4% |
| Bundled font, platform-default rasterization | 0.76%–6.08% |
| Bundled font, forced `None` smoothing + `None` hinting (the old default) | 0.27%–5.65% |
| Bundled font, forced `AntiAlias` + `None` hinting, no path rasterization | 0.82%–8.66% — **worse**, per-OS mask AA |
| + normalized metrics, no path rasterization | 0.000%–7.48% (fixes layout, not rasterization) |
| + path rasterization, un-normalized metrics | 0.000%–5.09% (fixes rasterization, not layout) |
| **+ both (current)** | **0.000% on 6 of 8 fixtures** (byte-identical PNGs), 3 px within channel tolerance on the 7th |

Dead ends, don't retry without new evidence:

- **`AntiAlias` + `Slight` hinting** (explicitly requesting *Linux's own* `PlatformDefault` on both
  OSes): no improvement. Every named hinting level runs a platform-specific outline-adjustment
  algorithm; naming the same setting on both platforms doesn't make FreeType and CoreText agree.
- **Disabling anti-aliasing**, which is what the old default did: actively counterproductive once path
  rasterization is in play. An aliased mask turns every sub-pixel disagreement into a full 0↔255 pixel
  flip that no tolerance absorbs; with AA on, the same content comes out byte-identical.
- **Supersampling** (render at 4×/8× density, box-filter down): 0.88%–8.71%, no better than the
  baseline and worse on a shadowed Card. The residual is structural (sub-pixel layout shifts), not
  noise, so averaging doesn't remove it.
- **Making Skia use FreeType on macOS**: impossible without forking skiko. `skia-pack` builds macOS
  with `skia_use_fonthost_mac=true` and doesn't compile FreeType into that binary at all.

`ImageDiffer.DEFAULT_TOLERANCE_PERCENT = 0.05` (with `DEFAULT_CHANNEL_TOLERANCE = 2`) covers the
residual. Calibration: adding one character to a button label moves 1.32% of the pixels, so there are
~25× of headroom between "rendering noise" and "smallest realistic regression".

**Still not portable:** glyphs missing from the bundled font. Fallback goes to a host font — real CJK
on Windows, tofu in a bare container, Segoe UI Symbol for a ✕ — and no rendering setting fixes it.
Three ways to take the host out of that chain were implemented and measured; all three failed, so
don't spend the day re-deriving them (`ViddikGlyphCoverage.kt`'s header keeps the same list):

1. *Register extra fonts with Compose's fallback provider.* skparagraph resolves a missing glyph
   through the **default** font manager, and skiko's `FontMgrWithFallback` — the one Compose installs
   — asks the host first and registered fonts "as a last resort" (its own KDoc). Measured: a ✓✕★
   fixture still took its glyphs from Segoe UI Symbol on Windows and DejaVu on Linux, 21% mismatch.
2. *List several families on the text style* (the Flutter `fontFamilyFallback` shape). Doesn't run:
   `ParagraphBuilder.skiko.kt` sets `res.typeface = resolved.typeface`, pinning one typeface for the
   whole run, so per-character family resolution never happens.
3. *Replace the FontCollection's font managers by reflection.* Does remove the host, but a plain
   `TypefaceFontProvider` implements no character matching at all (every uncovered glyph becomes
   tofu), and registering per-weight instances via `Typeface.makeClone()` reintroduces platform
   differences — DirectWrite and FreeType don't produce identical variable-font instances. Measured:
   more failures than doing nothing.

What shipped instead is `ViddikGlyphCoverage.missingGlyphs(text, fontBytes)` — a cmap reader that
names the offending codepoints up front (`ViddikGlyphCoverageTest` pins the behavior). Also still not
portable: `Dialog` content, which renders into its own semantics root that the capture-root modifier
doesn't reach.

A minimal Docker image (e.g. `eclipse-temurin:21-jdk`) needs `libgl1`/`libx11-6`/`libxext6`/
`libxrender1` installed or skiko's native lib won't load at all (`UnsatisfiedLinkError` at
`LibraryLoader.kt`) — Skiko links against libGL even for pure raster rendering. That container is how
the numbers above were measured; it is no longer needed to produce or verify goldens.

## Publishing (`buildSrc/viddik.publishing.gradle.kts`)

A precompiled script plugin (`id("viddik.publishing")`, applied by all 3 modules) generalizes the
publishing setup instead of each module hand-rolling its own `publishing {}` block: applies
`maven-publish`, sets `version` from the `viddik.version` Gradle property (`gradle.properties`, single
source of truth — modules no longer hardcode their own `version = "..."`), adds `withSourcesJar()` for
plain-`kotlin("jvm")` modules (KMP targets already publish their own sources jars per-target), and
registers a `wip` repository at `https://reposilite.kotlin.website/snapshots`. Credentials
(`REPOSILITE_USER`/`REPOSILITE_SECRET`) and the CI-only version override (`VERSION`) are all read via
`findProperty(...)`, not `System.getenv(...)` directly — but CI (`.github/workflows/publish-viddik-
snapshot.yaml`) still supplies them as plain environment variables prefixed `ORG_GRADLE_PROJECT_`
(`ORG_GRADLE_PROJECT_REPOSILITE_USER` etc.), which Gradle auto-maps to project properties, so
`findProperty("REPOSILITE_USER")` sees them without any extra wiring. The `VERSION` property, when
present, overrides the version of every registered `MavenPublication` at publish time only (base
version + build number, e.g. `0.1.1.482` — computed by the workflow's "Determine version" step) —
`publishToMavenLocal` never sees it and always publishes plain `0.1.1`, so local dev doesn't pollute
`~/.m2` with one version per rebuild. `./gradlew publish` / `publishAllPublicationsToWipRepository`
(root-level invocation runs it in every subproject that has it) pushes to `wip`; `publishToMavenLocal`
is unaffected by any of this and always available with no credentials.

There is no plugin-repository dependency for `viddik.publishing` itself — `buildSrc` precompiled script
plugins resolve purely from being present in `buildSrc/src/main/kotlin/`, no `pluginManagement`
repository entry needed for `viddik.*` plugin IDs (this project intentionally has no private/
authenticated plugin repository at all anymore — previously depended on a private `wip.publishing`
plugin from the monorepo's own Reposilite instance; removed when this project was extracted so it has
zero non-public dependencies to build). Dokka (`org.jetbrains.dokka`, aggregated at root via
`dependencies { dokka(projects.viddikAnnotations); ... }`) and ktlint (`org.jlleitschuh.gradle.ktlint`)
are applied per-module directly via version-catalog aliases, not through this convention plugin.
`.editorconfig` sets `ktlint_function_naming_ignore_when_annotated_with = Composable` project-wide —
without it, ktlint flags every PascalCase `@Composable` function name as a style violation.

## Consumers

Downstream consumers depend on `ru.workinprogress:viddik-*` either through `mavenLocal()` (a fresh
clone needs `viddik`'s `publishToMavenLocal` run manually first — there's no CI wiring to
auto-publish `viddik` before building a consumer) or, once a version has actually been pushed to
`wip` via the publish workflow, the public `https://reposilite.kotlin.website/snapshots` repository
directly (no credentials needed to read) — check a given consumer's own `settings.gradle.kts` to see
which it's currently wired for; both are legitimate depending on whether local iteration or a real
published version is being tested against. Exact coordinates depend on whether the consumer module is
itself KMP-aware:

- **A KMP consumer module** (e.g. a `jvm("desktop")` target) depends on the base coordinates without a
  target suffix (`ru.workinprogress:viddik-annotations:0.1.1`, `ru.workinprogress:viddik-testing-core:0.1.1`)
  since a KMP-aware consumer resolves the right variant through Gradle module metadata regardless of the
  producer's/consumer's local target *name* matching. KSP processor dependency example:
  `add("kspDesktopTest", "ru.workinprogress:viddik-processor:0.1.1")`.
- **A plain `kotlin("jvm")` consumer module**, NOT KMP-aware, needs the explicit platform-suffixed
  artifacts instead: `ru.workinprogress:viddik-annotations-desktop:0.1.1` (the `jvm("desktop")` target
  publication) and `ru.workinprogress:viddik-testing-core-jvm:0.1.1` (the unnamed `jvm()` target
  publication) plus `kspTest("ru.workinprogress:viddik-processor:0.1.1")`.
