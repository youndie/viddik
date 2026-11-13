package io.github.youndie.viddik.demo

import io.github.youndie.viddik.generated.GeneratedViddikRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `captureAtMillis` reaches the registry the processor writes — the half a processor unit test cannot
 * see, and the one that decides whether the annotation does anything at all. `GeneratedToleranceTest`
 * is the same check for `tolerancePercent`.
 */
class GeneratedCaptureTimeTest {
    private val components = GeneratedViddikRegistry.components

    @Test
    fun `a fixture's capture time reaches the registry`() {
        val spinner =
            checkNotNull(components.firstOrNull { it.name == "Endless spinner" }) {
                "no fixture named \"Endless spinner\" in ${components.map { it.name }}"
            }
        assertEquals(SPINNER_CAPTURE_AT_MS, spinner.captureAtMillis)
    }

    @Test
    fun `every other fixture is still captured once idle`() {
        val timed = components.filter { it.captureAtMillis != null }.map { it.name }
        assertTrue(timed == listOf("Endless spinner"), "only the spinner asks for a moment: $timed")
    }
}
