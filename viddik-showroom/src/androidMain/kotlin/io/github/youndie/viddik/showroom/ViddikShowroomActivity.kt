package io.github.youndie.viddik.showroom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.youndie.viddik.annotations.ViddikComponent

/**
 * The Android half of the showroom: an activity over the registry KSP generated for this module.
 *
 * A consumer writes the subclass and the manifest entry, and nothing else:
 *
 * ```kotlin
 * class ShowroomActivity : ViddikShowroomActivity() {
 *     override val components = GeneratedViddikRegistry.components
 * }
 * ```
 *
 * The registry is handed over rather than looked up. The desktop launcher can afford to load it
 * reflectively — it runs against a classpath a Gradle task assembled — but here it is an ordinary
 * reference in the consumer's own module, which means a fixture that stopped compiling is a build
 * error instead of an empty list at launch.
 *
 * That registry only exists for Android if it was generated from `commonMain`, which is what
 * `viddik { showroomTargets = true }` sets up. Generated into a test source set, as it is by default,
 * it is never compiled into an app at all.
 */
public abstract class ViddikShowroomActivity : ComponentActivity() {
    /** The components to browse — `GeneratedViddikRegistry.components`, in the normal case. */
    protected abstract val components: List<ViddikComponent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The showroom pads itself with `WindowInsets.safeDrawing`, so it is already drawing correctly
        // under the status and navigation bars; this is what lets it paint behind them too.
        enableEdgeToEdge()
        setContent { ViddikShowroomApp(components) }
    }
}
