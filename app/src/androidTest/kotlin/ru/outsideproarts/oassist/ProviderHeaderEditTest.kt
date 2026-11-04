package ru.outsideproarts.oassist

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.provider.ProviderConfigStore
import ru.outsideproarts.oassist.core.data.provider.ProviderRepository
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ProviderPreset
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.ProviderValidator
import ru.outsideproarts.oassist.feature.settings.ProviderEditScreen
import ru.outsideproarts.oassist.feature.settings.ProviderSecretsInput
import ru.outsideproarts.oassist.feature.settings.R as SettingsR

@RunWith(AndroidJUnit4::class)
class ProviderHeaderEditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredDeletedLegacyDraftKeepsOriginalIdentityAndCannotCreateProvider() {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val original = ProviderProfile("legacy", "Legacy", ProviderPreset.CUSTOM, "https://old.example/v1")
        runBlocking { repository.upsertProvider(original) }
        val current = mutableStateOf<ProviderProfile?>(original)
        var draft: ProviderProfile? = null
        var origin: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ProviderEditScreen(current.value, false, null,
                    { p, _, id, _ -> draft = p; origin = id }, {}, onBack = {}, sourceProviderId = "legacy")
            }
        }
        compose.waitForIdle()
        runBlocking { repository.deleteProvider("legacy") }
        compose.runOnIdle { current.value = null }
        restoration.emulateSavedInstanceStateRestore()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(SettingsR.string.action_save)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("legacy", draft!!.id)
            assertEquals("legacy", origin)
            assertNull(draft!!.credentialRevision)
        }
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"data":[]}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val result = runBlocking {
                saveProvider(repository, ProviderValidator(Diagnostics, secrets, http, null), secrets, draft!!,
                    ProviderSecretsInput(apiKey = "entered-before-import"), originalProviderId = origin)
            }
            assertTrue(result.exceptionOrNull() is ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException)
            assertEquals(0, requests)
            assertNull(runBlocking { repository.getProvider("legacy") })
        } finally { http.close() }
    }

    @Test fun restoredOldDraftAfterSameIdImportCannotValidateWithFreshCredentials() {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val original = ProviderProfile("p", "Provider", ProviderPreset.CUSTOM, "https://old.example/v1")
        val initial = runBlocking {
            repository.upsertProvider(original)
            repository.setApiKey("p", "old-key".toCharArray())
            repository.getProvider("p")!!
        }
        val current = mutableStateOf(initial)
        var draft: ProviderProfile? = null
        var input: ProviderSecretsInput? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ProviderEditScreen(current.value, false, null,
                    { p, s, _, _ -> draft = p; input = s }, {}, onBack = {})
            }
        }
        compose.waitForIdle()
        val replaced = runBlocking {
            repository.clearSecrets("p")
            repository.upsertProvider(original.copy(baseUrl = "https://imported.example/v1"))
            repository.setApiKey("p", "fresh-key".toCharArray())
            repository.getProvider("p")!!
        }
        compose.runOnIdle { current.value = replaced }
        restoration.emulateSavedInstanceStateRestore()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(SettingsR.string.action_save)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.credentialRevision, draft!!.credentialRevision)
            assertEquals(initial.baseUrl, draft!!.baseUrl)
        }
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"data":[]}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            val result = runBlocking {
                saveProvider(repository, ProviderValidator(Diagnostics, secrets, http, null), secrets, draft!!, input!!)
            }
            assertTrue(result.exceptionOrNull() is ru.outsideproarts.oassist.core.data.provider.CredentialRevisionMismatchException)
            assertEquals(0, requests)
        } finally { http.close() }
    }

    @Test fun changingPresetDoesNotSendOrRetainHiddenHeader() = checkEdit(changePreset = true)
    @Test fun editingWithoutChangingPresetKeepsVisibleHeader() = checkEdit(changePreset = false)

    @Test fun delayedCleanupCannotCloseNewEditorAfterBack() {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        secrets.beforeDelete = {
            started.complete(Unit)
            withContext(NonCancellable) { finish.await() }
        }
        compose.setContent {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    Column {
                        TextButton(onClick = { nav.navigate("editor/first") }) { Text("Open first") }
                        TextButton(onClick = { nav.navigate("editor/second") }) { Text("Open second") }
                    }
                }
                composable("editor/{id}") { entry ->
                    val owner = rememberProviderEditorOwner(entry)
                    val id = entry.arguments?.getString("id")
                    Column {
                        Text("Editor $id")
                        TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                        TextButton(onClick = {
                            owner.launch {
                                try {
                                    retryProviderHeaderCleanup(repository, secrets, setOf("unused"),
                                        { nav.currentBackStackEntry == entry }, {}, { nav.popBackStack() })
                                } finally {
                                    completed.complete(Unit)
                                }
                            }
                        }) { Text("Retry") }
                    }
                }
            }
        }
        compose.onNodeWithText("Open first").performClick()
        compose.onNodeWithText("Retry").performClick()
        runBlocking { withTimeout(5_000) { started.await() } }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Open second").performClick()
        finish.complete(Unit)
        runBlocking { withTimeout(5_000) { completed.await() } }
        compose.waitForIdle()
        compose.onNodeWithText("Editor second").assertExists()
    }

    private fun checkEdit(changePreset: Boolean) {
        val secrets = MemorySecrets()
        val repository = ProviderRepository(ProviderConfigStore(MemoryPreferences()), secrets)
        val profile = ProviderProfile(id = "p", name = "Provider", preset = ProviderPreset.CUSTOM,
            baseUrl = "https://old.example/v1", extraHeaders = mapOf("X-Private" to "local.alias"))
        runBlocking {
            secrets.put("local.alias", "private-value".toCharArray())
            repository.upsertProvider(profile)
        }
        val requests = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.host to request.headers["X-Private"]
            respond("""{"data":[]}""", headers = headersOf("Content-Type", "application/json"))
        })
        try {
            var edited: ProviderProfile? = null
            var input: ProviderSecretsInput? = null
            compose.setContent {
                MaterialTheme {
                    ProviderEditScreen(profile, false, null, { p, s, _, _ -> edited = p; input = s }, {}, onBack = {})
                }
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            if (changePreset) {
                compose.onNodeWithText(context.getString(SettingsR.string.provider_preset_custom))
                    .performScrollTo().performClick()
                compose.onNodeWithText(context.getString(SettingsR.string.provider_preset_openai)).performClick()
            }
            compose.onNodeWithText(context.getString(SettingsR.string.action_save)).performScrollTo().performClick()
            compose.runOnIdle { assertNotNull(edited); assertNotNull(input) }
            runBlocking {
                val validator = ProviderValidator(Diagnostics, secrets, http, probeTimeoutMs = null)
                val result = saveProvider(repository, validator, secrets, edited!!, input!!)
                assertTrue(result.toString(), result.isSuccess)
                val saved = repository.getProvider("p")!!
                ModelClient.create(Diagnostics, saved, secrets, http).fetchModels()
            }
            assertEquals(2, requests.size)
            if (changePreset) {
                assertNotEquals("old.example", requests[0].first)
                assertNull(requests[0].second)
                assertNull(requests[1].second)
                assertFalse(runBlocking { secrets.contains("local.alias") })
            } else {
                assertEquals(listOf("private-value", "private-value"), requests.map { it.second })
            }
        } finally { http.close() }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        var beforeDelete: suspend () -> Unit = {}
        override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) {
            error("Pair writes are not used by this test")
        }
        override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> =
            firstAlias?.let { get(it) } to secondAlias?.let { get(it) }
        override suspend fun put(alias: String, plaintext: CharArray) { values[alias] = plaintext.copyOf() }
        override suspend fun get(alias: String) = values[alias]?.copyOf()
        override suspend fun contains(alias: String) = alias in values
        override suspend fun delete(alias: String) { beforeDelete(); values.remove(alias) }
        override suspend fun clearAll() = values.clear()
        override suspend fun aliases() = values.keys.toList()
    }
}
