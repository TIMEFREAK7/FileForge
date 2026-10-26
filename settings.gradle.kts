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

rootProject.name = "FileForge"

include(":app")
include(":engine-media")
// Deliberately requests INTERNET so CI can prove the S1 check fails on it.
include(":tools:permission-canary")
