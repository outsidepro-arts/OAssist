plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ru.outsideproarts.oassist.core.device"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // androidx.annotation:1.3.0 из транзитивных deps Shizuku нет ни в кэше,
    // ни в доступном зеркале — исключаем (аннотации нужны только на компиляции SDK).
    implementation(libs.shizuku.api) { exclude(group = "androidx.annotation") }
    implementation(libs.shizuku.provider) { exclude(group = "androidx.annotation") }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
