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

// One module, like Deja and Abhyas. LayerLink split out `:core` because two apps shared a
// screen-capture engine; Sheaf has no sibling to share with, so the boundaries that matter
// (pdf/, ops/, files/, jobs/, ui/) are enforced by package discipline instead of by Gradle.
// Split `:core-pdf` out the day build times actually hurt, not before.
rootProject.name = "Sheaf"
include(":app")
