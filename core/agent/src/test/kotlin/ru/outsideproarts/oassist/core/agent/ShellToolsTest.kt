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
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

private class FakePrivExecutor(
    override val level: PrivilegeLevel = PrivilegeLevel.SHIZUKU,
) : PrivExecutor {
    var lastCmd: List<String> = emptyList()
    var lastPut: Triple<String, String, String>? = null

    override suspend fun exec(cmd: List<String>, timeoutMs: Long, maxBytes: Int): Result<ExecResult> {
        lastCmd = cmd
        return Result.success(ExecResult(0, "ok"))
    }

    override suspend fun getSetting(namespace: String, key: String): Result<String?> =
        Result.success("v:$namespace/$key")

    override suspend fun putSetting(namespace: String, key: String, value: String): Result<Unit> {
        lastPut = Triple(namespace, key, value)
        return Result.success(Unit)
    }

    override suspend fun inputTap(x: Int, y: Int): Result<Unit> = Result.success(Unit)
    override suspend fun inputSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Result<Unit> =
        Result.success(Unit)
    override suspend fun inputKeyevent(keyCode: Int): Result<Unit> = Result.success(Unit)
}

class ShellToolsTest {

    @Test
    fun `exec без привилегий fail-closed`() {
        val tool = ShellExecTool(NoopExecutor())
        assertEquals(PrivilegeLevel.SHIZUKU, tool.privilege)
        assertEquals(ActionRisk.ASK, tool.risk)
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
        val tool = ShellPutSettingTool(fake)
        assertEquals(ActionRisk.ASK, tool.risk)
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
        val tool = ShellPutSettingTool(fake)
        val args = buildJsonObject {
            put("namespace", "foobar")
            put("key", "x")
            put("value", "1")
        }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args) }
        assertFalse(result.success)
        assertEquals(null, fake.lastPut)
    }

    @Test
    fun `каталог содержит shell-тулы`() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue(names.contains("shell_exec"))
        assertTrue(names.contains("shell_get_setting"))
        assertTrue(names.contains("shell_put_setting"))
    }
}
