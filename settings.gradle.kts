pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Gradle resolves metadata and artifacts from the same repository.
        // Central repeatedly failed to deliver this lint dependency in CI (#688).
        // Route only this exact version to our existing Central mirror.
        google {
            content { excludeVersion("commons-codec", "commons-codec", "1.10") }
        }
        mavenCentral {
            content { excludeVersion("commons-codec", "commons-codec", "1.10") }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "PHANTOM"
include(":app")
