plugins {
    `maven-publish`
}

version = findProperty("viddik.version")?.toString() ?: "0.0.1"

plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}

publishing {
    repositories {
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = findProperty("REPOSILITE_USER")?.toString()
                password = findProperty("REPOSILITE_SECRET")?.toString()
            }
        }
    }
}

afterEvaluate {
    findProperty("VERSION")?.toString()?.let { publishVersion ->
        publishing.publications.withType<MavenPublication>().configureEach {
            version = publishVersion
        }
    }
}
