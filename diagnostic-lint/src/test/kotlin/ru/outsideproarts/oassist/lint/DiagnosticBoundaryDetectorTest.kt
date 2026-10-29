package ru.outsideproarts.oassist.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class DiagnosticBoundaryDetectorTest {
    private val stub = kotlin("src/main/kotlin/okhttp3/OkHttpClient.kt", """
        package okhttp3
        class OkHttpClient
    """).indented()

    @Test fun rejectsDirectConstruction() {
        lint().issues(DiagnosticBoundaryDetector.ISSUE).files(
            stub,
            kotlin("src/main/kotlin/example/Example.kt", """
                package example
                import okhttp3.OkHttpClient
                fun request() = OkHttpClient()
            """).indented(),
        ).allowMissingSdk().run().expectErrorCount(1)
    }

    @Test fun acceptsFactoryConstruction() {
        lint().issues(DiagnosticBoundaryDetector.ISSUE).files(
            stub,
            kotlin("core/net/src/main/kotlin/ru/outsideproarts/oassist/core/net/HttpClientFactory.kt", """
                package ru.outsideproarts.oassist.core.net
                import okhttp3.OkHttpClient
                fun create() = OkHttpClient()
            """).indented(),
        ).allowMissingSdk().run().expectClean()
    }

    @Test fun rejectsDirectToolExecution() {
        lint().issues(DiagnosticBoundaryDetector.ISSUE).files(
            kotlin("src/main/kotlin/ru/outsideproarts/oassist/core/agent/Tool.kt", """
                package ru.outsideproarts.oassist.core.agent
                interface Tool { fun execute() }
            """).indented(),
            kotlin("src/main/kotlin/example/Caller.kt", """
                package example
                import ru.outsideproarts.oassist.core.agent.Tool
                fun call(tool: Tool) = tool.execute()
                class Implementation : Tool { override fun execute() {} }
                fun callConcrete(tool: Implementation) = tool.execute()
            """).indented(),
        ).allowMissingSdk().run().expectErrorCount(2)
    }

    @Test fun rejectsProductionTestSwapAndEmptyRecorderInVariant() {
        lint().issues(DiagnosticBoundaryDetector.ISSUE).files(
            kotlin("src/main/kotlin/ru/outsideproarts/oassist/core/model/diagnostics/Diagnostics.kt", """
                package ru.outsideproarts.oassist.core.model.diagnostics
                interface DiagnosticRecorder { fun record() }
                object Diagnostics { fun swapForTest(recorder: DiagnosticRecorder?) {} }
            """).indented(),
            kotlin("src/debug/kotlin/example/Caller.kt", """
                package example
                import ru.outsideproarts.oassist.core.model.diagnostics.*
                class EmptyRecorder : DiagnosticRecorder { override fun record() {} }
                fun disable() = Diagnostics.swapForTest(null)
            """).indented(),
        ).allowMissingSdk().run().expectErrorCount(2)
    }
}
