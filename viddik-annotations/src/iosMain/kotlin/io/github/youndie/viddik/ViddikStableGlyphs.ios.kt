package io.github.youndie.viddik

import androidx.compose.ui.Modifier

// Same reasoning as the Android half: there is no viddik capture on iOS, LocalViddikCapture is never
// true there, and the point of the perspective term is to steer the *capture* away from the host font
// backend. Returning the receiver leaves the app drawing its own text its own way.
internal actual fun Modifier.glyphPerspectiveNudge(): Modifier = this
