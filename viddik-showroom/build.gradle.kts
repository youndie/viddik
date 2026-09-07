plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    android {
        namespace = "io.github.youndie.viddik.showroom"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: every entry point here takes a `List<ViddikComponent>`, so
            // a consumer cannot call one without that type being on their compile classpath.
            api(projects.viddikAnnotations)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }

        androidMain.dependencies {
            // ComponentActivity, setContent, BackHandler and enableEdgeToEdge — the four things an
            // Android host is. Confined to this source set: `androidx.activity` 1.11 drags in the KMP
            // `androidx.lifecycle` artifacts, whose klibs collide by `unique_name` with the
            // `org.jetbrains.androidx` forks Compose Multiplatform ships, and the iOS compilations in
            // this same module would see both.
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Same version-line split as in viddik-annotations, same one-line exception, same exit condition —
// see the block at the end of that module's build script for the whole story.
tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>()
    .matching { it.name.endsWith("KotlinMetadata") }
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }
