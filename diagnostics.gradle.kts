val diagnosticCoverage = mapOf(
    ":app" to "composition and lifecycle",
    ":core:model" to "event and context contracts",
    ":core:a11y" to "inherits interaction boundaries",
    ":core:ui" to "inherits interaction boundaries",
    ":core:data" to "storage boundary",
    ":core:net" to "transport boundary",
    ":core:provider" to "provider boundary",
    ":core:agent" to "agent and tool boundaries",
    ":core:device" to "privileged execution boundary",
    ":core:speech" to "speech boundaries",
    ":feature:chat" to "inherits agent boundary",
    ":feature:assistant" to "inherits agent boundary",
    ":feature:share" to "inherits agent boundary",
    ":feature:settings" to "inherits provider boundary",
    ":diagnostic-lint" to "build-time verification and offline inspector",
)
val diagnosticModules = diagnosticCoverage.keys - ":diagnostic-lint"

subprojects {
    listOf("com.android.application", "com.android.library").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            dependencies.add("lintChecks", project(":diagnostic-lint"))
        }
    }
}

val diagnosticModuleClassification = tasks.register("diagnosticModuleClassification") {
    doLast {
        val actual = subprojects.filter {
            it.plugins.hasPlugin("com.android.application") || it.plugins.hasPlugin("com.android.library") ||
                it.plugins.hasPlugin("org.jetbrains.kotlin.jvm") || it.plugins.hasPlugin("java") ||
                it.plugins.hasPlugin("java-library")
        }.map { it.path }.toSet()
        check(actual == diagnosticCoverage.keys) {
            "Every runtime or tooling module must be classified for diagnostic coverage. Missing: ${actual - diagnosticCoverage.keys}; stale: ${diagnosticCoverage.keys - actual}"
        }
        val forbidden = fileTree(rootDir) {
            include("**/lint.xml", "**/lint-baseline.xml")
            include("**/src/**/*.kt")
            include("**/build.gradle", "**/build.gradle.kts")
            exclude("**/build/**", ".gradle/**")
            exclude("diagnostic-lint/**")
        }.filter { it.readText().contains("DiagnosticBoundary") }
        check(forbidden.isEmpty()) { "DiagnosticBoundary cannot be suppressed or baselined: $forbidden" }
    }
}

val diagnosticContracts = tasks.register("diagnosticContracts") {
    dependsOn(":diagnostic-lint:test", ":core:model:testDebugUnitTest", ":core:data:testDebugUnitTest", ":core:agent:testDebugUnitTest", ":core:provider:testDebugUnitTest", ":core:net:testDebugUnitTest", ":core:speech:testDebugUnitTest", ":core:device:testDebugUnitTest")
    doFirst {
        val required = mapOf(
            "core/data" to listOf("DiagnosticContractTest.kt"),
            "core/agent" to listOf("AgentDiagnosticContractTest.kt", "ToolRegistryDiagnosticContractTest.kt", "McpDiagnosticContractTest.kt"),
            "core/provider" to listOf("ProviderDiagnosticContractTest.kt"),
            "core/net" to listOf("HttpDiagnosticContractTest.kt"),
            "core/speech" to listOf("VoiceInputRouterTest.kt"),
            "core/device" to listOf("DiagnosticPrivExecutorTest.kt"),
        )
        required.forEach { (module, names) -> names.forEach { name ->
            val matches = fileTree("$module/src/test") { include("**/$name") }.files
            check(matches.size == 1 && matches.single().readText().contains("@Test")) {
                "Required diagnostic contract is missing or empty: $module/$name"
            }
        } }
    }
}

val diagnosticDebug = tasks.register("diagnosticArchitectureCheckDebug") {
    dependsOn(diagnosticModuleClassification, diagnosticContracts)
    dependsOn(diagnosticModules.map { "$it:lintDebug" })
}

val diagnosticRelease = tasks.register("diagnosticArchitectureCheckRelease") {
    dependsOn(diagnosticModuleClassification, diagnosticContracts)
    dependsOn(diagnosticModules.map { "$it:lintRelease" })
}

val verifyDiagnostics = tasks.register("verifyDiagnostics") {
    group = "verification"
    description = "Verifies mandatory diagnostic boundaries and event contracts."
    dependsOn(diagnosticDebug, diagnosticRelease)
}

tasks.register("check") { dependsOn(verifyDiagnostics) }

project(":app") {
    tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn(diagnosticDebug) }
    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(diagnosticRelease) }
}
