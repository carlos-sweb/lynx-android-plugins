pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lynx-android-plugins"
include(":android-core")
include(":android-battery")
include(":android-camera")
include(":android-device")
include(":android-geolocation")
include(":android-network")
include(":android-vibration")
include(":android-all")
include(":demo-host")
