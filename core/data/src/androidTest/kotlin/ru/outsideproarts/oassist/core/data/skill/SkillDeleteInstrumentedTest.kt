package ru.outsideproarts.oassist.core.data.skill

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SkillDeleteInstrumentedTest {
    @Test fun retryWipeClearsFlagAfterConfigWriteFailure() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = Files.createTempDirectory(cache.toPath(), "ds009-skill-retry-").toFile()
        var job = Job()
        val configFile = File(dir, "skill_config.preferences_pb")
        fun config() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { configFile }
        try {
            val preferences = config()
            val skills = FileSkillStore({ dir }, preferences)
            skills.upsert("example", "Example", "content")
            skills.setEnabled("example", false)
            val failing = object : DataStore<Preferences> {
                override val data = preferences.data
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    throw IOException("config write failed")
                }
            }
            assertThrows(IOException::class.java) {
                runBlocking { FileSkillStore({ dir }, failing).deleteAll() }
            }
            assertFalse(File(dir, "skills/example").exists())
            job.cancelAndJoin()
            job = Job()
            FileSkillStore({ dir }, config()).deleteAll()
            job.cancelAndJoin()
            job = Job()
            val reopened = FileSkillStore({ dir }, config())
            reopened.upsert("example", "Example", "content")
            assertTrue(reopened.get("example")!!.enabled)
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
        }
    }

    @Test fun failedDeleteKeepsDisabledStateAcrossReopenAndRetry() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = Files.createTempDirectory(cache.toPath(), "ds009-skill-").toFile()
        var job = Job()
        val configFile = File(dir, "skill_config.preferences_pb")
        fun config() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { configFile }
        try {
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
            reopened.delete("example")
            job.cancelAndJoin()
            job = Job()
            val afterRetry = FileSkillStore({ dir }, config())
            assertFalse(File(dir, "skills/example").exists())
            afterRetry.upsert("example", "Example", "content")
            assertTrue(afterRetry.get("example")!!.enabled)
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
        }
    }
}
