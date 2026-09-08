package io.github.youndie.viddik.showroom

import androidx.compose.runtime.Composable

// `ViddikShowroomUIViewController` is presented on its own, not pushed onto a UINavigationController,
// so there is no interactive-pop gesture here to answer. A host that does push it onto a stack gets
// that stack's back, which belongs to the host and not to this.
@Composable
internal actual fun ViddikShowroomBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
): Unit = Unit
