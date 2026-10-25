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
        // One artifact only: de.robv.android.xposed:api:82 (compile-time XposedBridge stub).
        // The official https://api.xposed.info/ repo is dead as of 2026-10-25 (its DNS resolves
        // to an unrelated IP and connections time out); mvnrepository lists Appodeal's public
        // Artifactory as the live host for this exact artifact (api-82.jar/pom).
        maven("https://artifactory.appodeal.com/appodeal-public/") {
            content { includeGroup("de.robv.android.xposed") }
        }
        // JitPack serves com.github.tiann:FreeReflection, a YukiHookAPI 1.2.1 transitive dep.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.tiann") }
        }
    }
}
