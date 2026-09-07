// Every plugin the samples use, versioned once here and applied without a version in the modules.
// Gradle refuses a versioned request for a plugin already on the build classpath ("already on the
// classpath with an unknown version"), which is what two modules asking for Kotlin at a version look
// like from inside a build.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
