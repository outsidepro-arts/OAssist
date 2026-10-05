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

rootProject.name = "OAssist"

include(":app")
include(":core:model")
include(":core:a11y")
include(":core:ui")
include(":core:data")
include(":core:net")
include(":core:provider")
include(":core:agent")
include(":core:speech")
include(":feature:chat")
include(":feature:assistant")
include(":feature:share")
include(":feature:settings")
