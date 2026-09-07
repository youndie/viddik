package io.github.youndie.viddik.showroom

import androidx.compose.runtime.Composable

// A desktop window has no back gesture to intercept.
@Composable
internal actual fun ViddikShowroomBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
): Unit = Unit
