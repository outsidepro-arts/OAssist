package ru.outsideproarts.oassist.core.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.outsideproarts.oassist.core.data.memory.*
import ru.outsideproarts.oassist.core.data.mcp.*
import ru.outsideproarts.oassist.core.data.prefs.*
import ru.outsideproarts.oassist.core.data.provider.*
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.data.skill.FileSkillStore
import ru.outsideproarts.oassist.core.model.*
import java.io.File
import java.io.IOException

class BackupImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun invalidSkillDoesNotMutateAnyStoreOrSecrets() = runTest {
        val f = Fixture(temp.newFolder())
        try {
            f.seed()
            val before = f.providers.observeProviders().first()
            val skillBefore = f.skills.get("old")
            val raw = encodeBackup(BackupV1(skills = listOf(SkillBackup("bad", "Bad", "   "))))
            assertTrue(f.importer().apply(raw).isFailure)
            assertEquals(before, f.providers.observeProviders().first())
            assertEquals("old", f.memory.read())
            assertEquals(skillBefore, f.skills.get("old"))
            assertTrue(f.secrets.contains("provider.p.apiKey"))
            assertFalse(f.settings.importUnfinished.first())
        } finally { f.close() }
    }

    @Test fun legacyReplaceDropsAbsentModesCatalogsAndDanglingReferencesAfterReopen() = runTest {
        val dir = temp.newFolder()
        val f = Fixture(dir)
        try {
            f.seed()
            val backup = BackupV1(providers = listOf(profile), modes = listOf(
                ModeConfig(AssistMode.CHAT, providerId = "gone", modelId = "stale", initialPrompt = "keep"),
            ))
            f.importer().apply(encodeBackup(backup)).getOrThrow()
        } finally { f.close() }
        val reopened = Fixture(dir)
        try {
            val modes = reopened.providers.observeModeConfigs().first()
            assertEquals(AssistMode.entries.size, modes.size)
            assertEquals(ModeConfig(AssistMode.CHAT, initialPrompt = "keep"), modes.single { it.mode == AssistMode.CHAT })
            assertEquals(ModeConfig.default(AssistMode.ASSISTANT), modes.single { it.mode == AssistMode.ASSISTANT })
            assertNull(reopened.providers.observeModelCatalog("p").first())
            assertNull(reopened.providers.observeModelCatalog("orphan").first())
            assertFalse(reopened.settings.importUnfinished.first())
        } finally { reopened.close() }
    }

    @Test fun lateFailurePersistsWarningAndSameFileRetryCompletesReplacement() = runTest {
        val dir = temp.newFolder()
        val f = Fixture(dir)
        val raw = encodeBackup(BackupV1(providers = listOf(profile.copy(baseUrl = "https://new.invalid")), memory = "new"))
        try {
            f.seed()
            f.beforeMemoryWrite = { throw IOException("disk failure") }
            assertTrue(f.importer().apply(raw).isFailure)
            assertEquals("https://new.invalid", f.providers.getProvider("p")!!.baseUrl)
            assertEquals("old", f.memory.read())
            assertTrue(f.settings.importUnfinished.first())
            assertFalse(f.secrets.contains("provider.p.apiKey"))
        } finally { f.close() }
        val reopened = Fixture(dir)
        try {
            assertTrue(reopened.settings.importUnfinished.first())
            reopened.importer().apply(raw).getOrThrow()
            assertEquals("new", reopened.memory.read())
            assertFalse(reopened.settings.importUnfinished.first())
        } finally { reopened.close() }
    }

    @Test fun cancellationAfterMutationPropagatesAndWarningSurvivesReopen() = runTest {
        val dir = temp.newFolder()
        val f = Fixture(dir)
        try {
            f.seed()
            val reached = CompletableDeferred<Unit>()
            f.beforeMemoryWrite = { reached.complete(Unit); awaitCancellation() }
            val job = launch { f.importer().apply(encodeBackup(BackupV1())); fail("Cancellation swallowed") }
            reached.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(f.providers.observeProviders().first().isEmpty())
        } finally { f.close() }
        val reopened = Fixture(dir)
        try { assertTrue(reopened.settings.importUnfinished.first()) } finally { reopened.close() }
    }

    @Test fun concurrentImporterInstancesWaitForOwnerAndRetryAfterCancellation() = runTest {
        val f = Fixture(temp.newFolder())
        try {
            val reached = CompletableDeferred<Unit>()
            var calls = 0
            f.beforeMemoryWrite = { if (++calls == 1) { reached.complete(Unit); awaitCancellation() } }
            val first = launch { f.importer().apply(encodeBackup(BackupV1(memory = "first"))) }
            reached.await()
            val second = async { f.importer().apply(encodeBackup(BackupV1(memory = "second"))) }
            yield()
            assertFalse(second.isCompleted)
            assertTrue(f.settings.importUnfinished.first())
            first.cancelAndJoin()
            second.await().getOrThrow()
            assertEquals("second", f.memory.read())
            assertFalse(f.settings.importUnfinished.first())
        } finally { f.close() }
    }

    private class Fixture(private val dir: File) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Official Okio storage uses atomic replacement on Windows too. Android's
        // FileStorage uses File.renameTo, which cannot replace an existing JVM file here.
        private fun prefs(name: String) = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(dir, "$name.preferences_pb").absolutePath.toPath()
            },
            scope = scope,
        )
        val secrets = ImportSecrets()
        val settings = SettingsDataStore(prefs("settings"))
        val providers = ProviderRepository(ProviderConfigStore(prefs("providers")), secrets)
        private val tools = ToolConfigStore(prefs("tools"))
        private val mcp = McpServerRepository(McpConfigStore(prefs("mcp")), secrets)
        val skills = FileSkillStore({ dir }, prefs("skills"))
        var beforeMemoryWrite: suspend () -> Unit = {}
        private val diskMemory = FileMemoryStore(dir)
        val memory = object : MemoryStore by diskMemory {
            override suspend fun write(text: String) { beforeMemoryWrite(); diskMemory.write(text) }
        }
        private val approvals = DataStoreApprovalStore(prefs("approvals"))
        fun importer() = BackupImport(settings, providers, tools, mcp, skills, memory, approvals)
        suspend fun seed() {
            providers.upsertProvider(profile)
            providers.setApiKey("p", "secret".toCharArray())
            AssistMode.entries.forEach { providers.setModeConfig(ModeConfig(it, "p", "old")) }
            providers.saveModelCatalog("p", listOf(ModelInfo("old")))
            providers.saveModelCatalog("orphan", listOf(ModelInfo("orphan")))
            skills.upsert("old", "Old", "old skill")
            memory.write("old")
        }
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    companion object { private val profile = ProviderProfile("p", "P", ProviderPreset.CUSTOM, "https://old.invalid") }
}

internal class ImportSecrets : SecretStore {
    private val values = linkedMapOf<String, CharArray>()
    var beforePut: suspend () -> Unit = {}
    var beforeDelete: suspend () -> Unit = {}
    override suspend fun put(alias: String, plaintext: CharArray) { beforePut(); values[alias] = plaintext.copyOf() }
    override suspend fun get(alias: String) = values[alias]?.copyOf()
    override suspend fun contains(alias: String) = alias in values
    override suspend fun delete(alias: String) { beforeDelete(); values.remove(alias) }
    override suspend fun aliases() = values.keys.toList()
    override suspend fun clearAll() { values.clear() }
    override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) {
        first?.let { put(firstAlias, it) }; second?.let { put(secondAlias, it) }
    }
    override suspend fun getPair(firstAlias: String?, secondAlias: String?) = firstAlias?.let { get(it) } to secondAlias?.let { get(it) }
}
