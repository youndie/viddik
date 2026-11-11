plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaJvm)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

// THE HOST HALF OF THE ANDROID MATRIX. It reads what `viddik-android-capture` wrote on each emulator
// after AGP has pulled it back, lines the API levels up against each other and writes an HTML page.
// The `io.github.youndie.viddik.android-matrix` plugin runs it through a JavaExec, the way
// `viddikShowroom` runs the desktop browser, so none of this is on the Gradle plugin's own classpath.
//
// It compares with viddik-testing-core's ImageDiffer — the same differ and the same default thresholds
// `viddikVerify` uses — and touches nothing else in that module.

dependencies {
    implementation(projects.viddikTestingCore)
}
