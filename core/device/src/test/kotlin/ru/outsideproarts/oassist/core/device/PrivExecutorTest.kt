package ru.outsideproarts.oassist.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import ru.outsideproarts.oassist.core.model.PrivilegeLevel

class ShellPolicyTest {

    @Test
    fun `read-only settings get разрешён`() {
        assertNull(ShellPolicy.checkShellExec(listOf("settings", "get", "secure", "adb_enabled")))
    }

    @Test
    fun `settings put запрещён в shell_exec`() {
        assertNotNull(ShellPolicy.checkShellExec(listOf("settings", "put", "secure", "x", "1")))
    }

    @Test
    fun `su запрещён`() {
        assertNotNull(ShellPolicy.checkShellExec(listOf("su", "-c", "id")))
    }

    @Test
    fun `input запрещён напрямую`() {
        assertNotNull(ShellPolicy.checkShellExec(listOf("input", "tap", "1", "2")))
    }

    @Test
    fun `неизвестный бинарник запрещён`() {
        assertNotNull(ShellPolicy.checkShellExec(listOf("rm", "-rf", "/")))
    }

    @Test
    fun `метасимволы запрещены`() {
        assertNotNull(ShellPolicy.checkShellExec(listOf("getprop", "a;reboot")))
    }

    @Test
    fun `getprop и dumpsys разрешены`() {
        assertNull(ShellPolicy.checkShellExec(listOf("getprop")))
        assertNull(ShellPolicy.checkShellExec(listOf("dumpsys", "battery")))
    }

    @Test
    fun `wm разрешает только запросы и запрещает изменение`() {
        for (command in listOf(listOf("wm", "size"), listOf("wm", "density"))) {
            assertNull(ShellPolicy.checkShellExec(command))
        }
        for (command in listOf(listOf("wm", "size", "720x1280"), listOf("wm", "density", "reset"), listOf("wm", "size", "-d", "0", "720x1280"))) {
            assertNotNull(ShellPolicy.checkShellExec(command))
        }
    }

    @Test
    fun `dumpsys не передает непроверенные аргументы сервисам`() {
        for (command in listOf(listOf("dumpsys"), listOf("dumpsys", "-l"), listOf("dumpsys", "battery"))) {
            assertNull(ShellPolicy.checkShellExec(command))
        }
        for (command in listOf(listOf("dumpsys", "battery", "reset"), listOf("dumpsys", "battery", "set", "level", "1"), listOf("dumpsys", "--help"))) {
            assertNotNull(ShellPolicy.checkShellExec(command))
        }
    }

    @Test
    fun `только системные алиасы разрешены и сопоставляются фиксированному пути`() {
        for (binary in listOf("getprop", "/system/bin/getprop")) {
            val command = listOf(binary, "ro.build.version.sdk")
            assertNull(ShellPolicy.checkShellExec(command))
            assertEquals(listOf("/system/bin/getprop", "ro.build.version.sdk"), ShellPolicy.shellExecArgv(command))
        }
        for (binary in listOf("/data/local/tmp/getprop", "./getprop", "../system/bin/getprop", "/system/bin/../bin/getprop")) {
            assertNotNull(ShellPolicy.checkShellExec(listOf(binary)))
        }
    }

    @Test
    fun `публичный root exec отклоняет изменение и пользовательский путь до запуска процесса`() = runBlocking {
        val root = RootExecutor()
        assertTrue(root.exec(listOf("wm", "density", "reset")).exceptionOrNull() is IllegalArgumentException)
        assertTrue(root.exec(listOf("/data/local/tmp/getprop")).exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `прочие команды проверяют полный набор аргументов`() {
        for (command in listOf(listOf("settings", "list", "system"), listOf("pm", "list", "packages"))) {
            assertNull(ShellPolicy.checkShellExec(command))
        }
        for (command in listOf(listOf("settings", "get", "secure", "x", "extra"), listOf("getprop", "--help"), listOf("pm", "list", "packages", "extra"))) {
            assertNotNull(ShellPolicy.checkShellExec(command))
        }
    }

    @Test
    fun `capOutput режет с флагом`() {
        val (text, truncated) = ShellPolicy.capOutput("abcdef", 3)
        assertEquals("abc", text)
        assertTrue(truncated)
    }

    @Test
    fun `capOutput без обрезки без флага`() {
        val (text, truncated) = ShellPolicy.capOutput("abc", 10)
        assertEquals("abc", text)
        assertFalse(truncated)
    }
}

class PrivilegeRouterTest {

    private val noop = NoopExecutor()
    private val shizuku = ShizukuExecutor()
    private val root = RootExecutor()
    private val all = PrivExecutors(noop = noop, shizuku = shizuku, root = root)

    @Test
    fun `shizuku в приоритете`() {
        val status = PrivilegeStatus(shizukuAvailable = true, shizukuGranted = true, rootAvailable = true)
        assertEquals(PrivilegeLevel.SHIZUKU, PrivilegeRouter.select(status, all).level)
    }

    @Test
    fun `root при недоступном shizuku`() {
        val status = PrivilegeStatus(shizukuAvailable = false, shizukuGranted = false, rootAvailable = true)
        assertEquals(PrivilegeLevel.ROOT, PrivilegeRouter.select(status, all).level)
    }

    @Test
    fun `без привилегий noop fail-closed`() {
        val status = PrivilegeStatus(shizukuAvailable = false, shizukuGranted = false, rootAvailable = false)
        assertEquals(PrivilegeLevel.NONE, PrivilegeRouter.select(status, all).level)
        assertEquals(PrivilegeLevel.NONE, PrivilegeRouter.effectiveLevel(status))
    }

    @Test
    fun `shizuku без гранта не выбирается`() {
        val status = PrivilegeStatus(shizukuAvailable = true, shizukuGranted = false, rootAvailable = false)
        assertEquals(PrivilegeLevel.NONE, PrivilegeRouter.select(status, all).level)
    }
}
