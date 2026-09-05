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
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // google() and mavenCentral() with their content filters, the shared `wip` catalog, and the check
    // that this repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.2.0.29"
}

// `mavenLocal()` is gone. It arrived with the initial scaffold and nothing here ever needed it: no
// module resolves a `ru.workinprogress` coordinate, so all it could do is let a stale artifact in
// ~/.m2 win over the published one on somebody's machine and nowhere else.

include(":viddik-annotations")
include(":viddik-processor")
include(":viddik-testing-core")
include(":viddik-gradle-plugin")
