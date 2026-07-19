# viddik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.0-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![viddik-annotations](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-annotations?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-annotations)
[![viddik-processor](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-processor?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-processor)
[![viddik-testing-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/ru/workinprogress/viddik-testing-core?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/ru/workinprogress/viddik-testing-core)
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

### 🗂️ Groups & registry

Every fixture belongs to a `group` (shown as a section in `ViddikShowroom`, and as a filename prefix
for its golden PNG). `GeneratedViddikRegistry.components: List<ViddikComponent>` is the single source
of truth both the tests and the browser read from — generated once per module by `viddik-processor`,
nothing to wire by hand.
