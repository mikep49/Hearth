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
        // Termux's terminal emulator is only published on JitPack; nothing else may resolve from there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}
rootProject.name = "Hearth"
include(":app")
