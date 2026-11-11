package io.github.youndie.viddik.android

import io.github.youndie.viddik.annotations.ViddikComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViddikMatrixFilesTest {
    private fun named(
        group: String,
        name: String,
    ) = ViddikComponent(name = name, group = group, content = {})

    // Names the JVM path writes today — see viddik-testing-core/src/jvmTest/snapshots/. If one of
    // these changes, ViddikEngine.fileNameFor changed too, and so must matrixFileNameFor.
    @Test
    fun `a capture is named like the JVM golden of the same fixture`() {
        assertEquals("Demo_Simple_Button.png", matrixFileNameFor(named("Demo", "Simple Button")))
        assertEquals("Demo_Light_dark_-_Dark.png", matrixFileNameFor(named("Demo", "Light dark - Dark")))
        assertEquals(
            "Demo_Parameterized_button_-_First__0_Dark.png",
            matrixFileNameFor(named("Demo", "Parameterized button - First #0 Dark")),
        )
    }

    @Test
    fun `a long name is cut to what a Windows pull survives, and stays unique`() {
        val stem = "Screens"
        val tail = "PaymentSummaryContent - ErrorFullScreen with a very long state description"
        val one = matrixFileNameFor(named(stem, "$tail A"))
        val other = matrixFileNameFor(named(stem, "$tail B"))

        assertEquals(MAX_FILE_NAME, one.length)
        assertTrue(one.startsWith("Screens_PaymentSummaryContent"), one)
        assertTrue(one.endsWith(".png"), one)
        assertNotEquals(one, other)
    }

    @Test
    fun `the expected list carries the fixture's real name`() {
        assertEquals("Demo_Simple_Button.png\tDemo - Simple Button", expectedLine(named("Demo", "Simple Button")))
        assertEquals("A_b_c.png\tA - b c", expectedLine(named("A", "b\tc")))
    }

    @Test
    fun `a filter selects by group and name, and matching nothing is an error`() {
        val all = listOf(named("Screens", "List - Scrolled"), named("Screens", "List"), named("Auth", "Login"))

        assertEquals(all, selected(all, null))
        assertEquals(all.take(1), selected(all, "list - SCROLLED"))
        assertEquals(all.take(2), selected(all, "Screens"))
        assertFailsWith<IllegalArgumentException> { selected(all, "Nothing") }
    }

    @Test
    fun `a filter with a space arrives encoded, because the command line it travels through is unquoted`() {
        val all = listOf(named("Surfaces", "Bottom sheet"), named("Surfaces", "Bottom bar"))

        assertEquals(all.take(1), selected(all, "Surfaces+-+Bottom+sheet"))
        assertEquals(all.take(1), selected(all, "Bottom%20sheet"))
    }

    @Test
    fun `a failure's reason stays on its one line`() {
        assertEquals("A_b.png\tline one line two", failureLine(named("A", "b"), "line one\n  line two"))
    }

    @Test
    fun `the device file is valid JSON with escaped strings`() {
        assertEquals(
            "{\"sdkInt\":34,\"release\":\"14\",\"model\":\"sdk \\\"gphone\\\"\",\"density\":2.625,\"widthPx\":1080,\"heightPx\":2400}\n",
            deviceJson(34, "14", "sdk \"gphone\"", 2.625f, 1080, 2400),
        )
    }
}
