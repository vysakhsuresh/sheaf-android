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

// One module of app code, like Deja and Abhyas. LayerLink split out `:core` because two apps
// shared a screen-capture engine; Sheaf has no sibling to share with, so the boundaries that
// matter (pdf/, ops/, files/, jobs/, ui/) are enforced by package discipline instead of by Gradle.
// Split `:core-pdf` out the day build times actually hurt, not before.
//
// `:baselineprofile` is not a crack in that. It holds no app code and nothing depends on it at
// runtime: it drives the app on a device and writes one text file that `:app` ships. It is its
// own module rather than an `androidTest` source set in `:app` because it has to start, kill and
// restart the app as a separate process, which is what `com.android.test` is for.
rootProject.name = "Sheaf"
include(":app")
include(":baselineprofile")
