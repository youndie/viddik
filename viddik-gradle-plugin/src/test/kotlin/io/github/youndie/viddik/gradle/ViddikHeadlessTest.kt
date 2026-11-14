package io.github.youndie.viddik.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

/** What `viddik { headless = ... }` puts on the screenshot tasks' forked JVMs. */
class ViddikHeadlessTest {
    @Test
    fun `headless by default keeps AWT off the window system`() {
        assertEquals(
            mapOf("java.awt.headless" to "true", "apple.awt.UIElement" to "true"),
            windowlessSystemProperties(headless = true),
        )
    }

    @Test
    fun `turning headless off still keeps the forks out of the Dock`() {
        assertEquals(mapOf("apple.awt.UIElement" to "true"), windowlessSystemProperties(headless = false))
    }
}
