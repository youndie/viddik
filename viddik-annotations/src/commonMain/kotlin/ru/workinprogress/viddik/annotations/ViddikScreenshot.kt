package ru.workinprogress.viddik.annotations

/**
 * Marks a `@Composable` as a viddik fixture: one golden image, and one entry in the showroom.
 *
 * Every descriptive parameter is optional. What is left out is read from an
 * `androidx.compose.ui.tooling.preview.Preview` annotation on the same function, when there is one,
 * so a composable that already carries a preview for the IDE does not have to spell its name and
 * size a second time:
 *
 * ```
 * @ViddikScreenshot
 * @Preview(name = "Primary", group = "Buttons", widthDp = 320)
 * @Composable
 * fun PrimaryButton() { ... }
 * ```
 *
 * That is the whole reason this annotation carries no parameters in the example: `@Preview` is a
 * superset of what viddik needs, it means the same thing to the IDE preview pane and to Android's
 * own screenshot tooling, and in Compose Multiplatform 1.12 it is the very same
 * `androidx.compose.ui.tooling.preview.Preview` on both. Reading it keeps one declaration serving
 * all three.
 *
 * This annotation stays the opt-in, though, and is not going away: scanning every `@Preview` in a
 * codebase would silently turn previews written purely for the IDE into goldens, including the many
 * that cannot render headless at all.
 *
 * Precedence for each field is: an argument given here, then the matching `@Preview` field, then the
 * default. Arguments given here therefore keep working exactly as before.
 *
 * @param name golden-file and showroom name; defaults to the `@Preview` name, then to the function's
 *   own name.
 * @param group showroom grouping; defaults to the `@Preview` group, then to `"Default"`.
 * @param width capture width in pixels; defaults to `@Preview.widthDp`, then to 400. viddik renders
 *   at density 1, so a dp from `@Preview` is a pixel here — see `ViddikDensityTest`, which pins that.
 * @param height capture height in pixels; defaults to `@Preview.heightDp`, then to [AUTO_HEIGHT],
 *   which measures the content and crops to it.
 * @param darkVariant emit a *second*, dark entry alongside the light one. Distinct from
 *   `@Preview(uiMode = UI_MODE_NIGHT_YES)`, which says this one fixture is dark rather than asking
 *   for another one; setting both is an error.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ViddikScreenshot(
    val name: String = "",
    val group: String = "",
    val width: Int = UNSPECIFIED,
    val height: Int = UNSPECIFIED,
    val darkVariant: Boolean = false,
)
