plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:32.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("com.android.tools.lint:lint-tests:32.0.0")
    testImplementation("com.android.tools.lint:lint-api:32.0.0")
    testImplementation("com.android.tools.lint:lint-checks:32.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}

tasks.test { systemProperty("oassist.root", rootProject.projectDir.absolutePath) }

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "ru.outsideproarts.oassist.lint.DiagnosticIssueRegistry"
}

kotlin { jvmToolchain(17) }

tasks.register<JavaExec>("inspectDiagnostics") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ru.outsideproarts.oassist.lint.DiagnosticInspectorKt"
}
