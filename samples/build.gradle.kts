// Every plugin the samples use, versioned once here and applied without a version in the modules.
// Gradle refuses a versioned request for a plugin already on the build classpath ("already on the
// classpath with an unknown version"), which is what two modules asking for Kotlin at a version look
// like from inside a build.
plugins {
    alias(wip.plugins.kotlinMultiplatform) apply false
    alias(wip.plugins.androidApplication) apply false
    alias(wip.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(wip.plugins.composeMultiplatform) apply false
    alias(wip.plugins.composeCompiler) apply false
    alias(wip.plugins.ksp) apply false
}
