pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aurora-music-engine"

include(":aurora-core")
include(":aurora-provider-ytmusic")
include(":aurora-transport-progressive")
include(":aurora-player-android")
// Future phases will uncomment these modules:
include(":aurora-transport-sabr")
// include(":aurora-storage")
// include(":aurora-bridge-rn")
