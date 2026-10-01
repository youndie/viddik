# Configuration

## Setup

`mavenCentral()` belongs in **both** settings blocks, and `google()` beside the second one:

```kotlin
// settings.gradle.kts
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

A plugin is resolved by its marker, out of `pluginManagement` — a block that does not look at Maven
Central unless it is told to. And Compose Multiplatform's desktop artifacts, which viddik brings with
it, depend on `androidx.compose.runtime:runtime` and `androidx.lifecycle:*`, published to Google's
repository and not to Central: without `google()` the build fails with
`Could not find androidx.compose.runtime:runtime`, naming the artifact rather than the missing
repository.

```kotlin
// build.gradle.kts of the module that holds the fixtures
plugins {
    id("com.google.devtools.ksp") version "<KSP_VERSION>" // must match your Kotlin compiler version
    id("io.github.youndie.viddik") version "<VERSION>"
}
```

The plugin adds the dependencies, puts the processor on the right KSP configuration, registers the
generated-source directory and the tasks. KSP is yours to apply, because its version is tied to your
exact Kotlin compiler; the plugin checks that it is there. Compose is yours too: the plugin adds no
`material3` or `compose.desktop` dependency, since it cannot know which of them your fixtures use.

Getting those names right is the part the plugin exists for: a `jvm("desktop")` target needs
`kspDesktopTest` / `src/desktopTest/snapshots`, an unnamed `jvm()` needs `kspJvmTest` /
`src/jvmTest/snapshots`, and a plain `kotlin("jvm")` module needs `kspTest` plus the
platform-suffixed artifacts (`viddik-annotations-desktop`, `viddik-testing-core-jvm`) because it
can't resolve a multiplatform variant. Get one of those wrong by hand and nothing errors — KSP just
reports `SKIPPED` and the screenshot task passes with no tests in it.

## Options

Every option has a default derived from the module; set only what you need.

```kotlin
viddik {
    tolerancePercent = 0.0
}
```

| option | default | what it does |
|---|---|---|
| `snapshotsDir` | `src/<test source set>/snapshots` | where the goldens live |
| `reportsDir` | `build/reports/screenshots` | where a failed comparison writes its `_DIFF.png` |
| `tolerancePercent` | 0.05 | share of pixels allowed to differ ([portability.md](portability.md#how-a-comparison-decides)) |
| `channelTolerance` | 2 | how far a channel may differ with the pixel still counting as equal |
| `minMismatchedPixels` | 16 | the pixel floor; 0 turns it off |
| `floorChannelDelta` | 96 | the floor only absorbs pixels within this delta; 255 makes it count pixels only |
| `verifyOnCheck` | `false` | make `check` depend on `viddikVerify`; `-Pviddik.verify` does it for one run |
| `excludeFromTestTask` | `true` | keep the generated tests out of the module's ordinary test task |
| `shards` | 2 | test classes and forks to spread the fixtures over ([running.md](running.md#speed)) |
| `sceneReuse` | `true` | serve every capture from one Compose scene |
| `kspDeclarationSnapshot` | `true` | let KSP see only declarations, so body edits don't re-run it |
| `glyphCheck` | off | fail a fixture whose text the font cannot draw ([portability.md](portability.md#glyphs-the-font-does-not-have)) |
| `glyphCheckFont` | bundled Roboto | the font `glyphCheck` reads |
| `designDir` | `<snapshotsDir>/design` | the design PNGs for [design parity](design-parity.md) |
| `designTolerancePercent` | 5.0 | share of pixels allowed to differ from the design |
| `designChannelTolerance` | 16 | per-channel allowance against the design |
| `designStrict` | `false` | fail on a design mismatch; `-Pviddik.designStrict` for one run |
| `showroomTargets` | `false` | generate the registry from `commonMain` for Android and iOS ([showroom.md](showroom.md#on-android-and-ios)) |
| `generateTests` | `true` | `false` generates the registry only, no JUnit 5 tests |
| `addDependencies` | `true` | `false` to declare the viddik artifacts yourself |
| `viddikVersion` | the plugin's version | version of the artifacts the plugin adds |

## Compatibility

`viddik-testing-core` renders through `ComposeScene` and skiko directly, so each viddik line is bound
to one Compose Multiplatform line. A mismatch does not fail compilation: it shows up on the first
captured frame as `NoSuchMethodError` or `IllegalAccessError`.

| viddik | Compose Multiplatform | Kotlin |
|---|---|---|
| 0.2 – 0.7 | 1.12 | 2.4 |
| 0.1 | 1.11 | 2.4 |

viddik 0.7 needs JDK 21 to build against: the artifacts and the plugin are published for it, and an
older toolchain fails resolution with "No matching variant". An Android consumer of
`viddik-annotations` needs `compileSdk = 37`, which Compose Multiplatform 1.12 requires of everything
that depends on it. Which version brought which feature is in the
[release notes](https://github.com/youndie/viddik/releases).

## Declaring the dependencies by hand

With `addDependencies = false` — or without the plugin at all:

```kotlin
dependencies {
    // KMP consumer (e.g. your own jvm("desktop") target) — base coordinates, no target suffix:
    testImplementation("io.github.youndie.viddik:viddik-annotations:<VERSION>")
    testImplementation("io.github.youndie.viddik:viddik-testing-core:<VERSION>")
    add("kspDesktopTest", "io.github.youndie.viddik:viddik-processor:<VERSION>")

    // Plain kotlin("jvm") consumer, NOT KMP-aware — needs the explicit per-target artifacts instead:
    // testImplementation("io.github.youndie.viddik:viddik-annotations-desktop:<VERSION>")
    // testImplementation("io.github.youndie.viddik:viddik-testing-core-jvm:<VERSION>")
    // kspTest("io.github.youndie.viddik:viddik-processor:<VERSION>")
}
```

`viddik-annotations` is the lightweight API surface (the `@ViddikScreenshot` marker, `ViddikComponent`,
`ViddikShowroom`) — safe to depend on from any Compose Multiplatform target: `android()`, `jvm()` and
iOS. `viddik-processor` is the KSP codegen (registry + JUnit5 tests). `viddik-testing-core` is the
JVM-only capture/diff/record engine (JUnit5 + Compose Desktop) — only ever needed on a
`test`/`jvmTest`/`desktopTest` classpath, never `main`. `viddik-showroom` is the optional host layer:
an Android activity and an iOS view controller over the same browser, and the only artifact you would
ever put on `main`.
