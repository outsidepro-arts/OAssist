package ru.outsideproarts.oassist.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.data.DataContainer
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics

@RunWith(AndroidJUnit4::class)
class ChatDiagnosticDeletionTest {
    @Test fun failedAndInFlightRootsAreDeletedThroughProductionRepository() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "chat-diagnostic-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(directory, "databases/$name").also { it.parentFile?.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                base.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                base.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("${directory.name}-$name", mode)
        }
        val previous = Diagnostics.swapForTest(null)
        val data = try { DataContainer(context, context) } catch (error: Throwable) {
            Diagnostics.swapForTest(previous)
            throw error
        }
        try {
            val chat = data.chats.createChat(AssistMode.CHAT, "test")
            data.chats.bindDiagnosticOperation(chat, "failed-root")
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "failed-root", content = "failed-private"))
            data.chats.bindDiagnosticOperation(chat, "live-root")
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "live-child", "live-root", content = "live-private"))
            Diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "other-root", content = "other-content"))
            data.chats.deleteChat(chat)
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "late-child", "live-root", content = "late-private"))
            data.chats.bindDiagnosticOperation(chat, "late-bind")
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "late-bind", content = "late-bind-private"))

            val assistant = ChatRepository.ASSISTANT_CHAT_ID
            data.chats.replaceAssistantTurns(emptyList())
            val oldGeneration = data.chats.diagnosticGeneration(assistant)
            data.chats.deleteChat(assistant)
            data.chats.replaceAssistantTurns(emptyList())
            val newGeneration = data.chats.diagnosticGeneration(assistant)
            data.chats.bindDiagnosticOperation(assistant, "new-assistant", newGeneration)
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "new-assistant", content = "new-assistant-content"))
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "old-assistant", content = "old-assistant-private"))
            data.chats.bindDiagnosticOperation(assistant, "old-assistant", oldGeneration)
            data.diagnostics.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "old-assistant", content = "late-assistant-private"))
            val output = ByteArrayOutputStream()
            data.diagnostics.export(output, 0)
            val events = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                var result = ""
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "events.jsonl") result = zip.readBytes().decodeToString()
                }
                result
            }
            assertFalse(events.contains("private"))
            assertTrue(events.contains("other-content"))
            assertTrue(events.contains("new-assistant-content"))
        } finally {
            Diagnostics.swapForTest(previous)
            data.close()
            base.deleteSharedPreferences("${directory.name}-diagnostics")
            require(directory.canonicalFile.toPath().startsWith(base.cacheDir.canonicalFile.toPath()))
            check(directory.deleteRecursively())
        }
    }
}
