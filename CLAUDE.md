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

Downstream consumers resolve `ru.workinprogress:viddik-*` via `mavenLocal()` — after any change here,
`publishToMavenLocal` before rebuilding them. There is no
version bump discipline yet (everything sits at `0.0.1`); Gradle/consumers cache by exact version+build
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
    dialog directly or indirectly should always pass an explicit `height`.
  - `ImageDiffer` — pixel-for-pixel diff, paints every mismatched (or out-of-bounds) pixel solid red in
    the output `DiffResult.diffImage` so the artifact is a readable visual diff, not just a boolean.
    `DiffResult.matches(tolerancePercent: Double = DEFAULT_TOLERANCE_PERCENT)` — NOT a `val`, a
    function; `mismatchedPixels == 0` used to be the bar but that's unreachable cross-platform (see
    the font/rasterization bullet below), so `DEFAULT_TOLERANCE_PERCENT = 0.5` is the real default.
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
    - `ViddikPlatformTextStyle` — forces `FontRasterizationSettings(smoothing = None, hinting = None,
      subpixelPositioning = false)`. This exact combination was reached by elimination, not
      guessed — see "Cross-platform golden portability" below for the numbers; `AntiAlias` +
      `Slight` hinting (i.e. forcing *Linux's own PlatformDefault* on both OSes) was tried and
      measured *worse* than `None`/`None`, so don't reintroduce it without re-measuring.
    - `ViddikConsistentRendering.isEnabled` — reads the `viddik.consistentRendering` system property,
      `false` unless a consumer's `Test` task sets it. This is the on/off switch for the whole
      font+rasterization fix; see below for why it defaults off.
    - `viddikTypography(base: Typography = Typography())` — rebuilds all 15 Material3 text styles
      from `base` with `ViddikFontFamily` + `ViddikPlatformTextStyle` applied. Lowercase name
      deliberately (ktlint's `function-naming` rule flags PascalCase for anything not annotated
      `@Composable`, and this isn't one — the `.editorconfig` exception only covers `@Composable`).
    - There is no way to force any of this onto a fixture from outside — `CaptureEngine` renders
      whatever `content()` the fixture passes in, and if that fixture calls its own
      `MaterialTheme(typography = ...)`, that always wins over anything provided further out. Every
      consumer decides for itself whether to call `viddikTypography()`, typically gated on
      `ViddikConsistentRendering.isEnabled`.
  - `DemoViddik.kt` (jvmTest) — the project's own self-test AND a living usage example: static fixture,
    dark-variant fixture, a `ViddikShowroom` self-screenshot, and a `@PreviewParameter` fixture using a
    bare `String` (which can't implement `ViddikPreviewLabel`, demonstrating the `toString()` fallback
    naming path). `demoTypography` picks `viddikTypography()` when `ViddikConsistentRendering.isEnabled`,
    else a plain `Typography()` — this module's own `build.gradle.kts` sets
    `systemProperty("viddik.consistentRendering", "true")` on the `Test` task specifically so this
    repo's own CI passes reliably across macOS dev / Linux CI; a consumer copying this pattern makes
    that call for itself instead of inheriting it from here. Even with the flag on, macOS vs Linux
    still isn't byte-identical (different underlying glyph rasterizer, CoreText vs FreeType) —
    measured 0.08%–0.27% mismatch empirically (macOS-recorded goldens verified against a Docker/Linux
    run), comfortably under the 0.5% default tolerance. If this ever fails outside that range, it's a
    real regression, not rendering noise; re-record with `VIDDIK_RECORD_MODE=true` and visually check
    the PNG (and the `_DIFF.png` in `build/reports/screenshots/`) before trusting either outcome.

## Cross-platform golden portability

This was a real, fully-worked-through problem (not a hypothetical) — see git history on
`feature/fonts` for the investigation. Two independent, mutually-exclusive fixes exist; don't combine
them, and don't assume the second one is "free":

**Fix 1 — record on CI.** Don't change rendering at all; always record goldens on the exact runner
image that later verifies them (`.github/workflows/record-viddik-snapshots.yaml`, `workflow_dispatch`,
runs the self-test in `VIDDIK_RECORD_MODE=true` on `ubuntu-latest` and uploads `snapshots/*.png` as a
build artifact — download it and commit those files instead of ones recorded on a dev machine). Zero
rendering-quality cost, but a golden recorded anywhere else is categorically invalid.

**Fix 2 — `ViddikConsistentRendering` (`ViddikFonts.kt`).** Opt-in (`viddik.consistentRendering`
system property, off by default) — bundles a font and forces rasterization settings so a dev-machine
golden is *also* valid in CI, at the cost of visibly worse-looking (aliased) text everywhere,
including the live `ViddikShowroom` browser. Order of sub-fixes, each closing a different fraction of
the gap (measured, not guessed):

1. **No bundled font**: ~100% pixel mismatch on nearly every fixture (host OS font substitution is
   total, not subtle) — Skia has no fallback to a bundled font unless you explicitly load one.
2. **+ bundled font, default rasterization**: 0.68%–2.26% mismatch — font identity fixed, but
   `FontRasterizationSettings.PlatformDefault` still differs by OS.
3. **+ forced `AntiAlias` smoothing / `Slight` hinting** (i.e. explicitly requesting *Linux's own*
   `PlatformDefault` values on both OSes): 0.66%–2.24% — **no improvement over step 2**. Naming the
   same setting on both platforms doesn't make FreeType (Linux) and CoreText (macOS) produce the same
   hinting adjustment; each still runs its own platform-specific algorithm for "Slight".
4. **+ forced `None` smoothing / `None` hinting** (what `ViddikPlatformTextStyle` actually uses):
   0.08%–0.27% — the remaining gap is the OS's underlying glyph rasterizer library itself, not
   anything exposed through Compose's public API. This is the best combination found; don't
   re-attempt step 3's approach without re-measuring — it was tried on this exact codebase and
   regressed.

`ImageDiffer.DEFAULT_TOLERANCE_PERCENT = 0.5` absorbs step 4's residual regardless of which Fix is in
use — a real UI regression moves far more than a fraction of a percent of pixels.

A minimal Docker image (e.g. `eclipse-temurin:21-jdk`) is NOT representative of `ubuntu-latest` for
this kind of testing — it's missing `libgl1`/`libx11-6`/`libegl1` (Skiko's native lib fails to load at
all without them: `UnsatisfiedLinkError` at `LibraryLoader.kt`) and ships only DejaVu fonts, vs
whatever `ubuntu-latest` actually has preinstalled — useful for reproducing/debugging the font problem
locally (which is how every number above was measured), but its own goldens would NOT be valid
stand-ins for what `ubuntu-latest` produces.

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
version + build number, e.g. `0.0.1.482` — computed by the workflow's "Determine version" step) —
`publishToMavenLocal` never sees it and always publishes plain `0.0.1`, so local dev doesn't pollute
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
  target suffix (`ru.workinprogress:viddik-annotations:0.0.1`, `ru.workinprogress:viddik-testing-core:0.0.1`)
  since a KMP-aware consumer resolves the right variant through Gradle module metadata regardless of the
  producer's/consumer's local target *name* matching. KSP processor dependency example:
  `add("kspDesktopTest", "ru.workinprogress:viddik-processor:0.0.1")`.
- **A plain `kotlin("jvm")` consumer module**, NOT KMP-aware, needs the explicit platform-suffixed
  artifacts instead: `ru.workinprogress:viddik-annotations-desktop:0.0.1` (the `jvm("desktop")` target
  publication) and `ru.workinprogress:viddik-testing-core-jvm:0.0.1` (the unnamed `jvm()` target
  publication) plus `kspTest("ru.workinprogress:viddik-processor:0.0.1")`.
