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
        // Shizuku AARs are vendored in this repository; resolve those from the local
        // Maven repository while keeping standard repositories available for others.
        maven { url = uri("vendor/maven") }
        google()
        mavenCentral()
    }
}

rootProject.name = "OAssist"

include(":app")
include(":core:model")
include(":core:a11y")
include(":core:ui")
include(":core:data")
include(":core:net")
include(":core:provider")
include(":core:agent")
include(":core:device")
include(":core:speech")
include(":feature:chat")
include(":feature:assistant")
include(":feature:share")
include(":feature:settings")
