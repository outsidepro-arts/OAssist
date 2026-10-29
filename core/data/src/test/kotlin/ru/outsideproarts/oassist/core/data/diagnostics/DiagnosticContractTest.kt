package ru.outsideproarts.oassist.core.data.diagnostics

import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DiagnosticContractTest {
    private val cipher = object : DiagnosticCipher {
        override fun encrypt(data: ByteArray) = data.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    private fun unzip(data: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test fun `secrets are cleaned before writing and export`() {
        val dir = Files.createTempDirectory("oassist-diagnostics-test").toFile()
        val store = DiagnosticStore(dir, cipher)
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "run", attributes = mapOf("authorization" to "my-key"), content = """{"password":"my-key","message":"my-key"}"""), listOf("my-key"))
        val archive = ByteArrayOutputStream()
        store.export(archive, 0)
        val events = unzip(archive.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("my-key"))
        assertTrue("${store.health()} files=${dir.listFiles()?.map { it.name }} events=$events", events.contains("REDACTED"))
        assertTrue(dir.listFiles()!!.none { it.readBytes().decodeToString().contains("my-key") })
    }

    @Test fun `sanitizer preserves token counts and tool parameter definitions`() {
        val input = """{"max_tokens":100,"total_tokens":43,"password":"sensitive","parameters":{"properties":{"password":{"type":"string"}}}}"""
        val result = DiagnosticSanitizer.clean(input, emptyList())
        assertTrue(result.contains("\"max_tokens\":100"))
        assertTrue(result.contains("\"total_tokens\":43"))
        assertTrue(result.contains("\"password\":{\"type\":\"string\"}"))
        assertFalse(result.contains("sensitive"))
    }

    @Test fun `media is omitted by default and included on explicit request`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-media-test").toFile(), cipher)
        val encoded = Base64.getEncoder().encodeToString("image-content".toByteArray())
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "run", content = """{"image_url":"data:image/png;base64,$encoded"}"""))
        val without = ByteArrayOutputStream()
        store.export(without, 0)
        val defaultEntries = unzip(without.toByteArray())
        assertTrue(defaultEntries.keys.none { it.startsWith("media/") })
        assertFalse(defaultEntries.getValue("events.jsonl").decodeToString().contains(encoded))
        val with = ByteArrayOutputStream()
        store.export(with, 0, true)
        assertEquals("image-content", unzip(with.toByteArray()).filterKeys { it.startsWith("media/") }.values.single().decodeToString())
    }

    @Test fun `media quota failure removes partial event and published media before next run`() {
        val dir = Files.createTempDirectory("oassist-media-quota-cleanup").toFile()
        val store = DiagnosticStore(dir, cipher, maxBytes = 10000, reserveBytes = 1000, maxMediaBytes = 100)
        val encoded = Base64.getEncoder().encodeToString(ByteArray(60) { 1 })
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "failed", content =
            """{"first":"data:image/png;base64,$encoded","second":"data:image/png;base64,$encoded"}"""))
        store.export(ByteArrayOutputStream(), 0)
        assertEquals("media_quota_exceeded", store.health().lastError)
        assertTrue(dir.listFiles()!!.none { it.extension == "partial" || it.extension == "media" })
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "next", content = "next-content"))
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        assertTrue(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("next-content"))
        store.close()
    }

    @Test fun `first use removes partial files left by process death`() {
        val dir = Files.createTempDirectory("oassist-stale-partials").toFile()
        listOf("event.partial", "event.media.partial", "owner.binding.partial").forEach {
            java.io.File(dir, it).writeBytes(cipher.encrypt("stale private".toByteArray()))
        }
        java.io.File(dir, "orphan.blob.media").writeBytes(cipher.encrypt("orphan".toByteArray()))
        java.io.File(dir, "valid.event").writeBytes(cipher.encrypt("retained event".toByteArray()))
        java.io.File(dir, "valid.blob.media").writeBytes(cipher.encrypt("retained media".toByteArray()))
        val store = DiagnosticStore(dir, cipher, maxBytes = 10000, reserveBytes = 1000)
        assertTrue(store.health().available)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".partial") })
        assertFalse(java.io.File(dir, "orphan.blob.media").exists())
        assertTrue(java.io.File(dir, "valid.event").exists())
        assertTrue(java.io.File(dir, "valid.blob.media").exists())
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "new", content = "new-content"))
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        assertTrue(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("new-content"))
        store.close()
    }

    @Test fun `deleting conversation removes associated descendants`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-delete-test").toFile(), cipher)
        store.record(DiagnosticEvent(DiagnosticKind.STORAGE, "root", attributes = mapOf("chatId" to "chat")))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", parentOperationId = "root", content = "private"))
        store.export(ByteArrayOutputStream(), 0)
        store.removeConversation("chat")
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", content = "late private"))
        val out = ByteArrayOutputStream()
        store.export(out, 0)
        val actual = unzip(out.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse("${store.health()} events=$actual", actual.contains("private"))
    }

    @Test fun `deletion tombstones child registered before queue and rejects its late descendant`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-delete-register-race").toFile(), cipher)
        store.bindConversation("chat", "root")
        val registered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedSecrets = object : AbstractCollection<String>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String> {
                registered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return listOf("secret").iterator()
            }
        }
        val child = thread {
            store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", "root", content = "child-private"), blockedSecrets)
        }
        try {
            assertTrue(registered.await(10, TimeUnit.SECONDS))
            store.removeConversation("chat")
        } finally {
            release.countDown()
            child.join(10000)
        }
        assertFalse(child.isAlive)
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "grandchild", "child", content = "grandchild-private"))
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        assertFalse(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("private"))
        store.close()
    }

    @Test fun `clear rejects late events from previous operations`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-clear-test").toFile(), cipher)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "old"))
        store.export(ByteArrayOutputStream(), 0)
        store.clear()
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "old", content = "late private"))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", parentOperationId = "old", content = "late child"))
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "fresh", content = "new"))
        val out = ByteArrayOutputStream()
        store.export(out, 0)
        val events = unzip(out.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("late private"))
        assertFalse(events.contains("late child"))
        assertTrue(events.contains("new"))
    }

    @Test fun `deletion uses stored roots when binding event was lost`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-lost-binding-test").toFile(), cipher)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "root"))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", parentOperationId = "root", content = "private"))
        store.export(ByteArrayOutputStream(), 0)
        store.removeConversation("chat", setOf("root"))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", content = "late"))
        val out = ByteArrayOutputStream()
        store.export(out, 0)
        val events = unzip(out.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("private"))
        assertFalse(events.contains("late"))
    }

    @Test fun `failed turn ownership survives reopening without a binding event`() {
        val dir = Files.createTempDirectory("oassist-failed-owner").toFile()
        val first = DiagnosticStore(dir, cipher)
        first.bindConversation("chat", "failed")
        first.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "failed", content = "private history"))
        first.export(ByteArrayOutputStream(), 0)
        first.close()
        val reopened = DiagnosticStore(dir, cipher)
        reopened.removeConversation("chat")
        reopened.record(DiagnosticEvent(DiagnosticKind.PROVIDER_RESPONSE, "late", "failed", content = "late private"))
        val out = ByteArrayOutputStream()
        reopened.export(out, 0)
        assertFalse(unzip(out.toByteArray()).getValue("events.jsonl").decodeToString().contains("private"))
        reopened.close()
    }

    @Test fun `binding failure drops later request content without failing the call`() {
        val failingCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray = error("unavailable")
            override fun decrypt(data: ByteArray): ByteArray = error("unavailable")
        }
        val store = DiagnosticStore(Files.createTempDirectory("oassist-bind-failure").toFile(), failingCipher)
        store.bindConversation("chat", "root")
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "root", content = "private"))
        assertFalse(store.health().available)
        assertTrue(store.health().droppedEvents >= 2)
        store.close()
    }

    @Test fun `binding quota fails closed and preserves existing ownership`() {
        val dir = Files.createTempDirectory("oassist-binding-quota").toFile()
        val reserve = 700L
        val limit = 4000L
        val store = DiagnosticStore(dir, cipher, maxBytes = limit, reserveBytes = reserve)
        store.bindConversation("chat", "first")
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "first", content = "owned-private"))
        store.export(ByteArrayOutputStream(), 0)
        var rejected = ""
        for (index in 0..100) {
            val operation = "extra-$index"
            store.bindConversation("chat", operation)
            if (!store.health().available) {
                rejected = operation
                break
            }
        }
        assertTrue(rejected.isNotEmpty())
        assertTrue(dir.listFiles()!!.filter { it.extension == "binding" }.sumOf { it.length() } <= reserve)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= limit)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".binding.partial") })
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, rejected, content = "blocked-private"))
        val before = ByteArrayOutputStream()
        store.export(before, 0)
        val beforeEvents = unzip(before.toByteArray()).getValue("events.jsonl").decodeToString()
        assertTrue(beforeEvents.contains("owned-private"))
        assertFalse(beforeEvents.contains("blocked-private"))
        store.removeConversation("chat")
        val after = ByteArrayOutputStream()
        store.export(after, 0)
        assertFalse(unzip(after.toByteArray()).getValue("events.jsonl").decodeToString().contains("owned-private"))
        store.close()
    }

    @Test fun `expired completed binding frees quota while active binding remains`() {
        var now = System.currentTimeMillis()
        val dir = Files.createTempDirectory("oassist-binding-retention").toFile()
        val store = DiagnosticStore(dir, cipher, clock = { now }, maxBytes = 10000, reserveBytes = 1000)
        store.bindConversation("completed-chat", "completed")
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "completed"))
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_END, "completed"))
        store.bindConversation("active-chat", "active")
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "active"))
        store.export(ByteArrayOutputStream(), 0)
        now += 8L * 24 * 60 * 60 * 1000
        store.bindConversation("new-chat", "new")
        assertTrue(store.health().toString(), store.health().available)
        assertEquals(2, dir.listFiles()!!.count { it.extension == "binding" })
        store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "active", content = "active-private"))
        store.removeConversation("active-chat")
        val out = ByteArrayOutputStream()
        store.export(out, 0)
        assertFalse(unzip(out.toByteArray()).getValue("events.jsonl").decodeToString().contains("active-private"))
        store.close()
    }

    @Test fun `rotation preserves technical metadata and evicts content by run`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-quota-test").toFile(), cipher, maxBytes = 18000, reserveBytes = 3000)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "old", attributes = mapOf("status" to "old-meta")))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "old", content = "A".repeat(6000)))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "old", content = "B".repeat(6000)))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "new", content = "C".repeat(6000)))
        val out = ByteArrayOutputStream()
        store.export(out, 0)
        val events = unzip(out.toByteArray()).getValue("events.jsonl").decodeToString()
        assertTrue(events.contains("old-meta"))
        assertFalse(events.contains("A".repeat(100)))
        assertFalse(events.contains("B".repeat(100)))
        assertTrue(events.contains("C".repeat(100)))
    }

    @Test fun `pinned snapshot survives quota pressure and export does not block writer`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-pinned-test").toFile(), cipher, maxBytes = 13000, reserveBytes = 2000)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "old", content = "A".repeat(7000)))
        store.listOperations(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val output = object : java.io.FilterOutputStream(bytes) {
            override fun write(value: Int) { started.countDown(); release.await(10, TimeUnit.SECONDS); out.write(value) }
            override fun write(value: ByteArray, offset: Int, length: Int) { started.countDown(); release.await(10, TimeUnit.SECONDS); out.write(value, offset, length) }
        }
        val exporting = thread { store.export(output, 0) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        try {
            store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "new", content = "B".repeat(7000)))
            store.listOperations(0)
            assertTrue(store.health().droppedEvents > 0)
        } finally { release.countDown(); exporting.join(10000) }
        assertTrue(unzip(bytes.toByteArray()).getValue("events.jsonl").decodeToString().contains("A".repeat(100)))
    }
}
