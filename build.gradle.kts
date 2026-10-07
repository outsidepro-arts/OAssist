plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.setFork(false)
    }
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        forkEvery = 0
    }
}
