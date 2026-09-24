rootProject.name = "viddik-samples"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// A SEPARATE BUILD, ON PURPOSE. These modules consume viddik the way a stranger's project does —
// through `id("io.github.youndie.viddik")` and the published coordinates — and a module inside the
// main build cannot: a plugin cannot be applied to a sibling module of the build that produces it.
//
// `includeBuild("..")` substitutes both: the plugin marker resolves to `:viddik-gradle-plugin`, and
// every `io.github.youndie.viddik:viddik-*` dependency the plugin adds resolves to the project that
// publishes that coordinate. Nothing is published, nothing is stale, and a change to the plugin is
// visible here on the next build.
pluginManagement {
    includeBuild("..")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Where the shared catalog above is published. Filtered to the one group it serves, like
        // every other third-party repository in this build.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
    // The same catalogs the main build uses, so the samples cannot drift onto another Compose or
    // Kotlin version and quietly stop testing what they are here to test.
    //
    // TWO of them now. The compiler moved to `wip`, the catalog a sborka release publishes, and
    // this build does not get it the way the main one does: `sborka.settings` is not applied here —
    // this file has its own `pluginManagement` with `includeBuild("..")`, which is the whole point
    // of the separate build. So `wip` is taken as what it is, a published catalog.
    //
    // Its version is READ from the main catalog rather than written here. Typing 0.4.0.x twice is
    // exactly the drift this migration removed, and it would be the more dangerous kind: the
    // samples would keep building, on a different compiler, while claiming to test the same thing.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
        create("wip") {
            val pin = file("../gradle/libs.versions.toml").readLines()
                .first { it.trimStart().startsWith("sborka = ") }
                .substringAfter('"').substringBefore('"')
            from("io.github.youndie.sborka:catalog:$pin")
        }
    }
}

// Declared TWICE, and it has to be. `pluginManagement { includeBuild }` substitutes plugin markers
// only — it is how `id("io.github.youndie.viddik")` finds `:viddik-gradle-plugin`. Ordinary
// dependency substitution comes from the top-level one, and without it the `viddik-annotations` and
// `viddik-showroom` coordinates are looked up in a repository and reported missing at whatever
// version `gradle.properties` currently states.
includeBuild("..")

include(":fixtures")
include(":android-app")
include(":goldens-only")
