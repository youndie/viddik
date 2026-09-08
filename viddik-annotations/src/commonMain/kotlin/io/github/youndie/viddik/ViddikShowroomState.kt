package io.github.youndie.viddik

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.youndie.viddik.annotations.ViddikComponent

/**
 * What the showroom is currently showing: the search query, and the component whose detail view is
 * open (`null` while the list is).
 *
 * Hoisted out of [ViddikShowroom] so a host can both read and drive it. The reason is the Android
 * system back gesture: it has to close the detail view rather than the activity, and the only thing
 * that can answer it is an `androidx.activity.compose.BackHandler` in the host. viddik-annotations
 * deliberately does not depend on AndroidX to provide that itself — activity 1.11 brings the KMP
 * `androidx.lifecycle`/`savedstate` artifacts, whose klibs collide by `unique_name` with the
 * `org.jetbrains.androidx` forks Compose Multiplatform ships, and this module has native targets.
 *
 * ```
 * val state = rememberViddikShowroomState()
 * BackHandler(enabled = state.selected != null) { state.selected = null }
 * ViddikShowroom(components, state = state)
 * ```
 *
 * Passing it is optional — [ViddikShowroom] remembers one of its own — so every existing call site
 * keeps working unchanged.
 */
public class ViddikShowroomState {
    /** The search text. Empty means "show everything". */
    public var query: String by mutableStateOf("")

    /** The component being shown full-screen, or `null` for the list. */
    public var selected: ViddikComponent? by mutableStateOf(null)
}

@Composable
public fun rememberViddikShowroomState(): ViddikShowroomState = remember { ViddikShowroomState() }
