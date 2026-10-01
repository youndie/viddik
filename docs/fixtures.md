# Writing fixtures

A fixture is a `@Composable` marked `@ViddikScreenshot`, with only default-valued parameters. It
lives in the module's test source set — `src/desktopTest/kotlin` for a `jvm("desktop")` target,
`src/jvmTest/kotlin` for an unnamed `jvm()`, `src/test/kotlin` for a plain `kotlin("jvm")` module — or
in `commonMain` when the showroom has to reach Android and iOS ([showroom.md](showroom.md)).

```kotlin
@ViddikScreenshot(name = "AppButton - Primary", group = "Buttons")
@Composable
fun AppButtonPrimaryPreview() {
    MaterialTheme(typography = viddikTypography()) {
        Button(onClick = {}) { Text("Continue") }
    }
}
```

`viddikTypography()` is the bundled font; it is what makes the golden the same on every operating
system ([portability.md](portability.md)). It comes with `viddik-testing-core`, which is JVM-only, so
fixtures in `commonMain` bundle a font of their own instead.

## Names and groups

Every fixture has a `group` (a section in the showroom) and a `name`; the golden is
`<group>_<name>.png` in the snapshots directory, with anything outside `[A-Za-z0-9_.-]` replaced by
`_`. Keep names ASCII: two names that differ only in non-ASCII characters end up as the same file.
`--component` and the showroom search match on `"$group - $name"`.

KSP generates one `GeneratedViddikRegistry` per module; its `components: List<ViddikComponent>` is
what the tests, the showroom and design parity all read. There is nothing to wire by hand.

## Metadata from `@Preview`

`@ViddikScreenshot` also works as a bare marker, with the details read off an
`androidx.compose.ui.tooling.preview.Preview` on the same function:

```kotlin
@ViddikScreenshot
@Preview(name = "AppButton - Primary", group = "Buttons", widthDp = 320)
@Composable
fun AppButtonPrimaryPreview() {
    MaterialTheme {
        Button(onClick = {}) { Text("Continue") }
    }
}
```

Worth doing because that one annotation is read by three different things: the IDE preview pane,
Android's own screenshot tooling, and viddik. In Compose Multiplatform 1.12 it is literally the same
`androidx.compose.ui.tooling.preview.Preview` on Android and in `commonMain`, so a fixture declares
its name and size once and every tool agrees on them.

`@ViddikScreenshot` stays the opt-in and isn't going away: scanning every `@Preview` in a codebase
would silently turn previews written purely for the IDE into goldens, including the many that can't
render headless at all.

| `@Preview` field | becomes |
|---|---|
| `name`, `group` | the golden name and showroom group |
| `widthDp`, `heightDp` | the capture size in pixels — viddik renders at density 1 |
| `uiMode = UI_MODE_NIGHT_YES` | this fixture renders dark |

Precedence per field is: an argument on `@ViddikScreenshot`, then the `@Preview` field, then viddik's
default.

`darkVariant` and `tolerancePercent` are viddik's own — `@Preview` has no counterpart for either, so
those two are only ever read off `@ViddikScreenshot`.

Note that `uiMode` and `darkVariant` mean different things: `uiMode` says *this* fixture is dark,
`darkVariant = true` asks for a **second**, dark copy beside the light one. Setting both is an error
rather than a silently duplicated dark golden.

### Multipreview

`@Preview` is repeatable, and a multipreview annotation is just an annotation class carrying several
of them — so one marker gives one fixture per preview, `@PreviewLightDark` and hand-rolled ones alike:

```kotlin
@Preview(name = "Small", fontScale = 0.85f, widthDp = 320)
@Preview(name = "Large", fontScale = 1.5f, widthDp = 320)
annotation class AppTypeScale

@ViddikScreenshot(name = "Body text", group = "Type")
@AppTypeScale
@Composable
fun BodyText() { ... }
```

That records `Type - Body text - Small` and `Type - Body text - Large`. With several previews the name
on `@ViddikScreenshot` becomes the stem and each `@Preview` says which one it is; a preview with no
name of its own falls back to its index, so names can't collapse into each other. Multipreviews built
out of multipreviews resolve too.

`darkVariant` is refused alongside several previews — it would silently double all of them. Say which
ones are dark with `@PreviewLightDark` or a night `uiMode` instead.

### `fontScale` and `device`

`fontScale` is honoured: it scales text inside the capture without resizing the canvas, so
`@PreviewFontScale` produces genuinely different goldens rather than seven identical ones.

`device` is read only for its size, and only in the `spec:` form — `spec:width=411dp,height=891dp`
sets the capture size. Everything else a spec can say (`dpi`, `orientation`, `isRound`) is a density or
a device shape a plain canvas has no equivalent for; those are **warned about and dropped**, not
errors, because a fixture carrying `device` for the IDE's sake is still a perfectly good fixture. Named
devices (`id:pixel_5`) are warned about and ignored.

## One theme for every fixture: `@PreviewWrapper`

```kotlin
class AppPreviewTheme : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        MaterialTheme(typography = viddikTypography(), content = content)
    }
}

@ViddikScreenshot
@PreviewWrapper(AppPreviewTheme::class)
@Preview(name = "Primary", group = "Buttons")
@Composable
fun PrimaryButton() { ... }   // no theme call of its own
```

A theme can't be forced onto a composable from outside the composition, so without this every
fixture has to remember to call the theme that gives it the bundled font — and one that forgets
records a golden drawn in the host's system font, which is not portable. `@PreviewWrapper` puts the
theme in one place, and because it can sit on an annotation class, a project's own `@AppPreviews`
can carry the theme and the light/dark pair together.

## Dark variants

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

## Size

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

## Parameterized fixtures (`@PreviewParameter`)

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

One annotation gives one entry per provider value, each with its own golden, named
`<name> - <label> #<index>`. The label is the value's `previewLabel` when its type implements
`ViddikPreviewLabel`, its `toString()` otherwise, cut to 60 characters either way; the index is always
appended, so two values with the same label cannot overwrite each other's golden.

```kotlin
data class CheckboxPreviewState(
    val checked: Boolean,
    val enabled: Boolean,
) : ViddikPreviewLabel {
    override val previewLabel get() = if (enabled) "Enabled" else "Disabled"
}
```
