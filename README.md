# viddik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![viddik-annotations](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-annotations?name=annotations&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-annotations)
[![viddik-processor](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-processor?name=processor&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-processor)
[![viddik-testing-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-testing-core?name=testing-core&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-testing-core)
[![viddik-gradle-plugin](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-gradle-plugin?name=gradle-plugin&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-gradle-plugin)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue?logoColor=white)](https://youndie.github.io/viddik/)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

**screenshot-testing toolkit for Compose Multiplatform** — a showkase + paparazzi analog that renders
through a real **Compose Desktop/Skiko** JVM window instead of Android/LayoutLib

> 🖼️ one annotation → a golden-file test + a live entry in an interactive component browser

No emulator, no AVD, no LayoutLib — `@ViddikScreenshot`-annotated composables are collected by a KSP
processor into a component registry, then either captured to PNG and diffed (`ViddikEngine`, record/
verify) or shown live in a portable browser (`ViddikShowroom`), all on a plain JVM.

### 📦 Installation

Add the Reposilite snapshot repository to `settings.gradle.kts`, and apply the plugin:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.kotlin.website/snapshots")
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://reposilite.kotlin.website/snapshots")
    }
}
```

```kotlin
// build.gradle.kts of the module that holds the fixtures
plugins {
    id("com.google.devtools.ksp") version "<KSP_VERSION>" // must match your Kotlin compiler version
    id("ru.workinprogress.viddik") version "<VERSION>"
}
```

That's the whole setup. The plugin adds the dependencies, puts the processor on the right KSP
configuration, registers the generated-source directory, and gives you two tasks:

```bash
./gradlew :yourModule:viddikRecord   # write the goldens
./gradlew :yourModule:viddikVerify   # compare against them
./gradlew :yourModule:viddikShowroom # open the component browser in a window
```

Which names those are is the part the plugin exists for: a `jvm("desktop")` target needs
`kspDesktopTest` / `src/desktopTest/snapshots`, an unnamed `jvm()` needs `kspJvmTest` /
`src/jvmTest/snapshots`, and a plain `kotlin("jvm")` module needs `kspTest` plus the
platform-suffixed artifacts (`viddik-annotations-desktop`, `viddik-testing-core-jvm`) because it
can't resolve a multiplatform variant. Get one of those wrong by hand and nothing errors — KSP just
reports `SKIPPED` and the screenshot task passes with no tests in it.

Everything is configurable, and every default is derived from the module:

```kotlin
viddik {
    snapshotsDir = "src/desktopTest/snapshots" // default: src/<test source set>/snapshots
    tolerancePercent = 0.5                     // default: viddik's own 0.05%
    channelTolerance = 0                       // default: viddik's own ±2
    reportsDir = "build/reports/screenshots"   // where a failed comparison writes its _DIFF.png
    verifyOnCheck = true                       // default: false; -Pviddik.verify turns it on per-run
    generateTests = false                      // registry only, no JUnit5 tests (Android app modules)
    excludeFromTestTask = false                // default: true — see below
    addDependencies = false                    // declare the viddik artifacts yourself instead
    viddikVersion = "<VERSION>"                // default: the plugin's own version
}
```

By default the goldens are **not** wired into `check`, and the generated tests are excluded from the
module's ordinary test task. Goldens are portable once your fixtures bundle a font (see
"Cross-platform goldens" below), but a project that hasn't done that yet has host-specific goldens,
and those would redden `./gradlew build` on every machine that didn't record them. Turn the check on
for good with `verifyOnCheck = true`, or per run with `./gradlew check -Pviddik.verify`.

#### Compatibility

`viddik-testing-core` renders through `ComposeScene` and `skiko` directly, so it is bound to one
Compose Multiplatform line rather than to a range of them — a mismatch shows up at runtime
(`NoSuchMethodError` / `IllegalAccessError` on the first frame), not at compile time.

| viddik | Compose Multiplatform | Kotlin |
|---|---|---|
| 0.2.x | 1.12.x | 2.4.x |
| 0.1.x | 1.11.x | 2.4.x |

An Android consumer of `viddik-annotations` needs `compileSdk = 37` from 0.2.0 on — that is what
Compose Multiplatform 1.12 requires of everything that depends on it.

#### Declaring the dependencies by hand

With `addDependencies = false` — or without the plugin at all:

```kotlin
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
`desktopTest` classpath, never `main`. Compose itself stays yours: the plugin adds no `material3` or
`compose.desktop` dependency, since it can't know which of them your fixtures use.

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
# Record every golden in the module (writes src/<test source set>/snapshots/*.png — verify them
# visually, record mode doesn't validate anything)
./gradlew :yourModule:viddikRecord

# Verify (compares against the recorded goldens, fails with a saved _DIFF.png on mismatch)
./gradlew :yourModule:viddikVerify

# Live in a window — same registry, no capture, just an interactive browser
./gradlew :yourModule:viddikShowroom
```

To work on one component, use `--component` — Gradle's own `--tests` can't help here, since every
fixture is a JUnit5 **dynamic** test under a single `GeneratedViddikTests` class and `--tests` only
matches classes and methods:

```bash
./gradlew :yourModule:viddikRecord --component "Buttons - Primary"  # rewrites one golden
./gradlew :yourModule:viddikVerify --component Primary              # bare substring
./gradlew :yourModule:viddikVerify --component "Buttons*Dark"       # * and ? are wildcards
```

The pattern is a **case-insensitive substring** of `"$group - $name"`, with `*` and `?` as wildcards.
A pattern that matches nothing fails the task and lists what the module does have, rather than
reporting a green run of zero screenshots.

Without the plugin, recording is the `VIDDIK_RECORD_MODE` environment variable on whatever test task
runs the generated class (`VIDDIK_RECORD_MODE=true ./gradlew :yourModule:test --rerun`), and the
browser is a `fun main()` you write yourself:

```kotlin
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

**Goldens are portable.** Record on macOS, verify on Linux CI, or the other way round. On this
repo's own suite 8 of 10 goldens come out byte-identical across Windows and Linux (same md5) and the
other two differ by 1–2 pixels within the channel tolerance; every PR re-checks the committed PNGs on
`ubuntu-latest`, `macos-latest` (arm64) and `windows-latest` at once
(`.github/workflows/verify-goldens.yaml`). No Docker, no "record only on the runner", no per-OS
baselines.

That isn't free by default though, because Skia's text rendering is platform-specific in two
independent ways. viddik fixes one of them for you and hands you the tool for the other.

**Fixed automatically — glyph rasterization.** Everything Skia draws except glyphs goes through its
own scan converter, identical in every skiko build; glyphs instead go to the host font backend
(CoreText / DirectWrite / FreeType), and no combination of `FontRasterizationSettings` makes those
three agree. `CaptureEngine` sidesteps the backend entirely: it hands the canvas a matrix carrying a
1e-9 perspective term, which is Skia's own documented condition for abandoning the glyph mask cache
and filling glyph outlines with its regular path rasterizer. Geometry shifts by ~1e-6 px, text keeps
full anti-aliasing, and rendering stops depending on the OS. Nothing to configure.

**Your job — fonts.** Skia renders text through whatever fonts the host OS has installed, so a golden
recorded against the macOS system UI font can't match a bare Linux runner. Bundle a font file:

- **No font of your own?** Use the bundled Roboto (OFL, variable, single file for every weight):

  ```kotlin
  MaterialTheme(typography = viddikTypography()) { content() }
  ```

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

**What still isn't portable:** glyphs your bundled font doesn't have. A `世界`, an emoji, or a `✕`
used as a close button falls back to a *host* font — real CJK on a Mac, tofu boxes in a bare
container, Segoe UI Symbol on Windows. This one can't be fixed from outside Compose (a fallback font
registered with Skia is only consulted after the host's, and ParagraphBuilder pins one typeface per
style, so per-character family fallback never runs — both measured, see CLAUDE.md), so viddik reports
it instead:

```kotlin
check(ViddikGlyphCoverage.missingGlyphs(label).isEmpty()) { "host fonts would draw these: $label" }
```

`missingGlyphs(text, fontBytes = bundled Roboto)` reads the font's own `cmap`. Non-empty means that
text renders differently per machine — draw the icon as an icon, or bundle a font that covers it.

`ViddikEngine.verify(...)` treats a match as "≤ 0.05% of pixels differ"
(`ImageDiffer.DEFAULT_TOLERANCE_PERCENT`) with a ±2 per-channel allowance. For scale: adding one
character to a button label moves 1.32% of the pixels, so this is a strict check, not a loose one.
Override per call via `tolerancePercent`, or globally via the `viddik.tolerancePercent` system
property.

### 🗂️ Groups & registry

Every fixture belongs to a `group` (shown as a section in `ViddikShowroom`, and as a filename prefix
for its golden PNG). `GeneratedViddikRegistry.components: List<ViddikComponent>` is the single source
of truth both the tests and the browser read from — generated once per module by `viddik-processor`,
nothing to wire by hand.
