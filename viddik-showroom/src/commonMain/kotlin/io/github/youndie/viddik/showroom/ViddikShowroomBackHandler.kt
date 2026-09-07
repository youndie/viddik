package io.github.youndie.viddik.showroom

import androidx.compose.runtime.Composable

/**
 * Answers the platform's own "go back" while a component's detail view is open.
 *
 * Android is the platform that needs it: without it, backing out of a component closes the host
 * activity rather than returning to the list. The desktop and iOS halves do nothing — a window has no
 * back gesture, and a bare `ComposeUIViewController` is not on a navigation stack that could offer
 * one. The `←` row inside [io.github.youndie.viddik.ViddikShowroom] is the affordance everywhere; this
 * is the additional one, where the system has an opinion.
 *
 * It lives here rather than in `viddik-annotations` because answering it on Android means depending on
 * `androidx.activity`, and that module publishes iOS klibs which must not see AndroidX's copies of
 * `lifecycle` and `savedstate` beside Compose Multiplatform's forks of them.
 */
@Composable
internal expect fun ViddikShowroomBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
