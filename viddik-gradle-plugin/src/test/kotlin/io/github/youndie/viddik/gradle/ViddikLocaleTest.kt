package io.github.youndie.viddik.gradle

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What `viddik { locale = ... }` puts on the screenshot tasks' forked JVMs (#65). */
class ViddikLocaleTest {
    @Test
    fun `the default pins every field of the locale`() {
        assertEquals(
            mapOf("user.language" to "en", "user.script" to "", "user.country" to "US", "user.variant" to ""),
            localeSystemProperties("en-US"),
        )
    }

    @Test
    fun `a language alone still clears the host's country`() {
        assertEquals("", localeSystemProperties("de")["user.country"])
    }

    @Test
    fun `script and variant travel too`() {
        val properties = localeSystemProperties("sr-Latn-RS")

        assertEquals("sr", properties["user.language"])
        assertEquals("Latn", properties["user.script"])
        assertEquals("RS", properties["user.country"])
    }

    @Test
    fun `something that is not a language tag fails the build`() {
        assertFailsWith<GradleException> { localeSystemProperties("not a locale") }
    }
}
