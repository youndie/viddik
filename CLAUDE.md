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
  - `ViddikEngine` — the record/verify harness (Paparazzi-equivalent). `VIDDIK_RECORD_MODE` env var
    (not a Gradle property — set it in the shell/CI step) toggles write-golden vs compare-and-fail.
    `viddik.snapshotsDir`/`viddik.reportsDir` **system properties** (not env vars) override the
    defaults (`src/desktopTest/snapshots`, `build/reports/screenshots`) — needed because different
    consumer modules name their JVM target differently (`jvm()` vs `jvm("desktop")`), and
    `GeneratedViddikTests` calls `dynamicTests(...)` with no parameters, so there's no way to thread an
    override through generated code; every consumer's `build.gradle.kts` sets these explicitly via
    `systemProperty(...)` on its `Test` task — see `viddik-testing-core/build.gradle.kts` itself for
    the pattern (`src/jvmTest/snapshots`, since this module's own target is unnamed `jvm()`).
  - `DemoViddik.kt` (jvmTest) — the project's own self-test AND a living usage example: static fixture,
    dark-variant fixture, a `ViddikShowroom` self-screenshot, and a `@PreviewParameter` fixture using a
    bare `String` (which can't implement `ViddikPreviewLabel`, demonstrating the `toString()` fallback
    naming path). The `Showroom - Showroom - list` golden was re-recorded on this machine after the
    extraction carried over a stale one from the original banqfunkie copy (font-rendering mismatch,
    7890/120000 px) — if this starts failing again on a different machine/CI runner, it's almost
    certainly the same font-rendering sensitivity, not a real regression; re-record with
    `VIDDIK_RECORD_MODE=true` and visually check the PNG before trusting it.

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

Downstream consumers depend on `ru.workinprogress:viddik-*` through `mavenLocal()` (added to their own
`dependencyResolutionManagement` repositories) — there's no CI wiring to auto-publish `viddik` before
building any consumer, so a fresh clone of a consumer repo needs `viddik`'s `publishToMavenLocal` run
manually first. Exact coordinates depend on whether the consumer module is itself KMP-aware:

- **A KMP consumer module** (e.g. a `jvm("desktop")` target) depends on the base coordinates without a
  target suffix (`ru.workinprogress:viddik-annotations:0.0.1`, `ru.workinprogress:viddik-testing-core:0.0.1`)
  since a KMP-aware consumer resolves the right variant through Gradle module metadata regardless of the
  producer's/consumer's local target *name* matching. KSP processor dependency example:
  `add("kspDesktopTest", "ru.workinprogress:viddik-processor:0.0.1")`.
- **A plain `kotlin("jvm")` consumer module**, NOT KMP-aware, needs the explicit platform-suffixed
  artifacts instead: `ru.workinprogress:viddik-annotations-desktop:0.0.1` (the `jvm("desktop")` target
  publication) and `ru.workinprogress:viddik-testing-core-jvm:0.0.1` (the unnamed `jvm()` target
  publication) plus `kspTest("ru.workinprogress:viddik-processor:0.0.1")`.
