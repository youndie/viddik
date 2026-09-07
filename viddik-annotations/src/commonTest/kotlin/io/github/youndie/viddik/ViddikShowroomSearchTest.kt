package io.github.youndie.viddik

import io.github.youndie.viddik.annotations.ViddikComponent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViddikShowroomSearchTest {
    private val primaryButton = component(group = "Widgets", name = "Button - Primary")

    @Test
    fun `empty query matches everything`() {
        assertTrue(matchesQuery(primaryButton, ""))
    }

    @Test
    fun `a query of nothing but whitespace is an empty query`() {
        // Otherwise a stray space typed into the field empties the list, which reads as "this module
        // has no components" rather than as "you have typed a space". Tabs and newlines too: a query
        // is often pasted, and a pasted one brings whatever was around it.
        assertTrue(matchesQuery(primaryButton, "   "))
        assertTrue(matchesQuery(primaryButton, "\t\n "))
    }

    @Test
    fun `tokens are separated by any whitespace and not only by spaces`() {
        assertTrue(matchesQuery(primaryButton, "wid\tprim"))
    }

    @Test
    fun `matches on the name`() {
        assertTrue(matchesQuery(primaryButton, "prim"))
    }

    @Test
    fun `matches on the group`() {
        assertTrue(matchesQuery(primaryButton, "widget"))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(matchesQuery(primaryButton, "BUTTON"))
    }

    @Test
    fun `every token has to match and they may come from group and name alike`() {
        assertTrue(matchesQuery(primaryButton, "wid prim"))
        assertFalse(matchesQuery(primaryButton, "wid secondary"))
    }

    @Test
    fun `tokens need not be in the order they appear in`() {
        assertTrue(matchesQuery(primaryButton, "primary widgets"))
    }

    @Test
    fun `a token that appears nowhere excludes the component`() {
        assertFalse(matchesQuery(primaryButton, "checkbox"))
    }

    @Test
    fun `a multi-word query is never matched as one phrase`() {
        // Tokens are looked for one at a time, so "Widgets Button" finds the component whose group is
        // Widgets and whose name is Button even though that exact string appears in neither. The same
        // rule is why no token can straddle the space this join inserts: there is no token with a
        // space in it to straddle it with.
        assertTrue(matchesQuery(primaryButton, "button widgets"))
        assertFalse(matchesQuery(primaryButton, "buttonwidgets"))
    }

    private fun component(
        group: String,
        name: String,
    ) = ViddikComponent(name = name, group = group) { }
}
