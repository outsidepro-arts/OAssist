package ru.outsideproarts.oassist.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.client.api.UElementHandler
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

class DiagnosticBoundaryDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> = listOf(
        "HttpClient", "OkHttpClient", "OpenAiCompatibleClient", "OpenAiResponsesClient",
        "AnthropicClient", "GeminiClient", "RoutingModelClient", "execute", "swapForTest", "install",
    )

    override fun getApplicableConstructorTypes(): List<String> = listOf(
        "okhttp3.OkHttpClient", "io.ktor.client.HttpClient",
        "ru.outsideproarts.oassist.core.provider.openai.OpenAiCompatibleClient",
        "ru.outsideproarts.oassist.core.provider.openai.OpenAiResponsesClient",
        "ru.outsideproarts.oassist.core.provider.anthropic.AnthropicClient",
        "ru.outsideproarts.oassist.core.provider.gemini.GeminiClient",
        "ru.outsideproarts.oassist.core.provider.RoutingModelClient",
    )

    override fun visitConstructor(context: JavaContext, node: UCallExpression, constructor: PsiMethod) {
        inspect(context, node, constructor)
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        inspect(context, node, method)
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UClass::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitClass(node: UClass) {
            val file = context.file.invariantSeparatorsPath
            if (isProduction(file) && !node.isInterface &&
                context.evaluator.implementsInterface(node.javaPsi, "ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder", false) &&
                !file.endsWith("/core/data/src/main/kotlin/ru/outsideproarts/oassist/core/data/diagnostics/DiagnosticStore.kt") &&
                node.qualifiedName != "ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics") {
                context.report(ISSUE, node, context.getLocation(node as UElement), "Production recorder implementations are restricted to the diagnostic store.")
            }
        }
    }

    private fun inspect(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val owner = method.containingClass?.qualifiedName ?: return
        val file = context.file.invariantSeparatorsPath
        if (!isProduction(file)) return
        val name = method.name
        val invalid = when {
            name == "HttpClient" && owner.startsWith("io.ktor.client.") ->
                !file.endsWith("/core/net/src/main/kotlin/ru/outsideproarts/oassist/core/net/HttpClientFactory.kt")
            name == "OkHttpClient" && owner.startsWith("okhttp3.") ->
                !file.endsWith("/core/net/src/main/kotlin/ru/outsideproarts/oassist/core/net/HttpClientFactory.kt")
            name in providerClients && owner.startsWith("ru.outsideproarts.oassist.core.provider.") ->
                !file.endsWith("/RoutingModelClient.kt") &&
                    !file.endsWith("/ModelClient.kt") && !file.endsWith("/$name.kt")
            name == "execute" && (owner == "ru.outsideproarts.oassist.core.agent.Tool" ||
                method.containingClass?.let {
                    context.evaluator.implementsInterface(it, "ru.outsideproarts.oassist.core.agent.Tool", false)
                } == true) ->
                !file.endsWith("/ToolRegistry.kt")
            name == "swapForTest" && owner == "ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics" -> true
            name == "install" && owner == "ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics" ->
                !file.endsWith("/core/data/src/main/kotlin/ru/outsideproarts/oassist/core/data/DataContainer.kt")
            else -> false
        }
        if (invalid) context.report(ISSUE, node, context.getLocation(node),
            "Use the instrumented factory or registry so diagnostics cannot be bypassed.")
    }

    companion object {
        private fun isProduction(file: String): Boolean =
            "/src/" in file && !Regex("/src/(?:test|androidTest)[^/]*/", RegexOption.IGNORE_CASE).containsMatchIn(file)
        private val providerClients = setOf("OpenAiCompatibleClient", "OpenAiResponsesClient", "AnthropicClient", "GeminiClient", "RoutingModelClient")
        val ISSUE: Issue = Issue.create(
            id = "DiagnosticBoundary",
            briefDescription = "Diagnostic boundary bypassed",
            explanation = "Network clients, provider implementations and tools must use their instrumented factory or registry.",
            category = Category.CORRECTNESS,
            priority = 10,
            severity = Severity.ERROR,
            implementation = Implementation(DiagnosticBoundaryDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
