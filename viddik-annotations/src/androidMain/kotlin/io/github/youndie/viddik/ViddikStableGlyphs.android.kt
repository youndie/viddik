package io.github.youndie.viddik

import androidx.compose.ui.Modifier

// There is no viddik capture on Android — LocalViddikCapture is never true there — so this is only
// ever reached if someone provides that local by hand. Returning the receiver keeps the app's own
// rendering untouched rather than quietly drawing its text through a different rasterizer.
internal actual fun Modifier.glyphPerspectiveNudge(): Modifier = this
