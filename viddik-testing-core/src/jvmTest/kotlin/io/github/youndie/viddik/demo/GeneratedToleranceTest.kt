package io.github.youndie.viddik.demo

import io.github.youndie.viddik.generated.GeneratedViddikRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one place the generated registry itself is read back.
 *
 * `tolerancePercent` is spliced into the `ViddikComponent(...)` calls the processor writes, and there
 * are three of those call sites — the static one, the parameterized one, and the dark copy of the
 * parameterized one. A processor unit test proves the number was *resolved*; only reading the registry
 * proves it was *emitted*, which is the half that decides whether the annotation does anything at all.
 */
class GeneratedToleranceTest {
    private val components = GeneratedViddikRegistry.components

    @Test
    fun `a static fixture's tolerance reaches the registry`() {
        assertEquals(0.2, componentNamed("Simple Button").tolerancePercent)
    }

    @Test
    fun `every entry a parameterized fixture expands to carries it, dark copies included`() {
        val expanded = components.filter { it.name.startsWith("Parameterized button") }

        assertEquals(6, expanded.size, "three provider values, light and dark: ${expanded.map { it.name }}")
        assertTrue(expanded.any { it.name.endsWith("Dark") }, "the dark copies should be there to test")
        assertTrue(
            expanded.all { it.tolerancePercent == 0.2 },
            "each expanded entry should carry it: ${expanded.map { it.name to it.tolerancePercent }}",
        )
    }

    @Test
    fun `a fixture that states nothing gets no tolerance rather than a copy of the default`() {
        // null is what leaves the run's own threshold in charge; a number baked in here would pin the
        // fixture to whatever DEFAULT_TOLERANCE_PERCENT happened to be on the day it was generated.
        assertNull(componentNamed("Simple Text").tolerancePercent)
    }

    private fun componentNamed(name: String) =
        checkNotNull(components.firstOrNull { it.name == name }) {
            "no fixture named \"$name\" in ${components.map { it.name }}"
        }
}
