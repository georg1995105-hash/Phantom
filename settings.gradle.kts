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
        google()
        // GitHub runners have repeatedly received artifact-level 404s from
        // repo.maven.apache.org after metadata was already selected there.
        // Gradle keeps artifacts pinned to the repository that supplied the
        // metadata, so a later mirror is not a real fallback. Use the existing
        // Maven Central mirror first and keep Central itself as a fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "PHANTOM"
include(":app")
