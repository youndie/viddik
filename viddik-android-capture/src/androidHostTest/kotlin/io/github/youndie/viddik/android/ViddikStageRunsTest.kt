package io.github.youndie.viddik.android

import io.github.youndie.viddik.annotations.ViddikComponent
import kotlin.test.Test
import kotlin.test.assertEquals

class ViddikStageRunsTest {
    private fun fixture(captureAtMillis: Long? = null) =
        ViddikComponent(name = "f", group = "g", captureAtMillis = captureAtMillis, content = {})

    private fun runs(components: List<ViddikComponent>): List<IntRange> =
        generateSequence(0) { stageRunEnd(components, it).takeIf { end -> end < components.size } }
            .map { it until stageRunEnd(components, it) }
            .toList()

    @Test
    fun `fixtures captured once idle share one stage`() {
        assertEquals(listOf(0 until 3), runs(List(3) { fixture() }))
    }

    @Test
    fun `a fixture captured at a moment gets a stage of its own, wherever it is`() {
        val components = listOf(fixture(), fixture(), fixture(500), fixture(), fixture(500), fixture(500))

        assertEquals(listOf(0 until 2, 2 until 3, 3 until 4, 4 until 5, 5 until 6), runs(components))
    }

    @Test
    fun `a run that restarts mid-way after a failure ends where it would have`() {
        // captureAll starts a fresh activity at the fixture after one that threw.
        val components = listOf(fixture(), fixture(), fixture(), fixture(0))

        assertEquals(3, stageRunEnd(components, 1))
        assertEquals(4, stageRunEnd(components, 3))
    }
}
