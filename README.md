# viddik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.20-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![maven central](https://img.shields.io/maven-central/v/io.github.youndie.viddik/viddik-gradle-plugin?label=maven%20central&color=40c14a)](https://central.sonatype.com/namespace/io.github.youndie.viddik)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue?logoColor=white)](https://youndie.github.io/viddik/)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

**Screenshot tests for Compose Multiplatform on a plain JVM.** One annotation on a composable gives a
golden-file test and an entry in a component browser — rendered through Compose Desktop/Skiko, with
no emulator, no AVD and no layoutlib.

```kotlin
@ViddikScreenshot(name = "Primary", group = "Buttons")
@Composable
fun PrimaryButton() {
    MaterialTheme(typography = viddikTypography()) {
        Button(onClick = {}) { Text("Continue") }
    }
}
```

```bash
./gradlew :yourModule:viddikRecord     # write the goldens
./gradlew :yourModule:viddikVerify     # compare against them
./gradlew :yourModule:viddikShowroom   # browse the components in a window
```

## Installation

```kotlin
// settings.gradle.kts — mavenCentral() in both blocks, google() beside the second
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts of the module that holds the fixtures
plugins {
    id("com.google.devtools.ksp") version "<KSP_VERSION>" // must match your Kotlin compiler version
    id("io.github.youndie.viddik") version "0.7.0"
}
```

That is the whole setup: the plugin adds the dependencies, the KSP processor and the tasks, with the
names your module's shape needs. viddik 0.7 works with Compose Multiplatform 1.12, Kotlin 2.4 and
JDK 21. Options, compatibility and the setup without the plugin: [docs/configuration.md](docs/configuration.md).

## How it compares

Measured in [screenshot-bench](https://github.com/youndie/screenshot-bench): the same generated
composables at 360×640, each tool set up as its own documentation says, one build machine
(20 cores, Linux), medians. viddik 0.6.1.45 for the speed rows, which is the code of 0.7.0 there.

| | viddik | Roborazzi, desktop | Roborazzi, Robolectric | Paparazzi |
|---|---|---|---|---|
| Renderer | Skiko | Skiko | Android framework | layoutlib |
| Verify 1000 screenshots, one JVM | 25.1 s | 29.6 s | 41.5 s | 28.7 s |
| Verify 2000 screenshots, four forks | 14.8 s | 17.9 s | 35.9 s | 22.7 s |
| Edit a component, record one golden (N=1000) | 3.3 s | 2.8 s | 7.4 s | 5.5 s |
| Linux goldens verified on macOS (N=50) | 50/50 pass, 38 byte-identical | 0/50 pass | 50/50, byte-identical | 50/50, byte-identical |
| A full stop added to a heading, default settings | 2/10 caught | 10/10 | 10/10 | 2/10 |

On large suites the two Skiko tools and Paparazzi are within a few seconds of each other; the
edit-and-record cycle is where they differ most. viddik's goldens travel between operating systems on
Skiko, where Roborazzi's do not — the Android renderers get that for free. The price is a tolerance:
at the default 0.05% a 12-pixel change on a full screen passes; with `tolerancePercent = 0.0` it is
caught and the cross-OS goldens still pass ([how a comparison decides](docs/portability.md#how-a-comparison-decides)).

## What it does

- **Fixtures** — `@ViddikScreenshot`, or `@Preview` metadata read off the same function, multipreviews,
  `@PreviewParameter`, dark variants, one theme for all of them through `@PreviewWrapper`.
  [docs/fixtures.md](docs/fixtures.md)
- **Recording and verifying** — a record writes only the goldens a verification would reject;
  `--component` selects one fixture; one scene, two forks and a KSP that ignores body edits by
  default. [docs/running.md](docs/running.md)
- **Goldens across operating systems** — record on macOS, verify on Linux: viddik rasterizes glyphs
  itself, and a bundled font (`viddikTypography()`) does the rest. [docs/portability.md](docs/portability.md)
- **The showroom** — a component browser over the same registry, in a desktop window or in an
  Android or iOS app. [docs/showroom.md](docs/showroom.md)
- **Design parity** — how far each fixture is from the PNG exported from its design.
  [docs/design-parity.md](docs/design-parity.md)

API reference: [youndie.github.io/viddik](https://youndie.github.io/viddik/). Release notes:
[GitHub releases](https://github.com/youndie/viddik/releases).

## License

MIT, see [LICENSE](LICENSE).
