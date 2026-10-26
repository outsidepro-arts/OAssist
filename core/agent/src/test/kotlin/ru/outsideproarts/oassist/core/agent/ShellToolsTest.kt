package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.device.ExecResult
import ru.outsideproarts.oassist.core.device.NoopExecutor
import ru.outsideproarts.oassist.core.device.PrivExecutor
import ru.outsideproarts.oassist.core.model.PrivilegeLevel
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups

private class FakePrivExecutor(
    override val level: PrivilegeLevel = PrivilegeLevel.SHIZUKU,
) : PrivExecutor {
    var lastCmd: List<String> = emptyList()
    var lastPut: Triple<String, String, String>? = null
    var lastGet: Pair<String, String>? = null
    var getResult: Result<String?> = Result.success("privileged")
    var putResult: Result<Unit> = Result.success(Unit)

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> {
        lastCmd = cmd
        return Result.success(ExecResult(0, "ok"))
    }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> {
        lastGet = namespace to key
        return getResult
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        lastPut = Triple(namespace, key, value)
        return putResult
    }

    override suspend fun inputTap(x: Int, y: Int): Result<Unit> = Result.success(Unit)
    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        Result.success(Unit)
    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> = Result.success(Unit)
}

private class FakeSettingsAccess : SettingsAccess {
    var calls = mutableListOf<String>()
    var getResult: Result<String?> = Result.failure(SecurityException("read denied"))
    var putResult: Result<Boolean> = Result.failure(SecurityException("write denied"))

    override suspend fun getSetting(namespace: String, key: String): Result<String?> {
        calls += "get:$namespace/$key"
        return getResult
    }

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Boolean> {
        calls += "put:$namespace/$key=$value"
        return putResult
    }
}

class ShellToolsTest {

    @Test
    fun `exec без привилегий fail-closed`() {
        val tool = ShellExecTool(NoopExecutor())
        assertEquals(PrivilegeLevel.SHIZUKU, tool.privilege)
        assertEquals(ToolGroups.key(ToolCategory.PRIVILEGED, ToolAccess.WRITE), tool.groupKey)
    }

    @Test
    fun `exec без привилегий возвращает подсказку`() {
        val tool = ShellExecTool(NoopExecutor())
        val args = buildJsonObject { put("command", "settings get secure adb_enabled") }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertFalse(result.success)
        assertTrue(result.output.contains("Shizuku"))
    }

    @Test
    fun `exec блокирует вне белого списка`() {
        val fake = FakePrivExecutor()
        val tool = ShellExecTool(fake)
        val args = buildJsonObject { put("command", "rm -rf /") }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertFalse(result.success)
        assertTrue(fake.lastCmd.isEmpty())
    }

    @Test
    fun `exec пропускает read-only`() {
        val fake = FakePrivExecutor()
        val tool = ShellExecTool(fake)
        val args = buildJsonObject { put("command", "settings get secure adb_enabled") }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertTrue(result.success)
        assertEquals(listOf("settings", "get", "secure", "adb_enabled"), fake.lastCmd)
    }

    @Test
    fun `put_setting пишет через исполнителя`() {
        val fake = FakePrivExecutor()
        val tool = ShellPutSettingTool(fake, FakeSettingsAccess())
        assertEquals(ToolGroups.key(ToolCategory.PRIVILEGED, ToolAccess.WRITE), tool.groupKey)
        val args = buildJsonObject {
            put("namespace", "system")
            put("key", "screen_off_timeout")
            put("value", "60000")
        }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertTrue(result.success)
        assertEquals(Triple("system", "screen_off_timeout", "60000"), fake.lastPut)
    }

    @Test
    fun `put_setting отклоняет чужое пространство`() {
        val fake = FakePrivExecutor()
        val local = FakeSettingsAccess()
        val tool = ShellPutSettingTool(fake, local)
        val args = buildJsonObject {
            put("namespace", "foobar")
            put("key", "x")
            put("value", "1")
        }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertFalse(result.success)
        assertEquals(null, fake.lastPut)
        assertTrue(local.calls.isEmpty())
    }

    @Test
    fun `settings keep approval groups but do not require privileges for local access`() {
        val local = FakeSettingsAccess()
        val read = ShellGetSettingTool(NoopExecutor(), local)
        val write = ShellPutSettingTool(NoopExecutor(), local)
        assertEquals(PrivilegeLevel.NONE, read.privilege)
        assertEquals(PrivilegeLevel.NONE, write.privilege)
        assertEquals(ToolGroups.key(ToolCategory.PRIVILEGED, ToolAccess.READ), read.groupKey)
        assertEquals(ToolGroups.key(ToolCategory.PRIVILEGED, ToolAccess.WRITE), write.groupKey)
    }

    @Test
    fun `local reads work without privileges in all namespaces`() = kotlinx.coroutines.runBlocking {
        for (namespace in listOf("system", "secure", "global")) {
            val local = FakeSettingsAccess().apply { getResult = Result.success("local") }
            val result = ShellGetSettingTool(NoopExecutor(), local).execute(settingArgs(namespace))
            assertTrue(result.success)
            assertEquals("local", result.output)
            assertEquals(listOf("get:$namespace/test_key"), local.calls)
        }
    }

    @Test
    fun `local missing and empty values do not trigger privileged reads`() = kotlinx.coroutines.runBlocking {
        for (value in listOf(null, "")) {
            val fake = FakePrivExecutor()
            val local = FakeSettingsAccess().apply { getResult = Result.success(value) }
            val result = ShellGetSettingTool(fake, local).execute(settingArgs())
            assertTrue(result.success)
            assertEquals(value ?: "(не задано)", result.output)
            assertEquals(null, fake.lastGet)
        }
    }

    @Test
    fun `read denial falls back only after the local attempt`() = kotlinx.coroutines.runBlocking {
        val local = FakeSettingsAccess()
        val calls = mutableListOf<String>()
        val fake = object : PrivExecutor by NoopExecutor() {
            override val level = PrivilegeLevel.SHIZUKU
            override suspend fun getSetting(namespace: String, key: String): Result<String?> {
                calls += local.calls.single()
                calls += "priv:$namespace/$key"
                return Result.success("fallback")
            }
        }
        val result = ShellGetSettingTool(fake, local).execute(settingArgs("secure"))
        assertTrue(result.success)
        assertEquals("fallback", result.output)
        assertEquals(listOf("get:secure/test_key", "priv:secure/test_key"), calls)
    }

    @Test
    fun `local writes do not call privileged executor`() = kotlinx.coroutines.runBlocking {
        for (namespace in listOf("system", "secure", "global")) {
            val fake = FakePrivExecutor()
            val local = FakeSettingsAccess().apply { putResult = Result.success(true) }
            val result = ShellPutSettingTool(fake, local).execute(settingArgs(namespace, ""))
            assertTrue(result.success)
            assertEquals(listOf("put:$namespace/test_key="), local.calls)
            assertEquals(null, fake.lastPut)
        }
    }

    @Test
    fun `local write succeeds with no privileged executor`() = kotlinx.coroutines.runBlocking {
        val local = FakeSettingsAccess().apply { putResult = Result.success(true) }
        assertTrue(ShellPutSettingTool(NoopExecutor(), local).execute(settingArgs(value = "1")).success)
    }

    @Test
    fun `false write result triggers privileged retry`() = kotlinx.coroutines.runBlocking {
        val local = FakeSettingsAccess().apply { putResult = Result.success(false) }
        val fake = FakePrivExecutor(PrivilegeLevel.ROOT)
        val result = ShellPutSettingTool(fake, local).execute(settingArgs(value = "1"))
        assertTrue(result.success)
        assertEquals(listOf("put:system/test_key=1"), local.calls)
        assertEquals(Triple("system", "test_key", "1"), fake.lastPut)
    }

    @Test
    fun `local failure without privileges returns an explicit error`() = kotlinx.coroutines.runBlocking {
        val local = FakeSettingsAccess()
        val read = ShellGetSettingTool(NoopExecutor(), local).execute(settingArgs())
        val write = ShellPutSettingTool(NoopExecutor(), local).execute(settingArgs(value = "1"))
        assertFalse(read.success)
        assertFalse(write.success)
        assertTrue(read.output.contains("read denied"))
        assertTrue(write.output.contains("write denied"))
        assertTrue(read.output.contains("Shizuku"))
        assertTrue(write.output.contains("Shizuku"))
    }

    @Test
    fun `failed privileged reads and writes are reported`() = kotlinx.coroutines.runBlocking {
        val fake = FakePrivExecutor().apply {
            getResult = Result.failure(IllegalStateException("priv read failed"))
            putResult = Result.failure(IllegalStateException("priv write failed"))
        }
        val local = FakeSettingsAccess()
        val read = ShellGetSettingTool(fake, local).execute(settingArgs())
        val write = ShellPutSettingTool(fake, local).execute(settingArgs(value = "1"))
        assertFalse(read.success)
        assertFalse(write.success)
        assertTrue(read.output.contains("priv read failed"))
        assertTrue(write.output.contains("priv write failed"))
    }

    @Test
    fun `invalid read arguments never reach either executor`() = kotlinx.coroutines.runBlocking {
        val fake = FakePrivExecutor()
        val local = FakeSettingsAccess()
        val tool = ShellGetSettingTool(fake, local)
        for (args in listOf(
            settingArgs("invalid"),
            buildJsonObject { put("namespace", "system") },
            buildJsonObject { put("key", "test_key") },
        )) assertFalse(tool.execute(args).success)
        assertTrue(local.calls.isEmpty())
        assertEquals(null, fake.lastGet)
    }

    @Test
    fun `cancellation of settings access does not request privileged retry`() = kotlinx.coroutines.runBlocking {
        val fake = FakePrivExecutor()
        val local = FakeSettingsAccess().apply {
            getResult = Result.failure(kotlinx.coroutines.CancellationException("stop"))
            putResult = Result.failure(kotlinx.coroutines.CancellationException("stop"))
        }
        val tools = listOf(ShellGetSettingTool(fake, local), ShellPutSettingTool(fake, local))
        for (tool in tools) {
            var cancelled = false
            try {
                tool.execute(settingArgs(value = "1"))
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }
        assertEquals(null, fake.lastGet)
        assertEquals(null, fake.lastPut)
    }

    private fun settingArgs(namespace: String = "system", value: String? = null) = buildJsonObject {
        put("namespace", namespace)
        put("key", "test_key")
        if (value != null) put("value", value)
    }

    @Test
    fun `каталог содержит shell-тулы`() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue(names.contains("shell_exec"))
        assertTrue(names.contains("shell_get_setting"))
        assertTrue(names.contains("shell_put_setting"))
    }
}
