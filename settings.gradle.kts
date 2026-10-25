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
        google()
        mavenCentral()
        // Xposed API stubs — compileOnly only, never packaged.
        maven { url = uri("https://api.xposed.info/") }
        // libsu is published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "fuckMiGallery"
include(":app")
