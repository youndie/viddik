package io.github.youndie.viddik.demo

import io.github.youndie.viddik.annotations.ViddikComponent
import io.github.youndie.viddik.core.ViddikEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `viddik.filter` selection, exercised through `dynamicTests` rather than through the matcher
 * alone — what matters is which fixtures a run ends up with, and the empty-match case has to fail
 * instead of quietly reporting a green run of nothing.
 *
 * These never capture anything: a `DynamicTest` only runs its body when executed, and this only ever
 * counts them.
 */
class ViddikFilterTest {
    private val components =
        listOf(
            component(group = "Buttons", name = "Primary"),
            component(group = "Buttons", name = "Primary Dark"),
            component(group = "Widgets", name = "Card"),
        )

    @AfterEach
    fun clearFilter() {
        System.clearProperty(FILTER_PROPERTY)
    }

    @Test
    fun `without a filter every component becomes a test`() {
        assertEquals(3, ViddikEngine.dynamicTests(components).size)
    }

    @Test
    fun `a bare substring matches without naming the group`() {
        System.setProperty(FILTER_PROPERTY, "Card")

        assertEquals(listOf("Widgets - Card"), ViddikEngine.dynamicTests(components).map { it.displayName })
    }

    @Test
    fun `matching is case-insensitive`() {
        System.setProperty(FILTER_PROPERTY, "widgets - card")

        assertEquals(listOf("Widgets - Card"), ViddikEngine.dynamicTests(components).map { it.displayName })
    }

    @Test
    fun `a wildcard spans the group and the name`() {
        System.setProperty(FILTER_PROPERTY, "Buttons*Dark")

        assertEquals(listOf("Buttons - Primary Dark"), ViddikEngine.dynamicTests(components).map { it.displayName })
    }

    @Test
    fun `a substring can select several components`() {
        System.setProperty(FILTER_PROPERTY, "Primary")

        assertEquals(2, ViddikEngine.dynamicTests(components).size)
    }

    @Test
    fun `a filter that matches nothing fails instead of running nothing`() {
        System.setProperty(FILTER_PROPERTY, "Chip")

        val failure = assertThrows<IllegalStateException> { ViddikEngine.dynamicTests(components) }

        // The message has to say what *is* there, or a typo looks like a missing fixture.
        assertTrue(failure.message.orEmpty().contains("Widgets - Card"), failure.message)
    }

    @Test
    fun `regex metacharacters in a filter are matched literally`() {
        System.setProperty(FILTER_PROPERTY, "Buttons.")

        assertThrows<IllegalStateException> { ViddikEngine.dynamicTests(components) }
    }

    @Test
    fun `a blank filter is treated as no filter`() {
        System.setProperty(FILTER_PROPERTY, "  ")

        assertEquals(3, ViddikEngine.dynamicTests(components).size)
    }

    private fun component(
        group: String,
        name: String,
    ) = ViddikComponent(name = name, group = group, content = {})

    private companion object {
        const val FILTER_PROPERTY = "viddik.filter"
    }
}
