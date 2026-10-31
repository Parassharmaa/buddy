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
rootProject.name = "Buddy"
include(
    ":app",
    ":core",
    ":adb",
    ":pairing-native",
    ":shell-api",
    ":shell-helper",
    ":platform-api",
    ":phone",
    ":storage",
    ":designsystem",
    ":fixture",
    ":browser"
)
