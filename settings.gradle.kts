pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.0"
        id("com.android.library") version "8.5.0"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("dev.rikka.tools.refine") version "4.4.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Xposed API (api:82) 不在 mavenCentral, 需要这个 bintray-mirror
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "HanFengAdBlock"
include(":app")

include(":shizuku-fork:aidl")
include(":shizuku-fork:shared")
include(":shizuku-fork:common")
include(":shizuku-fork:api")
include(":shizuku-fork:provider")
include(":shizuku-fork:server-shared")
include(":shizuku-fork:starter")
include(":shizuku-fork:server")
include(":shizuku-fork:manager")
include(":shizuku-fork:rish")
