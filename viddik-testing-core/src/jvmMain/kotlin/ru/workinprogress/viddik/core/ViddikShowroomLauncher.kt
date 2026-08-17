package ru.workinprogress.viddik.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ru.workinprogress.viddik.ViddikShowroom
import ru.workinprogress.viddik.annotations.ViddikComponent

private const val REGISTRY_CLASS = "ru.workinprogress.viddik.generated.GeneratedViddikRegistry"

/**
 * Opens [ViddikShowroom] over whatever registry the KSP processor generated in the module this runs
 * against — the browser half of viddik, without every consumer hand-writing the same `fun main()`.
 *
 * The registry is loaded reflectively on purpose: it is generated into the *consumer's* test source
 * set, so this module can't see the class at compile time, only on the runtime classpath the
 * `viddikShowroom` Gradle task assembles.
 */
object ViddikShowroomLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val components = loadComponents()

        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "viddik showroom — ${components.size} components",
                state = rememberWindowState(size = DpSize(520.dp, 900.dp)),
            ) {
                MaterialTheme {
                    ViddikShowroom(components)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadComponents(): List<ViddikComponent> {
        val registry =
            try {
                Class.forName(REGISTRY_CLASS)
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "$REGISTRY_CLASS is not on the classpath: this module has no @ViddikScreenshot " +
                        "fixtures yet, or KSP hasn't run over its test source set.",
                    e,
                )
            }
        val instance = registry.getField("INSTANCE").get(null)
        return registry.getMethod("getComponents").invoke(instance) as List<ViddikComponent>
    }
}
