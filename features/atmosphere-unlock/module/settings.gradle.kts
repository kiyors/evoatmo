// Module Gradle project root — settings live here; the :app module carries the hook code.
rootProject.name = "evoatmo-atmosphere-module"
include(":app")

// Repositories: google() + mavenCentral() first; the Xposed repo serves de.robv.android.xposed:api:82.
// (docs/setup.md §4.2 pins this shape from the YukiHookAPI 1.2.1-era docs.)
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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") } // required as an Xposed module
        }
    }
}
