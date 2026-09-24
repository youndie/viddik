package io.github.youndie.viddik.samples.goldens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// No text, so the golden does not depend on the host's system font.
@Composable
public fun Badge(color: Color) {
    Box(Modifier.size(24.dp).background(color))
}
