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

rootProject.name = "findip-shield-android"
include(":shield")

// The demo app (examples/shield-demo): built in CI, never published.
include(":shield-demo")
project(":shield-demo").projectDir = file("examples/shield-demo")
