package io.github.youndie.viddik.samples.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.youndie.viddik.annotations.ViddikScreenshot

// In the test source set, where a module without `showroomTargets` keeps its fixtures.
@ViddikScreenshot(name = "Accent", group = "Badges")
@Composable
fun AccentBadge() {
    Badge(Color(0xFF3D5AFE))
}
