package ru.workinprogress.viddik.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UI_MODE_NIGHT_YES = 0x20
private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_TYPE_TELEVISION = 0x04

/** The arguments KSP hands over for a bare `@ViddikScreenshot` — every default substituted. */
private val bareScreenshot =
    ScreenshotArgs(name = "", group = "", width = UNSPECIFIED, height = UNSPECIFIED, darkVariant = false)

private fun resolve(
    functionName: String = "SampleFixture",
    screenshot: ScreenshotArgs = bareScreenshot,
    preview: PreviewArgs? = null,
): FixtureMetadata? = resolveFixture(functionName, screenshot, preview) { }

private fun errorFrom(
    screenshot: ScreenshotArgs = bareScreenshot,
    preview: PreviewArgs? = null,
): String? {
    var message: String? = null
    resolveFixture("SampleFixture", screenshot, preview) { message = it }
    return message
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
        assertTrue(checkNotNull(resolve(preview = PreviewArgs(uiMode = UI_MODE_NIGHT_YES or UI_MODE_TYPE_TELEVISION))).dark)
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
        val message = checkNotNull(errorFrom(screenshot = screenshot, preview = preview))
        assertTrue("darkVariant" in message && "uiMode" in message, "the message should name both sides: $message")
    }

    @Test
    fun `resolving without contradiction reports no error`() {
        assertNull(errorFrom(preview = PreviewArgs(name = "Primary", uiMode = UI_MODE_NIGHT_YES)))
    }
}
