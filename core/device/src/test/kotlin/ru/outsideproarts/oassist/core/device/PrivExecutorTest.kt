package ru.outsideproarts.oassist.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
