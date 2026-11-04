package ru.outsideproarts.oassist.core.device

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootExecutorExitCodeTest {
    private fun executor(code: Int, output: String) = RootExecutor { _, timeout, limit ->
        ProcessRunner.runProcess(FinishedProcess(code, output), timeout, limit)
    }

    @Test fun failedSettingsAndInputCommandsReturnDiagnostics() = runBlocking {
        val root = executor(7, "permission denied")
        val results = listOf(
            root.getSetting("secure", "example"),
            root.putSetting("secure", "example", "value"),
            root.inputTap(1, 2),
            root.inputSwipe(1, 2, 3, 4, 100),
            root.inputKeyevent(3),
        )
        results.forEach { result ->
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message, "7" in message && "permission denied" in message)
        }
        // Raw shell execution still exposes the command's nonzero result to its caller.
        assertEquals(7, root.exec(listOf("getprop")).getOrThrow().exitCode)
    }

    @Test fun successfulCommandsKeepTheirValues() = runBlocking {
        val root = executor(0, " value \n")
        assertEquals("value", root.getSetting("secure", "example").getOrThrow())
        assertTrue(root.putSetting("secure", "example", "value").isSuccess)
        assertTrue(root.inputTap(1, 2).isSuccess)
        assertTrue(root.inputSwipe(1, 2, 3, 4, 100).isSuccess)
        assertTrue(root.inputKeyevent(3).isSuccess)
        assertNull(executor(0, "null\n").getSetting("secure", "example").getOrThrow())
    }

    @Test fun settingsConversionRejectsFailedShizukuExecResult() {
        val result = Result.success(ExecResult(2, "settings error")).settingValue()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("settings error"))
        assertNull(Result.success(ExecResult(0, "null\n")).settingValue().getOrThrow())
    }

    private class FinishedProcess(private val code: Int, output: String) : Process() {
        private val input = ByteArrayInputStream(output.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = code
        override fun isAlive() = false
        override fun destroy() = Unit
    }
}
