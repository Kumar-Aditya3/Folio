pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // Mihon's injekt fork, required by the vendored manga/extension runtime.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Folio"

include(":shared")
include(":androidApp")
include(":desktopApp")
