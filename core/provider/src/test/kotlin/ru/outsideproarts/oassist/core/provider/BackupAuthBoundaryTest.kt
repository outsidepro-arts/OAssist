package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.data.backup.BackupV1
import ru.outsideproarts.oassist.core.data.backup.decodeBackup
import ru.outsideproarts.oassist.core.data.backup.encodeBackup
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

class BackupAuthBoundaryTest {
    @Test
    fun importedEndpointCannotReadOrSendExistingCredentialsForAnyAuthScheme() = runTest {
        for (protocol in ModelProtocol.entries) for (scheme in AuthScheme.entries) {
            val secrets = ExistingSecrets()
            val local = ProviderProfile(
                id = "p", name = "Local", preset = ProviderPreset.CUSTOM,
                baseUrl = "https://trusted.invalid", protocol = protocol, authScheme = scheme,
                apiKeyHeaderName = "X-Custom-Key", apiKeyRef = "provider.p.apiKey",
                basicUserRef = "provider.p.basicUser", basicPasswordRef = "provider.p.basicPassword",
                extraHeaders = mapOf("X-Private" to "unrelated.secret"),
            )
            val requests = mutableListOf<Pair<String, Map<String, String>>>()
            val http = HttpClient(MockEngine { request ->
                requests += request.url.host to request.headers.entries().associate { it.key.lowercase() to it.value.single() }
                respond("""{"data":[],"models":[]}""", headers = headersOf("Content-Type", "application/json"))
            })
            try {
                // Positive control: the real resolver and HTTP path can access the local secrets.
                ModelClient.create(Diagnostics, local, secrets, http).fetchModels()
                assertEquals("private-value", requests.single().second["x-private"])
                secrets.reads.clear()
                val imported = decodeBackup(encodeBackup(BackupV1(providers = listOf(
                    local.copy(baseUrl = "https://imported.invalid"),
                )))).getOrThrow().providers.single()
                ModelClient.create(Diagnostics, imported, secrets, http).fetchModels()
                val (host, headers) = requests.last()
                assertEquals("imported.invalid", host)
                assertEquals(2, requests.size)
                assertTrue("$protocol/$scheme read aliases: ${secrets.reads}", secrets.reads.isEmpty())
                for (name in listOf("authorization", "x-api-key", "x-goog-api-key", "x-custom-key", "x-private")) {
                    assertNull("$protocol/$scheme leaked $name", headers[name])
                }
            } finally {
                http.close()
            }
        }
    }

    private class ExistingSecrets : SecretStore {
        val reads = mutableListOf<String>()
        override suspend fun get(alias: String): CharArray? {
            reads += alias
            return when (alias) {
                "provider.p.apiKey" -> "local-key"
                "provider.p.basicUser" -> "local-user"
                "provider.p.basicPassword" -> "local-password"
                "unrelated.secret" -> "private-value"
                else -> null
            }?.toCharArray()
        }
        override suspend fun put(alias: String, plaintext: CharArray) = error("Unexpected write")
        override suspend fun contains(alias: String) = true
        override suspend fun delete(alias: String) = error("Unexpected deletion")
        override suspend fun clearAll() = error("Unexpected deletion")
        override suspend fun aliases() = emptyList<String>()
    }
}
