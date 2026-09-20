package io.github.youndie.viddik.demo

import io.github.youndie.viddik.annotations.ViddikComponent
import io.github.youndie.viddik.core.CAPTURE_SESSION_TEST_NAME
import io.github.youndie.viddik.core.RECORD_SUMMARY_TEST_NAME
import io.github.youndie.viddik.core.ViddikEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val FILTER_PROPERTY = "viddik.filter"
private const val DESIGN_PARITY_PROPERTY = "viddik.designParity"

/**
 * Splitting the fixtures across forks (`viddik { shards = N }`).
 *
 * Two properties matter and neither is obvious from the code: every fixture lands in exactly one
 * shard, and a shard that ends up empty is normal rather than an error. The second is what a
 * filter's own loud failure has to be reconciled with — an over-narrow `--component` must still
 * fail, but only because it matched nothing in the *module*, not because it matched nothing in this
 * fork.
 */
class ViddikShardTest {
    private val components = (1..10).map { component(group = "G${it % 3}", name = "Fixture $it") }

    @AfterEach
    fun clearFilter() {
        System.clearProperty(FILTER_PROPERTY)
    }

    @Test
    fun `the shards together are the whole module, and disjoint`() {
        val shards = (0 until 4).map { shard -> selected(components, shard, shards = 4) }

        assertEquals(components.size, shards.sumOf { it.size })
        assertEquals(components.map { "${it.group} - ${it.name}" }.toSet(), shards.flatten().toSet())
        assertEquals(shards.flatten().size, shards.flatten().toSet().size, "a fixture appears in one shard only")
    }

    @Test
    fun `one shard is the whole module`() {
        assertEquals(components.size, selected(components, shard = 0, shards = 1).size)
    }

    @Test
    fun `more shards than fixtures leaves some of them empty, which is not an error`() {
        val shards = (0 until 16).map { shard -> selected(components, shard, shards = 16) }

        assertEquals(components.size, shards.sumOf { it.size })
        assertTrue(shards.any { it.isEmpty() }, "with 16 shards and 10 fixtures some shard has nothing to do")
    }

    @Test
    fun `a filter that matches one fixture leaves the other shards empty and quiet`() {
        System.setProperty(FILTER_PROPERTY, "Fixture 7")

        val shards = (0 until 4).map { shard -> selected(components, shard, shards = 4) }

        assertEquals(listOf("G1 - Fixture 7"), shards.flatten())
    }

    @Test
    fun `a filter that matches nothing fails in every shard`() {
        System.setProperty(FILTER_PROPERTY, "Nothing")

        (0 until 4).forEach { shard ->
            assertThrows<IllegalStateException>("shard $shard should refuse an empty filter") {
                ViddikEngine.dynamicTests(components, shard = shard, shards = 4)
            }
        }
    }

    @Test
    fun `design parity is not split across shards`() {
        // It clears one report directory and rewrites one summary, so the shards would delete each
        // other's work. Shard 0 measures the module; the rest contribute nothing, and an empty
        // shard has to be allowed for that to be possible at all.
        System.setProperty(DESIGN_PARITY_PROPERTY, "true")
        try {
            val first = ViddikEngine.dynamicTests(components, shard = 0, shards = 4)
            val rest = (1 until 4).map { ViddikEngine.dynamicTests(components, shard = it, shards = 4) }

            // One test per fixture plus the summary.
            assertEquals(components.size + 1, first.size)
            assertTrue(rest.all { it.isEmpty() }, "only shard 0 measures design parity")
        } finally {
            System.clearProperty(DESIGN_PARITY_PROPERTY)
        }
    }

    @Test
    fun `a shard outside the split is a mistake worth throwing for`() {
        assertThrows<IllegalArgumentException> { ViddikEngine.dynamicTests(components, shard = 4, shards = 4) }
        assertThrows<IllegalArgumentException> { ViddikEngine.dynamicTests(components, shard = 0, shards = 0) }
    }

    private fun selected(
        components: List<ViddikComponent>,
        shard: Int,
        shards: Int,
    ): List<String> =
        ViddikEngine
            .dynamicTests(components, shard = shard, shards = shards)
            .map { it.displayName }
            .filterNot { it == RECORD_SUMMARY_TEST_NAME || it == CAPTURE_SESSION_TEST_NAME }

    private fun component(
        group: String,
        name: String,
    ) = ViddikComponent(name = name, group = group, content = {})
}
