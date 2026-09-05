package io.github.youndie.viddik.processor

// How a fixture's name, size and theme are decided, kept apart from KSP so it can be tested without
// standing up a compilation. ViddikSymbolProcessor collects the annotations into
// ScreenshotArgs/PreviewArgs — including expanding multipreview annotations, which needs KSP — and
// does nothing else with their values.

/** `@ViddikScreenshot`'s arguments, as KSP hands them over — defaults already substituted. */
internal data class ScreenshotArgs(
    val name: String? = null,
    val group: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val darkVariant: Boolean = false,
    val tolerancePercent: Double? = null,
)

/** The fields of `androidx.compose.ui.tooling.preview.Preview` that mean something to viddik. */
internal data class PreviewArgs(
    val name: String? = null,
    val group: String? = null,
    val widthDp: Int = PREVIEW_UNSET_DP,
    val heightDp: Int = PREVIEW_UNSET_DP,
    val uiMode: Int = 0,
    val fontScale: Float = 1f,
    val device: String = "",
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
    val fontScale: Float = 1f,
    /** This fixture's own diff budget, or null to be judged by whatever the run is set to. */
    val tolerancePercent: Double? = null,
)

/**
 * Resolves every fixture one annotated function contributes: one per `@Preview` after multipreview
 * annotations have been expanded, or exactly one when the function carries no `@Preview` at all.
 *
 * Reports through [onError]/[onWarn] rather than guessing. An error drops the whole function — a
 * fixture whose two annotations contradict each other has no defensible reading, and picking one
 * silently is how a wrong golden gets recorded and then trusted.
 */
internal fun resolveFixtures(
    functionName: String,
    rawScreenshot: ScreenshotArgs,
    previews: List<PreviewArgs>,
    onError: (String) -> Unit,
    onWarn: (String) -> Unit = {},
): List<FixtureMetadata> {
    // Validated once here rather than per preview: the tolerance is written on @ViddikScreenshot, so a
    // multipreview would otherwise report the same complaint once per fixture it produces.
    val tolerance = rawScreenshot.tolerancePercent?.takeIf { it != UNSPECIFIED_TOLERANCE }
    if (tolerance != null) {
        if (tolerance < 0.0 || tolerance > MAX_TOLERANCE_PERCENT) {
            onError(
                "tolerancePercent = $tolerance is not a share of pixels. It has to be between 0 and " +
                    "$MAX_TOLERANCE_PERCENT, and is a percentage: 6.0 means 6%, not 600%.",
            )
            return emptyList()
        }
        if (tolerance == MAX_TOLERANCE_PERCENT) {
            onWarn(
                "tolerancePercent = $MAX_TOLERANCE_PERCENT lets every pixel differ, so this fixture can " +
                    "no longer fail. Delete it or record it, rather than keeping a green check that " +
                    "checks nothing.",
            )
        }
    }
    val screenshot = rawScreenshot.copy(tolerancePercent = tolerance)

    if (previews.isEmpty()) {
        return listOfNotNull(resolveOne(functionName, screenshot, preview = null, onError = onError, onWarn = onWarn))
    }
    if (previews.size == 1) {
        return listOfNotNull(resolveOne(functionName, screenshot, previews.single(), onError, onWarn))
    }

    if (screenshot.darkVariant) {
        // Otherwise one @PreviewFontScale plus darkVariant quietly becomes fourteen goldens.
        onError(
            "darkVariant = true doubles every fixture a function produces, and this one already " +
                "produces ${previews.size} through its @Preview annotations. Use @PreviewLightDark, or a " +
                "@Preview(uiMode = UI_MODE_NIGHT_YES) among them, to say which ones are dark.",
        )
        return emptyList()
    }

    // With several previews the name on @ViddikScreenshot can no longer *be* the name — it would be the
    // same one for all of them. It becomes the stem, and each preview says which of them it is. The
    // built-in multipreviews all name their previews ("Light"/"Dark", "85%"…"200%", "Phone"/"Tablet"…),
    // so the index fallback is only for hand-rolled ones that don't.
    val stem = screenshot.name?.takeIf { it.isNotBlank() } ?: functionName
    return previews.mapIndexedNotNull { index, preview ->
        val discriminator = preview.name?.takeIf { it.isNotBlank() } ?: "#$index"
        resolveOne(
            functionName = "$stem - $discriminator",
            screenshot = screenshot.copy(name = "$stem - $discriminator"),
            preview = preview.copy(name = null),
            onError = onError,
            onWarn = onWarn,
        )
    }
}

private fun resolveOne(
    functionName: String,
    screenshot: ScreenshotArgs,
    preview: PreviewArgs?,
    onError: (String) -> Unit,
    onWarn: (String) -> Unit,
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

    val device = preview?.device?.let { parseDeviceSpec(it, onWarn) }

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
                ?: device?.widthDp?.takeIf { it > 0 }
                ?: DEFAULT_WIDTH,
        height =
            screenshot.height?.takeIf { it != UNSPECIFIED }
                ?: preview?.heightDp?.takeIf { it > 0 }
                ?: device?.heightDp?.takeIf { it > 0 }
                ?: AUTO_HEIGHT,
        dark = dark,
        darkVariant = screenshot.darkVariant,
        fontScale = preview?.fontScale ?: 1f,
        // Already normalized and validated by resolveFixtures; @Preview has no counterpart to fall back to.
        tolerancePercent = screenshot.tolerancePercent,
    )
}

internal data class DeviceSpec(
    val widthDp: Int = PREVIEW_UNSET_DP,
    val heightDp: Int = PREVIEW_UNSET_DP,
)

/**
 * Reads the size out of a `@Preview(device = ...)` string.
 *
 * Only the `spec:` form carries numbers viddik can act on, and only its `width`/`height` at that:
 * everything else a spec can say — `dpi`, `orientation`, `isRound`, `chinSize` — is either a density
 * (which the capture harness pins at 1, see `ViddikDensityTest`) or a device shape that a plain canvas
 * has no equivalent for. Those are dropped with a warning rather than an error: a fixture carrying
 * `device` for the IDE's sake is still a perfectly good fixture, it just doesn't get that device.
 */
internal fun parseDeviceSpec(
    device: String,
    onWarn: (String) -> Unit,
): DeviceSpec? {
    if (device.isBlank()) return null
    if (!device.startsWith(SPEC_PREFIX)) {
        onWarn(
            "device = \"$device\" is a named device, whose dimensions live in Android's device catalogue " +
                "rather than in the annotation. It is ignored; give widthDp/heightDp to size this fixture.",
        )
        return null
    }

    var widthDp = PREVIEW_UNSET_DP
    var heightDp = PREVIEW_UNSET_DP
    val ignored = mutableListOf<String>()

    device.removePrefix(SPEC_PREFIX).split(',').forEach { entry ->
        val key = entry.substringBefore('=').trim()
        val rawValue = entry.substringAfter('=', "").trim()
        when (key) {
            "" -> Unit
            "width" -> widthDp = rawValue.removeSuffix("dp").toIntOrNull() ?: PREVIEW_UNSET_DP
            "height" -> heightDp = rawValue.removeSuffix("dp").toIntOrNull() ?: PREVIEW_UNSET_DP
            else -> ignored += key
        }
    }

    if (ignored.isNotEmpty()) {
        onWarn(
            "device = \"$device\": ${ignored.joinToString()} ${if (ignored.size == 1) "is" else "are"} " +
                "ignored. viddik captures into a plain canvas at density 1, so only width and height " +
                "carry over.",
        )
    }
    return DeviceSpec(widthDp, heightDp)
}

/**
 * `uiMode` is a bit field, not an enum: the night bits are two of them, and the rest carry the
 * device type. `@PreviewLightDark`'s dark half is 33 — night-yes *or* type-normal — so comparing for
 * equality against 32 would miss it.
 */
private fun Int.isNightMode(): Boolean = (this and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

// Mirrors android.content.res.Configuration, which is where @Preview's uiMode values come from and
// which is not on this module's classpath — viddik-processor is a plain JVM module with no Android
// dependency, and gaining one to read two constants would be a poor trade.
private const val UI_MODE_NIGHT_MASK = 0x30
private const val UI_MODE_NIGHT_YES = 0x20

private const val SPEC_PREFIX = "spec:"

/** `@Preview` spells "no size given" as -1; anything <= 0 is treated as absent. */
internal const val PREVIEW_UNSET_DP = -1

// Kept in step with io.github.youndie.viddik.annotations, which this module deliberately does not
// depend on: the processor runs on the build's own classpath, not the consumer's.
internal const val UNSPECIFIED = Int.MIN_VALUE
internal const val UNSPECIFIED_TOLERANCE = -1.0
internal const val AUTO_HEIGHT = -1
internal const val DEFAULT_WIDTH = 400
internal const val DEFAULT_GROUP = "Default"

/** A tolerance is a share of pixels, so 100 is every pixel on the canvas and there is nothing above it. */
internal const val MAX_TOLERANCE_PERCENT = 100.0
