package ru.workinprogress.viddik.gradle

import java.util.Properties

/**
 * Versions baked into the plugin jar at build time (`generateViddikVersionResource`).
 *
 * The viddik version defaults to the plugin's own: the processor generates code against the engine's
 * API, so a consumer mixing versions gets a link error at test runtime rather than anything readable.
 * The JUnit versions are the ones `viddik-testing-core` exposes its API against — the engine and the
 * launcher have to agree on a platform version, and the consumer has no reason to know which.
 */
internal object ViddikPluginVersions {
    private const val RESOURCE = "/viddik-plugin.properties"

    private val properties: Properties by lazy {
        val stream =
            ViddikPluginVersions::class.java.getResourceAsStream(RESOURCE)
                ?: error("$RESOURCE is missing from the viddik plugin jar")
        stream.use { Properties().apply { load(it) } }
    }

    val viddik: String get() = property("viddik.version")
    val junitJupiter: String get() = property("junit.jupiter.version")
    val junitPlatform: String get() = property("junit.platform.version")

    private fun property(name: String): String = properties.getProperty(name) ?: error("$name is missing from $RESOURCE")
}
