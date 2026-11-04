package ru.outsideproarts.oassist.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.outsideproarts.oassist.core.data.memory.FileMemoryStore
import ru.outsideproarts.oassist.core.data.skill.FileSkillStore
import java.io.File
import java.io.IOException

class DeleteStoresTest {
    @get:Rule val temp = TemporaryFolder()

    private class Config : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(data.value)
            data.value = next
            return next
        }
    }

    @Test fun memoryDeleteFailurePreservesDataAndRetryWorks() = runBlocking {
        val dir = temp.newFolder()
        val memory = FileMemoryStore(dir)
        memory.write("private")
        for (delete in listOf<(File) -> Boolean>({ false }, { throw IOException("denied") })) {
            assertThrows(IOException::class.java) {
                runBlocking { FileMemoryStore(dir, delete).clear() }
            }
            assertEquals("private", FileMemoryStore(dir).read())
        }
        memory.clear()
        FileMemoryStore(dir).clear()
        assertEquals("", FileMemoryStore(dir).read())
    }

    @Test fun partialSkillDeletePreservesDisabledFlagAndWipeRetryFindsDirectory() = runBlocking {
        val dir = temp.newFolder()
        val preferences = Config()
        val skills = FileSkillStore({ dir }, preferences)
        skills.upsert("example", "Example", "content")
        skills.setEnabled("example", false)
        val failing = FileSkillStore({ dir }, preferences) { target ->
            assertTrue(File(target, "SKILL.md").delete())
            false
        }
        assertThrows(IOException::class.java) { runBlocking { failing.deleteAll() } }
        val reopened = FileSkillStore({ dir }, preferences)
        reopened.upsert("example", "Example", "content")
        assertFalse(reopened.get("example")!!.enabled)
        File(dir, "skills/example/SKILL.md").delete()
        reopened.deleteAll()
        assertFalse(File(dir, "skills/example").exists())
        reopened.upsert("example", "Example", "content")
        assertTrue(reopened.get("example")!!.enabled)
    }

    @Test fun skillDeleteExceptionRetainsFlagForRetry() = runBlocking {
        val dir = temp.newFolder()
        val config = Config()
        val skills = FileSkillStore({ dir }, config)
        skills.upsert("example", "Example", "content")
        skills.setEnabled("example", false)
        val failing = FileSkillStore({ dir }, config) { throw IOException("denied") }
        assertThrows(IOException::class.java) { runBlocking { failing.delete("example") } }
        assertFalse(skills.get("example")!!.enabled)
        skills.delete("example")
        assertEquals(null, skills.get("example"))
    }

    @Test fun disabledSkillSurvivesFailedDeleteAndRealDataStoreReopen() = runBlocking {
        val dir = temp.newFolder()
        val configFile = File(dir, "skill_config.preferences_pb")
        var job = Job()
        fun config() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { configFile }
        val preferences = config()
        val skills = FileSkillStore({ dir }, preferences)
        skills.upsert("example", "Example", "content")
        skills.setEnabled("example", false)
        val failing = FileSkillStore({ dir }, preferences) { false }
        assertThrows(IOException::class.java) { runBlocking { failing.delete("example") } }
        job.cancelAndJoin()
        job = Job()
        val reopened = FileSkillStore({ dir }, config())
        assertFalse(reopened.get("example")!!.enabled)
        job.cancelAndJoin()
    }

    @Test fun wipeRetryClearsFlagLeftAfterConfigFailure() = runBlocking {
        val dir = temp.newFolder()
        val saved = Config()
        val normal = FileSkillStore({ dir }, saved)
        normal.upsert("example", "Example", "content")
        normal.setEnabled("example", false)
        val failing = object : DataStore<Preferences> {
            override val data = saved.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("config write failed")
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { FileSkillStore({ dir }, failing).deleteAll() }
        }
        assertFalse(File(dir, "skills/example").exists())
        assertEquals(false, saved.data.value[booleanPreferencesKey("skill.enabled.example")])
        FileSkillStore({ dir }, saved).deleteAll()
        val reopened = FileSkillStore({ dir }, saved)
        reopened.upsert("example", "Example", "content")
        assertTrue(reopened.get("example")!!.enabled)
    }
}
