package io.github.youndie.viddik.samples.android

import io.github.youndie.viddik.generated.GeneratedViddikRegistry
import io.github.youndie.viddik.showroom.ViddikShowroomActivity

// The entire Android application. `ViddikShowroomActivity` brings the window, the theme, the
// edge-to-edge setup and the back gesture; the app brings the list.
class ShowroomActivity : ViddikShowroomActivity() {
    override val components = GeneratedViddikRegistry.components
}
