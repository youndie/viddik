package io.github.youndie.viddik.showroom

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.youndie.viddik.ViddikShowroom
import io.github.youndie.viddik.annotations.ViddikComponent
import io.github.youndie.viddik.rememberViddikShowroomState

/**
 * The showroom as a whole screen: themed chrome, remembered state, and whatever the platform provides
 * for going back.
 *
 * [ViddikShowroom] itself is the bare component browser and imposes no theme, which is what the
 * capture engine wants — a fixture brings its own. A running app wants the opposite, so this wraps it
 * in a default [MaterialTheme] and is what the Android and iOS entry points in this module put on
 * screen. Host it directly if you want the showroom inside your own theme or your own navigation.
 */
@Composable
public fun ViddikShowroomApp(
    components: List<ViddikComponent>,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        val state = rememberViddikShowroomState()
        ViddikShowroomBackHandler(enabled = state.selected != null) { state.selected = null }
        ViddikShowroom(components = components, modifier = modifier, state = state)
    }
}
