pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Google Home APIs SDK isn't on a public repo: it's downloaded by hand (see README → Google Home setup).
val homeSdk = providers.gradleProperty("homeSdk").orNull?.trim().orEmpty()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (homeSdk.isNotEmpty()) {
            // Either unzip the SDK into ./home-sdk (Maven layout) or into ~/.m2/repository.
            maven { url = uri("home-sdk") }
            mavenLocal()
        }
    }
}
rootProject.name = "GestureLauncher"
include(":app")
