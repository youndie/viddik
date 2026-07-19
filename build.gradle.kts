plugins {
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dokka)
}

version = findProperty("viddik.version").toString()

dependencies {
    dokka(projects.viddikAnnotations)
    dokka(projects.viddikProcessor)
    dokka(projects.viddikTestingCore)
}
