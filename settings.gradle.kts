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
        // Termux terminal-emulator (PTY/forkpty) is published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "re-adb"
include(":app")
