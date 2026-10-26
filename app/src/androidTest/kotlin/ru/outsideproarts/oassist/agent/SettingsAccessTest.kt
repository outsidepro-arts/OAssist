package ru.outsideproarts.oassist.agent

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.AndroidSettingsAccess
import ru.outsideproarts.oassist.core.agent.ShellGetSettingTool
import ru.outsideproarts.oassist.core.agent.ShellPutSettingTool
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.device.PrivExecutor
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

@RunWith(AndroidJUnit4::class)
class SettingsAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val local = AndroidSettingsAccess(context.contentResolver)

    @Test
    fun readsExistingSystemSettingWithoutPrivileges() = runBlocking {
        val key = Settings.System.SCREEN_OFF_TIMEOUT
        val expected = Settings.System.getString(context.contentResolver, key)
        assertTrue(expected != null)
        val result = ShellGetSettingTool(NoopExecutor(), local).execute(buildJsonObject {
            put("namespace", "system")
            put("key", key)
        })
        assertTrue(result.output, result.success)
        assertEquals(expected, result.output)
    }

    @Test
    fun absentSettingsAreSuccessfulReadsWithoutPrivileges() = runBlocking {
        for (namespace in listOf("system", "secure", "global")) {
            val result = ShellGetSettingTool(NoopExecutor(), local).execute(buildJsonObject {
                put("namespace", namespace)
                put("key", "oassist_absent_${System.nanoTime()}")
            })
            assertTrue(result.output, result.success)
            assertEquals("(не задано)", result.output)
        }
    }

    @Test
    fun secureWriteDenialRetriesThroughExecutorWithoutChangingSettings() = runBlocking {
        assumeTrue(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_DENIED)
        val key = "oassist_denied_${System.nanoTime()}"
        var writes = 0
        val fake = object : PrivExecutor by NoopExecutor() {
            override val level = PrivilegeLevel.SHIZUKU
            override suspend fun putSetting(namespace: String, actualKey: String, value: String): Result<Unit> {
                assertEquals("secure", namespace)
                assertEquals(key, actualKey)
                assertEquals("1", value)
                writes++
                return Result.success(Unit)
            }
        }
        val result = ShellPutSettingTool(fake, local).execute(buildJsonObject {
            put("namespace", "secure")
            put("key", key)
            put("value", "1")
        })
        assertTrue(result.output, result.success)
        assertEquals(1, writes)
        assertNull(local.getSetting("secure", key).getOrThrow())
    }
}
