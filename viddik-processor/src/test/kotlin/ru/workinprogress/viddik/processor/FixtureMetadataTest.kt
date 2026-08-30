package ru.workinprogress.viddik.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UI_MODE_NIGHT_YES = 0x20
private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_TYPE_TELEVISION = 0x04

/** What @PreviewLightDark actually carries on its dark half: night-yes *or* type-normal. */
private const val PREVIEW_LIGHT_DARK_NIGHT = 33

/** The arguments KSP hands over for a bare `@ViddikScreenshot` — every default substituted. */
private val bareScreenshot =
    ScreenshotArgs(name = "", group = "", width = UNSPECIFIED, height = UNSPECIFIED, darkVariant = false)

private fun resolveAll(
    functionName: String = "SampleFixture",
    screenshot: ScreenshotArgs = bareScreenshot,
    previews: List<PreviewArgs> = emptyList(),
): List<FixtureMetadata> = resolveFixtures(functionName, screenshot, previews, onError = { }, onWarn = { })

private fun resolve(
    functionName: String = "SampleFixture",
    screenshot: ScreenshotArgs = bareScreenshot,
    preview: PreviewArgs? = null,
): FixtureMetadata? = resolveAll(functionName, screenshot, listOfNotNull(preview)).singleOrNull()

private fun errorFrom(
    screenshot: ScreenshotArgs = bareScreenshot,
    previews: List<PreviewArgs> = emptyList(),
): String? {
    var message: String? = null
    resolveFixtures("SampleFixture", screenshot, previews, onError = { message = it }, onWarn = { })
    return message
}

private fun warningsFrom(preview: PreviewArgs): List<String> {
    val warnings = mutableListOf<String>()
    resolveFixtures("SampleFixture", bareScreenshot, listOf(preview), onError = { }, onWarn = { warnings += it })
    return warnings
}

class FixtureMetadataTest {
    @Test
    fun `falls back to viddik defaults with neither annotation carrying anything`() {
        val fixture = checkNotNull(resolve())

        assertEquals("SampleFixture", fixture.name)
        assertEquals(DEFAULT_GROUP, fixture.group)
        assertEquals(DEFAULT_WIDTH, fixture.width)
        assertEquals(AUTO_HEIGHT, fixture.height)
        assertEquals(false, fixture.dark)
    }

    @Test
    fun `reads name group and size off Preview when ViddikScreenshot is a bare marker`() {
        val fixture =
            checkNotNull(
                resolve(preview = PreviewArgs(name = "Primary", group = "Buttons", widthDp = 320, heightDp = 96)),
            )

        assertEquals("Primary", fixture.name)
        assertEquals("Buttons", fixture.group)
        assertEquals(320, fixture.width)
        assertEquals(96, fixture.height)
    }

    @Test
    fun `arguments on ViddikScreenshot win over Preview`() {
        val fixture =
            checkNotNull(
                resolve(
                    screenshot = bareScreenshot.copy(name = "Explicit", group = "Ours", width = 500, height = 200),
                    preview = PreviewArgs(name = "Primary", group = "Buttons", widthDp = 320, heightDp = 96),
                ),
            )

        assertEquals("Explicit", fixture.name)
        assertEquals("Ours", fixture.group)
        assertEquals(500, fixture.width)
        assertEquals(200, fixture.height)
    }

    @Test
    fun `each field falls back on its own`() {
        val fixture =
            checkNotNull(
                resolve(
                    screenshot = bareScreenshot.copy(width = 500),
                    preview = PreviewArgs(name = "Primary", widthDp = 320, heightDp = 96),
                ),
            )

        assertEquals(500, fixture.width, "the one field given here should win")
        assertEquals(96, fixture.height, "and the rest should still come from @Preview")
        assertEquals("Primary", fixture.name)
    }

    @Test
    fun `a width of 400 given by hand is not mistaken for the default`() {
        // The whole reason UNSPECIFIED exists: KSP substitutes defaults before the processor sees them,
        // so "omitted" and "written out" are otherwise the same value.
        val fixture =
            checkNotNull(
                resolve(
                    screenshot = bareScreenshot.copy(width = DEFAULT_WIDTH),
                    preview = PreviewArgs(widthDp = 320),
                ),
            )

        assertEquals(DEFAULT_WIDTH, fixture.width)
    }

    @Test
    fun `an unset Preview size does not override viddiks default`() {
        val fixture = checkNotNull(resolve(preview = PreviewArgs(widthDp = PREVIEW_UNSET_DP, heightDp = 0)))

        assertEquals(DEFAULT_WIDTH, fixture.width)
        assertEquals(AUTO_HEIGHT, fixture.height)
    }

    @Test
    fun `blank Preview name and group fall through to the defaults`() {
        val fixture = checkNotNull(resolve(preview = PreviewArgs(name = "", group = "  ")))

        assertEquals("SampleFixture", fixture.name)
        assertEquals(DEFAULT_GROUP, fixture.group)
    }

    @Test
    fun `night uiMode makes the fixture itself dark`() {
        val fixture = checkNotNull(resolve(preview = PreviewArgs(uiMode = UI_MODE_NIGHT_YES)))

        assertTrue(fixture.dark)
        assertEquals(false, fixture.darkVariant, "a dark fixture should not also ask for a dark copy")
    }

    @Test
    fun `uiMode is read as a bit field not as a value`() {
        // A television preview in night mode is still night mode; a television preview is not.
        assertTrue(
            checkNotNull(resolve(preview = PreviewArgs(uiMode = UI_MODE_NIGHT_YES or UI_MODE_TYPE_TELEVISION))).dark,
        )
        assertEquals(false, checkNotNull(resolve(preview = PreviewArgs(uiMode = UI_MODE_TYPE_TELEVISION))).dark)
        assertEquals(false, checkNotNull(resolve(preview = PreviewArgs(uiMode = UI_MODE_NIGHT_NO))).dark)
    }

    @Test
    fun `darkVariant still asks for a second entry`() {
        val fixture = checkNotNull(resolve(screenshot = bareScreenshot.copy(darkVariant = true)))

        assertTrue(fixture.darkVariant)
        assertEquals(false, fixture.dark, "the base entry stays light; the second one is the dark one")
    }

    @Test
    fun `a night Preview and darkVariant together are refused rather than resolved`() {
        val screenshot = bareScreenshot.copy(darkVariant = true)
        val preview = PreviewArgs(uiMode = UI_MODE_NIGHT_YES)

        assertNull(resolve(screenshot = screenshot, preview = preview))
        val message = checkNotNull(errorFrom(screenshot = screenshot, previews = listOf(preview)))
        assertTrue("darkVariant" in message && "uiMode" in message, "the message should name both sides: $message")
    }

    @Test
    fun `resolving without contradiction reports no error`() {
        assertNull(errorFrom(previews = listOf(PreviewArgs(name = "Primary", uiMode = UI_MODE_NIGHT_YES))))
    }

    // --- several previews on one function (@PreviewLightDark and friends) ---------------------

    @Test
    fun `each Preview becomes its own fixture`() {
        val fixtures =
            resolveAll(
                previews =
                    listOf(
                        PreviewArgs(name = "Light"),
                        PreviewArgs(name = "Dark", uiMode = PREVIEW_LIGHT_DARK_NIGHT),
                    ),
            )

        assertEquals(2, fixtures.size)
        assertEquals(listOf("SampleFixture - Light", "SampleFixture - Dark"), fixtures.map { it.name })
        assertEquals(listOf(false, true), fixtures.map { it.dark })
    }

    @Test
    fun `PreviewLightDark's night value is a bit field, not the bare constant`() {
        // @PreviewLightDark's dark half is 33 — UI_MODE_NIGHT_YES or UI_MODE_TYPE_NORMAL — so an equality
        // check against 32 would read it as light and record two identical goldens.
        assertEquals(33, PREVIEW_LIGHT_DARK_NIGHT)
        assertTrue(checkNotNull(resolve(preview = PreviewArgs(uiMode = PREVIEW_LIGHT_DARK_NIGHT))).dark)
    }

    @Test
    fun `the ViddikScreenshot name becomes the stem when there are several previews`() {
        val fixtures =
            resolveAll(
                screenshot = bareScreenshot.copy(name = "AppButton"),
                previews = listOf(PreviewArgs(name = "Light"), PreviewArgs(name = "Dark")),
            )

        assertEquals(listOf("AppButton - Light", "AppButton - Dark"), fixtures.map { it.name })
    }

    @Test
    fun `unnamed previews fall back to their index rather than colliding`() {
        val fixtures = resolveAll(previews = listOf(PreviewArgs(widthDp = 100), PreviewArgs(widthDp = 200)))

        assertEquals(listOf("SampleFixture - #0", "SampleFixture - #1"), fixtures.map { it.name })
        assertEquals(fixtures.map { it.name }.distinct().size, fixtures.size, "names must stay unique")
    }

    @Test
    fun `darkVariant across several previews is refused rather than doubling all of them`() {
        val previews = List(7) { PreviewArgs(name = "$it%", fontScale = 1f + it * 0.15f) }

        assertEquals(emptyList(), resolveAll(screenshot = bareScreenshot.copy(darkVariant = true), previews = previews))
        val message = checkNotNull(errorFrom(screenshot = bareScreenshot.copy(darkVariant = true), previews = previews))
        assertTrue("7" in message, "the message should say how many it would have doubled: $message")
    }

    // --- font scale ---------------------------------------------------------------------------

    @Test
    fun `fontScale is carried through`() {
        assertEquals(1.5f, checkNotNull(resolve(preview = PreviewArgs(fontScale = 1.5f))).fontScale)
        assertEquals(1f, checkNotNull(resolve()).fontScale, "no @Preview means no scaling")
    }

    // --- device specs -------------------------------------------------------------------------

    @Test
    fun `a spec device supplies the size when the Preview does not`() {
        val fixture = checkNotNull(resolve(preview = PreviewArgs(device = "spec:width=411dp,height=891dp")))

        assertEquals(411, fixture.width)
        assertEquals(891, fixture.height)
    }

    @Test
    fun `an explicit Preview size still beats the device spec`() {
        val fixture =
            checkNotNull(resolve(preview = PreviewArgs(widthDp = 320, device = "spec:width=411dp,height=891dp")))

        assertEquals(320, fixture.width)
        assertEquals(891, fixture.height, "only the field that was given should win")
    }

    @Test
    fun `the parts of a spec viddik cannot honour are warned about, not silently dropped`() {
        val warnings = warningsFrom(PreviewArgs(device = "spec:width=411dp,height=891dp,dpi=420,orientation=landscape"))

        val message = checkNotNull(warnings.singleOrNull()) { "expected exactly one warning, got $warnings" }
        assertTrue("dpi" in message && "orientation" in message, "the warning should name both: $message")
    }

    @Test
    fun `a named device is warned about and ignored`() {
        val warnings = warningsFrom(PreviewArgs(device = "id:pixel_5"))

        assertTrue(warnings.isNotEmpty())
        assertEquals(DEFAULT_WIDTH, checkNotNull(resolve(preview = PreviewArgs(device = "id:pixel_5"))).width)
    }

    @Test
    fun `a spec viddik can honour in full produces no warning`() {
        assertEquals(emptyList(), warningsFrom(PreviewArgs(device = "spec:width=411dp,height=891dp")))
    }

    @Test
    fun `a malformed spec falls back to the default instead of throwing`() {
        val fixture = checkNotNull(resolve(preview = PreviewArgs(device = "spec:width=wide,height=")))

        assertEquals(DEFAULT_WIDTH, fixture.width)
        assertEquals(AUTO_HEIGHT, fixture.height)
    }
}
