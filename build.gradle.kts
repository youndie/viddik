plugins {
    alias(wip.plugins.composeMultiplatform) apply false
    alias(wip.plugins.composeCompiler) apply false
    alias(wip.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(wip.plugins.ksp) apply false
    alias(libs.plugins.dokka)
}


dependencies {
    dokka(projects.viddikAnnotations)
    dokka(projects.viddikProcessor)
    dokka(projects.viddikTestingCore)
    dokka(projects.viddikShowroom)
    dokka(projects.viddikGradlePlugin)
}
