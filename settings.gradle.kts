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
        // M5.1: вендоренные aar Shizuku (vendor/maven). Файловый репозиторий первым —
        // сети не требует. Прямой Maven Central режется прокси (403), а его
        // Google-зеркало рвёт TLS-хендшейк из Java (проверено 2026-10-05), поэтому
        // новых внешних зависимостей не добавляем — только вендоренные.
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
