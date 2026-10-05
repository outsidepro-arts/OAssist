package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderKind
import ru.outsideproarts.oassist.core.model.ProviderProfile

class ProviderValidatorTest {

    private val emptySecrets = object : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun contains(alias: String): Boolean = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases(): List<String> = emptyList()
    }

    private fun profile(
        name: String = "Test",
        kind: ProviderKind = ProviderKind.OPENAI_COMPAT,
        baseUrl: String = "https://api.example.com/v1",
    ) = ProviderProfile(id = "p1", name = name, kind = kind, baseUrl = baseUrl)

    /** Движок-заглушка: запрос не должен выполняться (локальные проверки/заглушки). */
    private fun unexpectedEngine(): MockEngine = MockEngine { _ ->
        respond("unexpected request", status = HttpStatusCode.InternalServerError)
    }

    private fun validator(engine: MockEngine) = ProviderValidator(
        emptySecrets,
        HttpClient(engine) { expectSuccess = true },
        probeTimeoutMs = null,
    )

    @Test
    fun blankName_failsLocal() = runTest {
        val result = validator(unexpectedEngine()).validate(profile(name = " "))
        assertTrue(result is ProviderValidationResult.Failure)
        assertTrue((result as ProviderValidationResult.Failure).error is ProviderError.InvalidInput)
        assertEquals(
            ProviderError.InvalidInput.Reason.NAME_REQUIRED,
            (result.error as ProviderError.InvalidInput).reason,
        )
    }

    @Test
    fun blankBaseUrl_failsLocal() = runTest {
        val result = validator(unexpectedEngine()).validate(profile(baseUrl = ""))
        val error = (result as ProviderValidationResult.Failure).error as ProviderError.InvalidInput
        assertEquals(ProviderError.InvalidInput.Reason.BASE_URL_REQUIRED, error.reason)
    }

    @Test
    fun invalidBaseUrl_failsLocal() = runTest {
        val result = validator(unexpectedEngine()).validate(profile(baseUrl = "not a url"))
        assertTrue("got: $result", result is ProviderValidationResult.Failure)
        val error = (result as ProviderValidationResult.Failure).error
        assertTrue("got: $error", error is ProviderError.InvalidInput)
        assertEquals(
            ProviderError.InvalidInput.Reason.BASE_URL_INVALID,
            (error as ProviderError.InvalidInput).reason,
        )
    }

    @Test
    fun anthropic_ok_fetchesModels() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.example.com/v1/models", request.url.toString())
            respond(
                content = """{"data":[{"type":"model","id":"claude-opus-4-6","display_name":"Claude Opus 4.6"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = validator(engine).validate(
            profile(kind = ProviderKind.ANTHROPIC, baseUrl = "https://api.example.com"),
        )
        assertTrue("got: $result", result is ProviderValidationResult.Success)
        val models = (result as ProviderValidationResult.Success).models
        assertEquals(listOf("claude-opus-4-6"), models.map { it.id })
        assertEquals("Claude Opus 4.6", models.single().label)
    }

    @Test
    fun gemini_ok_fetchesModels() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models", request.url.toString().substringBefore("?"))
            respond(
                content = """{"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = validator(engine).validate(
            profile(kind = ProviderKind.GEMINI, baseUrl = "https://generativelanguage.googleapis.com"),
        )
        assertTrue("got: $result", result is ProviderValidationResult.Success)
        val models = (result as ProviderValidationResult.Success).models
        assertEquals(listOf("gemini-2.0-flash"), models.map { it.id })
    }

    @Test
    fun openAiCompat_ok_fetchesModels() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.example.com/v1/models", request.url.toString())
            respond(
                content = """{"object":"list","data":[{"id":"m1","created":1}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = validator(engine).validate(profile())
        assertTrue("got: $result", result is ProviderValidationResult.Success)
        assertEquals(listOf("m1"), (result as ProviderValidationResult.Success).models.map { it.id })
    }

    @Test
    fun openAiCompat_http401_mapsToAuthError() = runTest {
        val engine = MockEngine {
            respond("denied", status = HttpStatusCode.Unauthorized)
        }
        val result = validator(engine).validate(profile())
        val error = (result as ProviderValidationResult.Failure).error
        assertTrue(error is ProviderError.Http)
        assertEquals(401, error.status)
    }

    @Test
    fun openCodeZen_ok_fetchesModels() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://zen.example.com/v1/models", request.url.toString())
            respond(
                content = """{"object":"list","data":[{"id":"glm-5.3","created":1}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val result = validator(engine).validate(
            profile(kind = ProviderKind.OPENCODE_ZEN, baseUrl = "https://zen.example.com/v1"),
        )
        val models = (result as ProviderValidationResult.Success).models
        assertEquals(listOf("glm-5.3"), models.map { it.id })
    }

    @Test
    fun malformedResponse_mapsToProtocol() = runTest {
        val engine = MockEngine { respond("not-json-{", status = HttpStatusCode.OK) }
        val result = validator(engine).validate(profile())
        val error = (result as ProviderValidationResult.Failure).error
        assertTrue(error is ProviderError.Protocol)
    }
}
