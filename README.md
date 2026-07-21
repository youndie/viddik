# viddik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.0-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![viddik-annotations](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-annotations?name=annotations&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-annotations)
[![viddik-processor](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-processor?name=processor&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-processor)
[![viddik-testing-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-testing-core?name=testing-core&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-testing-core)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue?logoColor=white)](https://youndie.github.io/viddik/)

**screenshot-testing toolkit for Compose Multiplatform** — a showkase + paparazzi analog that renders
through a real **Compose Desktop/Skiko** JVM window instead of Android/LayoutLib

> 🖼️ one annotation → a golden-file test + a live entry in an interactive component browser

No emulator, no AVD, no LayoutLib — `@ViddikScreenshot`-annotated composables are collected by a KSP
processor into a component registry, then either captured to PNG and diffed (`ViddikEngine`, record/
verify) or shown live in a portable browser (`ViddikShowroom`), all on a plain JVM.

### 📦 Installation

Add the Reposilite snapshot repository and *viddik* dependencies:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<KSP_VERSION>" // must match your Kotlin compiler version
}

repositories {
    mavenCentral()
    maven {
        name = "wip"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    // KMP consumer (e.g. your own jvm("desktop") target) — base coordinates, no target suffix:
    testImplementation("ru.workinprogress:viddik-annotations:<VERSION>")
    testImplementation("ru.workinprogress:viddik-testing-core:<VERSION>")
    add("kspDesktopTest", "ru.workinprogress:viddik-processor:<VERSION>")

    // Plain kotlin("jvm") consumer, NOT KMP-aware — needs the explicit per-target artifacts instead:
    // testImplementation("ru.workinprogress:viddik-annotations-desktop:<VERSION>")
    // testImplementation("ru.workinprogress:viddik-testing-core-jvm:<VERSION>")
    // kspTest("ru.workinprogress:viddik-processor:<VERSION>")
}
```

`viddik-annotations` is the lightweight API surface (the `@ViddikScreenshot` marker, `ViddikComponent`,
`ViddikShowroom`) — safe to depend on from any Compose Multiplatform target, including `android()`.
`viddik-processor` is the KSP codegen (registry + JUnit5 tests). `viddik-testing-core` is the JVM-only
capture/diff/record engine (JUnit5 + Compose Desktop) — only ever needed on a `test`/`jvmTest`/
`desktopTest` classpath, never `main`.

### ✍️ Writing a fixture

A `@ViddikScreenshot` function is just a `@Composable` with only default-valued parameters:

```kotlin
@ViddikScreenshot(name = "AppButton - Primary", group = "Buttons", darkVariant = true)
@Composable
fun AppButtonPrimaryPreview() {
    MaterialTheme {
        Button(onClick = {}) { Text("Continue") }
    }
}
```

This shows up two ways, from the exact same fixture — no duplication between "the test" and "the
thing a developer clicks through":

```bash
# Record goldens (writes src/desktopTest/snapshots/*.png by default — override via the
# viddik.snapshotsDir system property if your module names its test source set differently,
# e.g. src/jvmTest/snapshots/ or src/test/snapshots/. Verify visually — record mode doesn't validate.)
VIDDIK_RECORD_MODE=true ./gradlew :yourModule:test --tests "*AppButton*"

# Verify (compares against the recorded golden, fails with a saved _DIFF.png on mismatch)
./gradlew :yourModule:test --tests "*AppButton*"
```

```kotlin
// Live in a window — same registry, no capture, just an interactive browser
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Component Browser") {
        MaterialTheme {
            ViddikShowroom(GeneratedViddikRegistry.components)
        }
    }
}
```

`darkVariant = true` generates a *second* registry entry automatically (`"... Dark"`), wrapped in
`CompositionLocalProvider(LocalViddikDarkTheme provides true)` — your fixture reads
`LocalViddikDarkTheme.current` itself to pick a color scheme, since there's no real "system dark mode"
on a JVM test harness:

```kotlin
@ViddikScreenshot(name = "Card", group = "Widgets", darkVariant = true)
@Composable
fun CardPreview() {
    val dark = LocalViddikDarkTheme.current
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Card { Text("Hello") }
    }
}
```

### 📐 Sizing

Width defaults to 400px; height defaults to **auto** — the engine renders into a tall canvas, measures
the actual composed content height, and crops to it. No more hand-picking `height = 680` per fixture:

```kotlin
@ViddikScreenshot(name = "Chip", group = "Widgets") // height auto-fits (width 400px by default)
```

Pass `height` explicitly only for content that has no natural height of its own — `fillMaxSize()`/
`weight()` layouts, or anything that opens a `Dialog`/`Popup` (auto-height isn't reliable for dialog
content):

```kotlin
@ViddikScreenshot(name = "FullScreenBanner", group = "Screens", height = 800)
```

### 🎛️ Parameterized fixtures (`@PreviewParameter`)

Exactly one parameter annotated `@PreviewParameter` is allowed as the sole exception to "only default
parameters" — the same convention as Compose tooling's own `@Preview`:

```kotlin
@ViddikScreenshot(name = "Checkbox", group = "Widgets", darkVariant = true)
@Composable
fun CheckboxPreview(
    @PreviewParameter(CheckboxStateProvider::class) state: CheckboxPreviewState,
) {
    MaterialTheme {
        Checkbox(checked = state.checked, onCheckedChange = {}, enabled = state.enabled)
    }
}
```

One annotation → N registry entries, one per provider value, each with its own golden file. For a
**descriptive** golden filename instead of a bare index (`... #0`, `... #1`), have the parameter type
implement `ViddikPreviewLabel`:

```kotlin
data class CheckboxPreviewState(
    val checked: Boolean,
    val enabled: Boolean,
) : ViddikPreviewLabel {
    override val previewLabel get() = if (enabled) "Enabled" else "Disabled"
}
```

### 🖥️ Cross-platform goldens (fonts, CI, tolerance)

Skia renders text through whatever fonts the host OS happens to have installed by default — a
golden recorded on a macOS dev machine (system UI font) won't match a bare Linux CI runner (DejaVu,
or nothing at all), and this affects *every* fixture that renders text, i.e. nearly all of them. Two
legitimate ways to deal with this — pick one per project, they're not meant to be combined:

**Option A — record on CI, change nothing else.** Don't touch fonts or rendering at all; just make
sure goldens are always recorded on the exact same runner image that later verifies them. Full native
text rendering quality (real anti-aliasing, real system fonts), but a golden recorded on a dev
machine is never valid in CI and vice versa. Run your test task with `VIDDIK_RECORD_MODE=true` as a
manually-triggered workflow (`workflow_dispatch`) on the same runner image your regular CI uses,
upload the resulting `snapshots/*.png` as a build artifact, download it, and commit those files —
not ones recorded locally. See this repo's own `.github/workflows/record-viddik-snapshots.yaml` for
the pattern.

**Option B — turn on `ViddikConsistentRendering`, get portable-but-uglier goldens.** Set the
`viddik.consistentRendering` system property (or a Gradle `systemProperty(...)` on your `Test` task,
the way `viddik-testing-core`'s own build does for its self-test) and build your theme's `Typography`
via `viddikTypography()` instead of the plain Material3 default:

```kotlin
MaterialTheme(
    typography = if (ViddikConsistentRendering.isEnabled) viddikTypography() else Typography(),
) { content() }
```

This bundles a bit-identical Roboto (OFL, variable font) and forces `FontRasterizationSettings`
(`smoothing = None, hinting = None, subpixelPositioning = false`) on every text style instead of the
platform default, which differs by OS (`FontHinting.Normal` on macOS vs `FontHinting.Slight` on
Linux) even with the same font file — this is *not* a guess, it was measured:

| Configuration | Mismatch (macOS golden vs Linux/Docker verify) |
|---|---|
| No bundled font at all | ~100% (wholesale font substitution) |
| Bundled font, platform-default rasterization | 0.68%–2.26% |
| Bundled font, forced `AntiAlias` + `Slight` hinting (i.e. *telling both platforms to use Linux's own default*) | 0.66%–2.24% — **no better**, since "Slight" hinting still runs a platform-specific outline-adjustment algorithm; naming the same setting on both platforms doesn't make FreeType and CoreText agree |
| Bundled font, forced `None` smoothing + `None` hinting | **0.08%–0.27%** — best found |

Even at its best this isn't byte-identical — Skia uses a different underlying glyph rasterizer per
platform (CoreText vs FreeType), which isn't something Compose's public API lets you override.
`ViddikEngine.verify(...)` therefore treats a match as "≤ 0.5% of pixels differ" by default
(`ImageDiffer.DEFAULT_TOLERANCE_PERCENT`), not exact equality — a real UI regression moves far more
than a fraction of a percent of pixels, so this doesn't meaningfully weaken the check. Override per
call via `tolerancePercent`, or globally via the `viddik.tolerancePercent` system property.

This trades visual fidelity for portability — forced no-AA/no-hinting text looks visibly worse
(aliased/jagged), in both the recorded PNGs and the live `ViddikShowroom` browser. `ViddikConsistentRendering.isEnabled` defaults to `false` precisely so this degradation is opt-in, not automatic.

### 🗂️ Groups & registry

Every fixture belongs to a `group` (shown as a section in `ViddikShowroom`, and as a filename prefix
for its golden PNG). `GeneratedViddikRegistry.components: List<ViddikComponent>` is the single source
of truth both the tests and the browser read from — generated once per module by `viddik-processor`,
nothing to wire by hand.
