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
./gradlew build                                  # Build all 4 modules, and the whole gate: ktlint, the
                                                   # goldens, and both unit-test suites
./gradlew :viddik-testing-core:jvmTest            # Self-test suite (DemoViddik.kt) — NOT `test`, the
                                                   # module's jvm() target is unnamed so Gradle names the
                                                   # task jvmTest, not test (that only applies to plain
                                                   # kotlin("jvm") consumer modules like dev:uikit-sandbox)
./gradlew :viddik-processor:test                  # Fixture-metadata resolution (FixtureMetadataTest) —
                                                   # plain kotlin("jvm"), so `test`, not `jvmTest`
./gradlew :viddik-gradle-plugin:test              # ViddikLayoutTest, the naming fork
./gradlew ktlintCheck                             # Style check (all 4 modules; jvmTest sourceSet in
                                                   # viddik-testing-core is deliberately excluded, see
                                                   # its build.gradle.kts — KSP-generated code lives there)
./gradlew ktlintFormat                            # Auto-fix style violations
./gradlew dokkaGenerate                           # Aggregated HTML docs at build/dokka/html/index.html
./gradlew publishToMavenLocal                     # Publish all 4 modules for local consumers to pick up
./gradlew :viddik-processor:publishToMavenLocal   # Single module, e.g. after a processor-only change
VIDDIK_RECORD_MODE=true ./gradlew :viddik-testing-core:jvmTest --tests "*runAllScreenshots*"
                                                   # Re-record the self-test golden PNGs (src/jvmTest/snapshots/).
                                                   # This rewrites EVERY golden, not the ones you changed —
                                                   # always `git status` afterwards and revert the rest.
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
`gradle.properties` (`viddik.version`, currently `0.3.0`); Gradle/consumers cache by exact version+build
hash so a republish under the same version is picked up by build cache invalidation, not by version
diffing — if a consumer's build looks stale after a republish, `--no-build-cache` or bump the version.

## Module Structure

Dependency order: `viddik-annotations` (no deps on the others) → `viddik-testing-core` (depends on
`viddik-annotations`, KSP-processed by `viddik-processor` in its own `jvmTest`) / any consumer module
(depends on both `viddik-annotations` + `viddik-testing-core`, KSP-processed by `viddik-processor`).
`viddik-gradle-plugin` depends on none of them at compile time — it only knows their coordinates.

- **viddik-annotations** — Kotlin Multiplatform (`android()` + `jvm("desktop")`), Compose Multiplatform
  UI only (LazyColumn/Text/clickable — no platform APIs), so adding targets here is unconstrained.
  - `ViddikScreenshot` (`annotations/ViddikScreenshot.kt`) — the marker annotation (`name`, `group`,
    `width`, `height`, `darkVariant`). Every size parameter defaults to `UNSPECIFIED`
    (`Int.MIN_VALUE`, in `ViddikComponent.kt`) rather than to its old literal, because KSP substitutes
    defaults before the processor sees them: without a value nobody would write by hand, "omitted" and
    "written out as 400" are indistinguishable, and falling back to `@Preview` would override a
    deliberate 400. Resolution order per field is argument here → `@Preview` field → viddik default
    (400 / `AUTO_HEIGHT` / `"Default"` / the function's own name).
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
  - `FixtureMetadata.kt` — how a fixture's name, size and theme are decided, deliberately free of KSP
    so it can be unit-tested without standing up a compilation (`FixtureMetadataTest`, 12 tests; this
    module had no test source set before). `resolveFixture()` takes `ScreenshotArgs` + a nullable
    `PreviewArgs` and returns null after reporting through an `onError` callback rather than picking a
    winner when the two contradict each other.
  - `ViddikSymbolProcessor` — scans `@ViddikScreenshot`-annotated functions, one-shot (`invoked` guard,
    since KSP calls `process()` repeatedly across rounds). Also reads
    `androidx.compose.ui.tooling.preview.Preview` off the same function when there is one —
    `name`/`group`/`widthDp`/`heightDp`/`uiMode`. That exact FQN is the point: Compose Multiplatform
    1.12 ships it in common and it is the same one Android uses, so a single annotation is read by the
    IDE preview pane, by Android's screenshot tooling and by viddik. The legacy
    `androidx.compose.desktop.ui.tooling.preview.Preview` is deliberately not read — it cannot serve
    Android, which is the whole reason for reading `@Preview` at all. Bare `@Preview` is **not**
    scanned: `@ViddikScreenshot` stays the opt-in, because capturing every preview in a codebase would
    turn IDE-only previews into goldens, most of which cannot render headless (the official Android
    tool reached the same conclusion with its own separate `@PreviewTest` marker).
    - `uiMode` and `darkVariant` are different questions and must not be conflated: `uiMode =
      UI_MODE_NIGHT_YES` says *this* fixture is dark (one entry, wrapped in the dark composition local,
      no `" Dark"` suffix), while `darkVariant = true` asks for a *second* entry beside a light one.
      Both at once is a hard error — the alternative is a dark golden with an identical dark golden
      next to it. `uiMode` is a bit field, so the check is `(uiMode and 0x30) == 0x20`, not equality;
      the constants are mirrored from `AndroidUiModes` rather than depended on, since the processor is
      a plain JVM module with no Compose on its classpath.
    - **Multipreview expansion.** `@Preview` is repeatable and a multipreview is just an annotation
      class carrying several, so `collectPreviews()` recurses into annotations' *declarations* looking
      for more. Two shapes have to be unwrapped: `@Preview` written directly, and
      `Preview.Container` — a repeatable annotation read off an already-compiled declaration (which is
      what `@PreviewLightDark` is) arrives wrapped. `MAX_MULTIPREVIEW_DEPTH` is a backstop against an
      annotation cycle, which the compiler permits; `SKIPPED_ANNOTATIONS` keeps the walk from
      resolving half of stdlib's annotation graph per fixture. With several previews the
      `@ViddikScreenshot` name becomes a stem (`"$stem - $discriminator"`) — it cannot *be* the name,
      it would be the same one for all of them — and the discriminator is the preview's own name, or
      its index when it has none. Every built-in multipreview names its previews
      (`"Light"`/`"Dark"`, `"85%"`…`"200%"`, `"Phone"`/`"Tablet"`), so the index path is only for
      hand-rolled ones.
    - `darkVariant` alongside several previews is an error: it doubles every fixture the function
      produces, so one `@PreviewFontScale` would quietly become fourteen goldens.
    - **`fontScale`** is honoured by `CaptureEngine`, which overrides `LocalDensity`'s font scale and
      *only* its font scale. Scaling the density too would change what a dp is worth and resize every
      golden `@PreviewFontScale` produces — `ViddikDensityTest` pins both halves of that. Without this,
      `@PreviewFontScale` would record seven identical images.
    - **`device`** is read for its size only, and only in the `spec:` form (`parseDeviceSpec`).
      `dpi`/`orientation`/`isRound` are a density or a device shape a plain canvas has no equivalent
      for, and a named device (`id:pixel_5`) has its dimensions in Android's catalogue, not in the
      annotation. Both are `logger.warn` rather than errors on purpose: a fixture carrying `device` for
      the IDE's sake is still a good fixture, it just doesn't get that device. The size sits *below*
      an explicit `widthDp`/`heightDp` in precedence and above viddik's default.
    - **`@PreviewWrapper`** (`collectWrappers()`, same recursive walk) wraps the generated content
      lambda in `Wrapper().Wrap { fixture() }`. This is the answer to what this file used to call
      unsolvable — "Typography can't be forced onto a fixture from outside" is still true of the
      *composition*, but the wrapper is applied at codegen time, outside it. A project's own
      `@AppPreviews` annotation can therefore carry the bundled-font theme and the light/dark pair at
      once, and a fixture that forgets its harness stops being a way to record a golden drawn in the
      host's system font. More than one distinct wrapper resolving onto one function is an error. Rules enforced: must also be `@Composable`;
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
    dialog directly or indirectly should always pass an explicit `height`. The image itself does not
    come from `captureToImage()`: the engine renders the scene (`SkikoComposeUiTest.scene`, public)
    into a `Surface` of its own whose canvas already carries the 1e-9 perspective term, which makes
    Skia rasterize glyph outlines with its own path rasterizer instead of the host font backend. That
    is what makes goldens portable across OSes, and doing it at the scene rather than at a node is
    what extends it to `Dialog`/`Popup`, which Compose renders into roots of their own — a dialog is
    then cropped out of the scene image by its semantics bounds.
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
    - `viddik.filter` selects a subset of fixtures, which is the only way to reach one component:
      `dynamicTests` is the sole entry point generated code calls, and Gradle's `--tests` can't see
      dynamic tests. Case-insensitive **substring** of `"$group - $name"` with `*`/`?` as wildcards
      (unanchored via `containsMatchIn`, so a bare `Primary` works without naming the group; regex
      metacharacters in the pattern are escaped and match literally). A filter matching nothing
      **throws**, listing the components that do exist — an over-narrow filter would otherwise be
      indistinguishable from a passing run. Because the filter lives in `dynamicTests` rather than in
      the processor, it reaches consumers that were built against an older `viddik-processor`; the
      surface `viddik-gradle-plugin` puts on it is `--component`. Covered by `ViddikFilterTest`
      (jvmTest), which counts the returned `DynamicTest`s without ever executing them — a
      `DynamicTest` doesn't capture anything until it runs.
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
    **Recording to add one fixture rewrites all of them**, and a golden that differs only within
    tolerance still gets a new file: adding the two `@Preview`-driven fixtures rewrote
    `Demo_Simple_Text*.png` by a single pixel at channel deviation 2 — noise the tolerance had been
    absorbing on verify since the CMP 1.12 render-path change. Check `git status` after every record
    and revert the goldens the change wasn't about; the committed ones were verified on three OSes,
    and a local re-record quietly downgrades that to one.
  - `ViddikDensityTest` (jvmTest) — pins the harness at density 1, i.e. `1.dp == 1px`, and pins that a
    font scale moves text without moving the canvas. The equality was
    accidental until `@Preview` support (`CaptureEngine` passed a pixel count into
    `Modifier.width(...dp)` and nothing reconciled the two); now `widthDp` is read straight into a
    capture width, so a changed default would move every golden at once. Asserted rather than forced —
    overriding `LocalDensity` would move the goldens now, on a guess.

- **viddik-gradle-plugin** — plain `kotlin("jvm")` + `java-gradle-plugin`, plugin id
  `ru.workinprogress.viddik`. Exists because the wiring it replaces was copied by hand into every
  consumer that predates it, each spelling the same names slightly differently.
  - `ViddikLayout` — the naming fork, and the only part with unit tests: `jvm("desktop")` →
    `kspDesktopTest`/`desktopTest`/`src/desktopTest/snapshots`, unnamed `jvm()` → `kspJvmTest`/
    `jvmTest`, plain `kotlin("jvm")` → `kspTest`/`test` plus the platform-suffixed artifacts
    (`viddik-annotations-desktop`, `viddik-testing-core-jvm`) a non-KMP-aware consumer needs.
  - **Dependencies and generated-source dirs are wired eagerly, NOT in `afterEvaluate`** — this is
    the one non-obvious thing in the plugin. KSP decides whether its task has anything to do by
    checking whether its configuration is empty, and it does that from *its own* `afterEvaluate`,
    which runs first whenever KSP is applied before this plugin. A processor added in our
    `afterEvaluate` is never seen, and the symptom isn't an error: `kspTestKotlinDesktop SKIPPED`,
    then `viddikRecord` failing with "No tests found for given includes". The wiring therefore
    happens from `targets.withType(KotlinJvmTarget).all { }` (KMP) / `plugins.withId` (JVM), with
    `dependencies.addAllLater(...)` on a `configurations.matching { }` so the extension's values are
    still read late and the plugins can be applied in either order. Same reason `viddik.generateTests`
    goes in as a `CommandLineArgumentProvider` rather than `ksp { arg(k, v) }`.
  - Task classpaths are wired in `afterEvaluate` (harmless there), but the three tasks are
    *registered* in `apply()` so a consumer can still write `tasks.named<Test>("viddikVerify") { }`
    in its own script body.
  - Task names are `viddikVerify`/`viddikRecord`/`viddikShowroom`, not the `screenshotTest` the
    hand-wired consumers use — namespaced, and `screenshotTest` also collides with AGP's own
    screenshot-test source set concept in a KMP+Android module. Adopting the plugin in a consumer
    means updating its README/CI to the new names.
  - `viddikRecord` sets `VIDDIK_RECORD_MODE=true` and `outputs.upToDateWhen { false }`, which is the
    `--rerun` that used to be part of the incantation.
  - `ViddikScreenshotTask` (a `Test` subclass) exists only to carry `--component`, which sets the
    engine's `viddik.filter`. Gradle's own `--tests` can't select a fixture: they're JUnit5 *dynamic*
    tests under one `GeneratedViddikTests` class and `--tests` matches classes and methods only
    (measured — `--tests "*Primary*"` fails with "No tests found", `--tests "*GeneratedViddikTests*"`
    runs all of them). The value travels as a `CommandLineArgumentProvider` on
    `jvmArgumentProviders`, not `systemProperty(...)`: a command-line option isn't known until after
    configuration. It's `@Internal` on the task and `@Input` on the provider, which is what makes
    changing `--component` re-run an otherwise up-to-date verification. The type is
    `@DisableCachingByDefault` because the same type serves recording, whose real output — goldens in
    the source tree — is undeclared.
  - Both tasks set `testLogging { events(FAILED); exceptionFormat = FULL; showStackTraces = false }`.
    Gradle's default prints `IllegalStateException at GeneratedViddikTests.kt:10` and nothing else,
    so every failure — a pixel mismatch or a mistyped `--component` — meant opening the HTML report
    to read a message that was already a full sentence.
  - Versions travel as a **resource** (`generateViddikVersionResource` → `viddik-plugin.properties`),
    not generated Kotlin: nothing for ktlint or Dokka to trip over. The viddik version the plugin
    hands consumers defaults to the plugin's own, so processor and engine can't drift apart.
  - The CI `VERSION` override is applied to `project.version` directly in this module's
    `build.gradle.kts`, not left to `viddik.publishing`'s `afterEvaluate` — `java-gradle-plugin`
    captures `project.version` when it creates the plugin-marker publication, so patching the
    publications afterwards would leave the marker pointing at a version that was never published.
  - KGP and KSP are `compileOnly`: the consumer's own versions must win, KSP's especially, since it
    is pinned to their exact Kotlin compiler version. The plugin doesn't apply KSP, it checks for it
    and fails with the configuration name it would have used.
  - Current KGP rejects a second JVM target in one module outright ("`jvm()` Kotlin Target Already
    Declared"), so `viddik { jvmTarget = ... }` and the "more than one JVM target" error are guards,
    not live paths.
  - `ViddikShowroomLauncher` (in `viddik-testing-core`, jvmMain) is the `viddikShowroom` task's main
    class — it loads `GeneratedViddikRegistry` **reflectively** because that class is generated into
    the *consumer's* test source set and isn't visible here at compile time.

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
names the offending codepoints up front (`ViddikGlyphCoverageTest` pins the behavior).

`Dialog` content used to be the other gap, for a mechanical reason: the perspective term sat on a
modifier around the fixture, and Compose renders a dialog into a root of its own, which that modifier
never reached. Measured on a downstream consumer's suite of 422 fixtures (recorded on Windows,
verified on Linux): dialogs and bottom sheets were the only failures left, 36 of them, at 0.5–2.5%.
Moving the term to the scene's canvas — the engine renders the scene itself instead of calling
`captureToImage()` on a node — took that to 0. A consumer's own remaining diff, if any, is then either
a fixture drawing a character its font lacks, or a component building `TextStyle(...)` from scratch
(no `fontFamily`, so `FontFamily.Default` resolves to a host font).

A minimal Docker image (e.g. `eclipse-temurin:21-jdk`) needs `libgl1`/`libx11-6`/`libxext6`/
`libxrender1` installed or skiko's native lib won't load at all (`UnsatisfiedLinkError` at
`LibraryLoader.kt`) — Skiko links against libGL even for pure raster rendering. That container is how
the numbers above were measured; it is no longer needed to produce or verify goldens.

## Compose Multiplatform version coupling

`CaptureEngine` reaches past the public test API into `ComposeScene` and `org.jetbrains.skia`, so this
project is pinned to one CMP *line*, not to a range. Neither end of that coupling is declared anywhere
a resolver can see it: a consumer on a different line gets a `NoSuchMethodError` /
`IllegalAccessError` on the first captured frame, at runtime, with everything having compiled clean.
That is why README carries a compatibility table and why a CMP line bump is a minor version here.

The 1.11 → 1.12 port (viddik 0.2.0) was two independent breakages, both in `CaptureEngine.kt`:

- **`ComposeScene.render(canvas, nanoTime)` is gone**, split into `measureAndLayout()` + `draw(canvas)`.
  The frame time it used to take isn't missed: `waitForIdle()` has already settled the harness before
  the capture runs, so there is no animation left to advance.
- **`org.jetbrains.skia.Matrix44` became a value class** in skiko 0.150.1, and its array constructor
  is no longer public — `Matrix44(*floatArrayOf(...))` stopped compiling. Spelling the sixteen floats
  as positional arguments works against both the old vararg constructor and the new one.

Do **not** pin skiko to reconcile a mismatch. It isn't declared here at all — it arrives transitively
with `compose.ui` and its version is chosen by CMP. Holding it back gets past the `Matrix44` error and
straight into the `ComposeScene` one, because the coupling is to the Compose API, not only to skiko.

CMP 1.12 also raised the Android floor: `viddik-annotations` compiles against `compileSdk = 37`, and
so must any Android consumer of it. Its own goldens were unaffected — the committed PNGs, recorded
against 1.11, verify byte-identical against 1.12 (checked against a deliberately corrupted golden to
confirm the comparison was live, not vacuous).

## Publishing (`buildSrc/viddik.publishing.gradle.kts`)

A precompiled script plugin (`id("viddik.publishing")`, applied by all 4 modules) generalizes the
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
version + build number, e.g. `0.3.0.482` — computed by the workflow's "Determine version" step) —
`publishToMavenLocal` never sees it and always publishes plain `0.3.0`, so local dev doesn't pollute
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
published version is being tested against.

Since `viddik-gradle-plugin` exists, a consumer normally applies `id("ru.workinprogress.viddik")` and
declares none of this by hand. Consumers that predate the plugin still carry the hand-rolled
version; migrating one means deleting its `screenshotTest` task, its `viddik-*` dependencies and its
`kotlin.srcDir("build/generated/ksp/...")` line, then renaming `screenshotTest` → `viddikVerify` in
its README and CI. The coordinates below are what the plugin picks for itself, and what you still
need with `viddik { addDependencies = false }`:

- **A KMP consumer module** (e.g. a `jvm("desktop")` target) depends on the base coordinates without a
  target suffix (`ru.workinprogress:viddik-annotations:0.3.0`, `ru.workinprogress:viddik-testing-core:0.3.0`)
  since a KMP-aware consumer resolves the right variant through Gradle module metadata regardless of the
  producer's/consumer's local target *name* matching. KSP processor dependency example:
  `add("kspDesktopTest", "ru.workinprogress:viddik-processor:0.3.0")`.
- **A plain `kotlin("jvm")` consumer module**, NOT KMP-aware, needs the explicit platform-suffixed
  artifacts instead: `ru.workinprogress:viddik-annotations-desktop:0.3.0` (the `jvm("desktop")` target
  publication) and `ru.workinprogress:viddik-testing-core-jvm:0.3.0` (the unnamed `jvm()` target
  publication) plus `kspTest("ru.workinprogress:viddik-processor:0.3.0")`.
