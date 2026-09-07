rootProject.name = "viddik"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
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
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // Both groups on purpose. The portfolio is moving to `io.github.youndie` and sborka
                // is already there — the plugin marker and the jar behind it are under the new one.
                // The old one is held by the library versions published before the move: they are
                // still on the server and resolve as before.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // google() and mavenCentral() with their content filters, the shared `wip` catalog, and the check
    // that this repository's `.editorconfig` is the one the rest of them use.
    id("io.github.youndie.sborka.settings") version "0.3.0.41"
}

// `mavenLocal()` is gone. It arrived with the initial scaffold and nothing here ever needed it: no
// module resolves a `ru.workinprogress` coordinate, so all it could do is let a stale artifact in
// ~/.m2 win over the published one on somebody's machine and nowhere else.

include(":viddik-annotations")
include(":viddik-processor")
include(":viddik-testing-core")
include(":viddik-showroom")
include(":viddik-gradle-plugin")
