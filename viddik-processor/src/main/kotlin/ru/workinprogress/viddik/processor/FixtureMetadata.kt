package ru.workinprogress.viddik.processor

// How a fixture's name, size and theme are decided, kept apart from KSP so it can be tested without
// standing up a compilation. ViddikSymbolProcessor reads the two annotations into
// ScreenshotArgs/PreviewArgs and does nothing else with their values.

/** `@ViddikScreenshot`'s arguments, as KSP hands them over — defaults already substituted. */
internal data class ScreenshotArgs(
    val name: String? = null,
    val group: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val darkVariant: Boolean = false,
)

/** The fields of `androidx.compose.ui.tooling.preview.Preview` that mean something to viddik. */
internal data class PreviewArgs(
    val name: String? = null,
    val group: String? = null,
    val widthDp: Int = PREVIEW_UNSET_DP,
    val heightDp: Int = PREVIEW_UNSET_DP,
    val uiMode: Int = 0,
)

internal data class FixtureMetadata(
    val name: String,
    val group: String,
    val width: Int,
    val height: Int,
    /** The fixture itself renders dark — `@Preview(uiMode = UI_MODE_NIGHT_YES)`. */
    val dark: Boolean,
    /** A *second*, dark entry is wanted next to the light one — `@ViddikScreenshot(darkVariant = true)`. */
    val darkVariant: Boolean,
)

/**
 * Resolves one fixture. Precedence per field: the argument on `@ViddikScreenshot`, then the matching
 * `@Preview` field, then viddik's own default.
 *
 * Returns null and reports through [onError] when the two annotations contradict each other, rather
 * than silently picking one — a fixture that asks to be dark *and* asks for a dark variant of itself
 * would otherwise quietly produce a dark image and a second, identical dark image beside it.
 */
internal fun resolveFixture(
    functionName: String,
    screenshot: ScreenshotArgs,
    preview: PreviewArgs?,
    onError: (String) -> Unit,
): FixtureMetadata? {
    val dark = preview != null && preview.uiMode.isNightMode()

    if (dark && screenshot.darkVariant) {
        onError(
            "darkVariant = true asks for a second, dark copy of a fixture that @Preview(uiMode = " +
                "UI_MODE_NIGHT_YES) already renders dark. Drop one of the two: keep uiMode to make this " +
                "fixture dark, or keep darkVariant and let @Preview stay light.",
        )
        return null
    }

    return FixtureMetadata(
        name =
            screenshot.name?.takeIf { it.isNotBlank() }
                ?: preview?.name?.takeIf { it.isNotBlank() }
                ?: functionName,
        group =
            screenshot.group?.takeIf { it.isNotBlank() }
                ?: preview?.group?.takeIf { it.isNotBlank() }
                ?: DEFAULT_GROUP,
        width =
            screenshot.width?.takeIf { it != UNSPECIFIED }
                ?: preview?.widthDp?.takeIf { it > 0 }
                ?: DEFAULT_WIDTH,
        height =
            screenshot.height?.takeIf { it != UNSPECIFIED }
                ?: preview?.heightDp?.takeIf { it > 0 }
                ?: AUTO_HEIGHT,
        dark = dark,
        darkVariant = screenshot.darkVariant,
    )
}

/**
 * `uiMode` is a bit field, not an enum: the night bits are two of them, and the rest carry the
 * device type (television, watch, car), which viddik has no opinion about.
 */
private fun Int.isNightMode(): Boolean = (this and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

// Mirrors android.content.res.Configuration, which is where @Preview's uiMode values come from and
// which is not on this module's classpath — viddik-processor is a plain JVM module with no Android
// dependency, and gaining one to read two constants would be a poor trade.
private const val UI_MODE_NIGHT_MASK = 0x30
private const val UI_MODE_NIGHT_YES = 0x20

/** `@Preview` spells "no size given" as -1; anything <= 0 is treated as absent. */
internal const val PREVIEW_UNSET_DP = -1

// Kept in step with ru.workinprogress.viddik.annotations, which this module deliberately does not
// depend on: the processor runs on the build's own classpath, not the consumer's.
internal const val UNSPECIFIED = Int.MIN_VALUE
internal const val AUTO_HEIGHT = -1
internal const val DEFAULT_WIDTH = 400
internal const val DEFAULT_GROUP = "Default"
