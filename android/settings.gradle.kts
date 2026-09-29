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
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Pad6-Android"

// The pure-Kotlin core (audio processing, P-6 file formats) builds without
// the Android SDK or Google's Maven repository; -Ppyp6.coreOnly=true leaves
// the app module out so it can be built and tested on its own.
include(":core")
if (providers.gradleProperty("pyp6.coreOnly").orNull != "true") {
    include(":app")
}
