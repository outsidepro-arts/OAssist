package ru.outsideproarts.oassist.core.data.diagnostics

import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticEvent
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    @Test fun `record returns while writer is blocked and queued events survive reopen`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val callerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val pausedCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                writing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val dir = Files.createTempDirectory("oassist-nonblocking-record").toFile()
        val store = DiagnosticStore(dir, pausedCipher)
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "first", content = "first-content"))
        var caller: Thread? = null
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            caller = thread {
                try {
                    store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "second", content = "second-content secret"), listOf("secret"))
                } catch (error: Throwable) {
                    callerFailure.set(error)
                } finally {
                    returned.countDown()
                }
            }
            assertTrue("record waited for archive writer", returned.await(2, TimeUnit.SECONDS))
            assertNull(callerFailure.get())
        } finally {
            release.countDown()
            caller?.join(10000)
            store.close()
        }
        val reopened = DiagnosticStore(dir, cipher)
        try {
            val output = ByteArrayOutputStream()
            reopened.export(output, 0)
            val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
            assertTrue(events.contains("first-content"))
            assertTrue(events.contains("second-content [REDACTED]"))
            assertFalse(events.contains("secret"))
            reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "third", content = "third-content"))
            val next = ByteArrayOutputStream()
            reopened.export(next, 0)
            val sequences = unzip(next.toByteArray()).getValue("events.jsonl").decodeToString()
                .lineSequence().filter { it.isNotBlank() }.map {
                    kotlinx.serialization.json.Json.decodeFromString<DiagnosticEvent>(it).sequence
                }.toList()
            assertEquals(listOf(1L, 2L, 3L), sequences)
        } finally {
            reopened.close()
        }
    }

    @Test fun `full queue rejects events without waiting for blocked writer`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val callerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val pausedCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                writing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val store = DiagnosticStore(Files.createTempDirectory("oassist-full-queue").toFile(), pausedCipher)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "root"))
        var caller: Thread? = null
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            caller = thread {
                try {
                    repeat(300) {
                        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "root", content = "queued-$it"))
                    }
                } catch (error: Throwable) {
                    callerFailure.set(error)
                } finally {
                    returned.countDown()
                }
            }
            assertTrue("full queue waited for archive writer", returned.await(2, TimeUnit.SECONDS))
            assertNull(callerFailure.get())
            release.countDown()
            val output = ByteArrayOutputStream()
            store.export(output, 0)
            val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
                .lineSequence().count { it.isNotBlank() }
            assertEquals(257, events)
            assertEquals(44L, store.health().droppedEvents)
            assertEquals("queue_full", store.health().lastError)
        } finally {
            release.countDown()
            caller?.join(10000)
            store.close()
        }
    }

    @Test fun `nested discard rejects descendants during marker publication`() {
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val childReady = CountDownLatch(1)
        val releaseSanitizing = CountDownLatch(1)
        val recorded = CountDownLatch(1)
        val deleteFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val recordFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val pausedCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                if (data.decodeToString().contains("\"operationIds\"")) {
                    publishing.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val store = DiagnosticStore(Files.createTempDirectory("oassist-nested-discard-admission").toFile(), pausedCipher)
        val blockedSecrets = object : AbstractCollection<String>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String> {
                childReady.countDown()
                check(releaseSanitizing.await(10, TimeUnit.SECONDS))
                return listOf("secret").iterator()
            }
        }
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "root"))
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "discarded", "root"))
        store.export(ByteArrayOutputStream(), 0)
        val deleting = thread {
            deleteFailure.set(runCatching { store.discardOperation("discarded") }.exceptionOrNull())
        }
        var caller: Thread? = null
        var grandchild: Thread? = null
        try {
            assertTrue(publishing.await(10, TimeUnit.SECONDS))
            caller = thread {
                try {
                    store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", "discarded", content = "child-private"), blockedSecrets)
                } catch (error: Throwable) {
                    recordFailure.set(error)
                } finally {
                    childReady.countDown()
                }
            }
            assertTrue(childReady.await(2, TimeUnit.SECONDS))
            grandchild = thread {
                try {
                    store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "grandchild", "child", content = "grandchild-private"))
                } catch (error: Throwable) {
                    recordFailure.set(error)
                } finally {
                    recorded.countDown()
                }
            }
            assertTrue("record waited for deletion marker I/O", recorded.await(2, TimeUnit.SECONDS))
            release.countDown()
            deleting.join(10000)
            assertFalse(deleting.isAlive)
            assertNull(deleteFailure.get())
            assertNull(recordFailure.get())
            store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "sibling", "root", content = "sibling-kept"))
            val output = ByteArrayOutputStream()
            store.export(output, 0)
            val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
            assertFalse(events.contains("private"))
            assertTrue(events.contains("sibling-kept"))
        } finally {
            release.countDown()
            releaseSanitizing.countDown()
            deleting.join(10000)
            caller?.join(10000)
            grandchild?.join(10000)
            store.close()
        }
    }

    @Test fun `reopen rejects descendants registered before deletion markers load`() {
        val dir = Files.createTempDirectory("oassist-reopen-admission-ancestry").toFile()
        val first = DiagnosticStore(dir, cipher)
        try {
            first.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "root"))
            first.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "discarded", "root"))
            first.discardOperation("discarded")
        } finally {
            first.close()
        }
        val reopened = DiagnosticStore(dir, cipher)
        val release = CountDownLatch(1)
        val callerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val callers = mutableListOf<Thread>()
        try {
            for ((id, parent) in listOf("discarded" to "root", "child" to "discarded")) {
                val sanitizing = CountDownLatch(1)
                val blockedSecrets = object : AbstractCollection<String>() {
                    override val size: Int get() = 1
                    override fun iterator(): Iterator<String> {
                        sanitizing.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return listOf("secret").iterator()
                    }
                }
                callers += thread {
                    try {
                        reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, id, parent, content = "$id-private"), blockedSecrets)
                    } catch (error: Throwable) {
                        callerFailure.set(error)
                    }
                }
                assertTrue(sanitizing.await(10, TimeUnit.SECONDS))
            }
            reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "grandchild", "child", content = "grandchild-private"))
            val output = ByteArrayOutputStream()
            reopened.export(output, 0)
            val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
            assertFalse(events.contains("private"))
            assertTrue(events.contains("\"operationId\":\"root\""))
            release.countDown()
            callers.forEach { it.join(10000); assertFalse(it.isAlive) }
            assertNull(callerFailure.get())
            val after = ByteArrayOutputStream()
            reopened.export(after, 0)
            assertFalse(unzip(after.toByteArray()).getValue("events.jsonl").decodeToString().contains("private"))
        } finally {
            release.countDown()
            callers.forEach { it.join(10000) }
            reopened.close()
        }
    }

    @Test fun `clear and content disable reject events still being sanitized`() {
        for (disableContent in listOf(false, true)) {
            val store = DiagnosticStore(Files.createTempDirectory("oassist-admission-invalidation").toFile(), cipher)
            val sanitizing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val blockedSecrets = object : AbstractCollection<String>() {
                override val size: Int get() = 1
                override fun iterator(): Iterator<String> {
                    sanitizing.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return listOf("secret").iterator()
                }
            }
            val caller = thread {
                try {
                    store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "old", content = "old-private"), blockedSecrets)
                } catch (error: Throwable) {
                    callerFailure.set(error)
                }
            }
            try {
                assertTrue(sanitizing.await(10, TimeUnit.SECONDS))
                if (disableContent) {
                    store.setContentEnabled(false)
                    store.setContentEnabled(true)
                } else {
                    store.clear()
                }
                store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "fresh", content = "fresh-content"))
                release.countDown()
                caller.join(10000)
                assertFalse(caller.isAlive)
                assertNull(callerFailure.get())
                val output = ByteArrayOutputStream()
                store.export(output, 0)
                val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
                assertFalse(events.contains("old-private"))
                assertTrue(events.contains("fresh-content"))
                assertEquals(1L, store.health().droppedEvents)
            } finally {
                release.countDown()
                caller.join(10000)
                store.close()
            }
        }
    }

    @Test fun `media below quota does not decode the archive for eviction`() {
        val decryptions = java.util.concurrent.atomic.AtomicInteger()
        val countingCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray) = cipher.encrypt(data)
            override fun decrypt(data: ByteArray): ByteArray {
                decryptions.incrementAndGet()
                return cipher.decrypt(data)
            }
        }
        val dir = Files.createTempDirectory("oassist-media-no-eviction").toFile()
        val store = DiagnosticStore(dir, countingCipher)
        try {
            store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "old", content = "retained-content"))
            store.export(ByteArrayOutputStream(), 0)
            decryptions.set(0)
            val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
            store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "new", content = """{"image_url":"data:image/png;base64,$encoded"}"""))
        } finally {
            store.close()
        }
        assertEquals(0, decryptions.get())
        assertEquals(2, dir.listFiles()!!.count { it.extension == "event" })
        assertEquals(1, dir.listFiles()!!.count { it.extension == "media" })
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

    private fun legacyEvent(dir: File, name: String, json: String) {
        File(dir, "$name.event").writeBytes(cipher.encrypt(json.toByteArray()))
    }

    @Test fun `legacy orphan components are removed while anchored legacy chains remain`() {
        val dir = Files.createTempDirectory("oassist-legacy-orphans").toFile()
        legacyEvent(dir, "orphan-parent", """{"kind":"TOOL_RESULT","operationId":"orphan-parent","content":"orphan-parent-private"}""")
        legacyEvent(dir, "orphan-child", """{"kind":"TOOL_RESULT","operationId":"orphan-child","parentOperationId":"orphan-parent","attributes":{"status":"orphan-attribute"},"content":"orphan-child-private"}""")
        File(dir, "orphan-child.blob.media").writeBytes(cipher.encrypt("orphan-media".toByteArray()))
        legacyEvent(dir, "valid-root", """{"kind":"OPERATION_START","operationId":"valid-root"}""")
        legacyEvent(dir, "valid-child", """{"kind":"TOOL_RESULT","operationId":"valid-child","parentOperationId":"valid-root","content":"valid-private"}""")
        legacyEvent(dir, "valid-followup", """{"kind":"AGENT_ROUND","operationId":"valid-child","content":"valid-followup"}""")
        File(dir, "legacy.binding").writeBytes(cipher.encrypt("""{"kind":"STORAGE","operationId":"bound-root","attributes":{"chatId":"other-chat"}}""".toByteArray()))
        legacyEvent(dir, "bound-child", """{"kind":"TOOL_RESULT","operationId":"bound-child","parentOperationId":"bound-root","content":"bound-private"}""")

        val store = DiagnosticStore(dir, cipher)
        val output = ByteArrayOutputStream()
        store.export(output, 0, includeMedia = true)
        val archive = unzip(output.toByteArray())
        val events = archive.getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("orphan"))
        assertTrue(events.contains("valid-private"))
        assertTrue(events.contains("valid-followup"))
        assertTrue(events.contains("bound-private"))
        assertTrue(archive.keys.none { it.startsWith("media/") })
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("orphan-") })
        store.close()
    }

    @Test fun `orphan left by deletion before upgrade is excluded after reopen`() {
        val dir = Files.createTempDirectory("oassist-legacy-deleted").toFile()
        legacyEvent(dir, "leftover", """{"kind":"PROVIDER_REQUEST","operationId":"child","parentOperationId":"lost-intermediate","content":"old-deleted-chat"}""")
        val store = DiagnosticStore(dir, cipher)
        val output = ByteArrayOutputStream()
        store.export(output, 0, includeMedia = true)
        assertFalse(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("old-deleted-chat"))
        assertFalse(File(dir, "leftover.event").exists())
        store.close()
    }

    @Test fun `deleting conversation after reopen removes lost-start legacy child and media`() {
        val dir = Files.createTempDirectory("oassist-legacy-delete").toFile()
        File(dir, "chat.binding").writeBytes(cipher.encrypt("""{"kind":"STORAGE","operationId":"root","attributes":{"chatId":"chat"}}""".toByteArray()))
        legacyEvent(dir, "lost-child", """{"kind":"PROVIDER_REQUEST","operationId":"child","parentOperationId":"lost-start","content":"deleted-private"}""")
        File(dir, "lost-child.blob.media").writeBytes(cipher.encrypt("deleted-media".toByteArray()))
        val reopened = DiagnosticStore(dir, cipher)
        reopened.removeConversation("chat")
        val output = ByteArrayOutputStream()
        reopened.export(output, 0, includeMedia = true)
        val archive = unzip(output.toByteArray())
        assertFalse(archive.getValue("events.jsonl").decodeToString().contains("deleted-private"))
        assertTrue(archive.keys.none { it.startsWith("media/") })
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("lost-child") })
        reopened.close()
    }

    @Test fun `new standalone event remains exportable after reopen`() {
        val dir = Files.createTempDirectory("oassist-self-root").toFile()
        val first = DiagnosticStore(dir, cipher)
        first.record(DiagnosticEvent(DiagnosticKind.PROVIDER_ERROR, "standalone", content = "standalone-private"))
        first.export(ByteArrayOutputStream(), 0)
        first.close()
        val reopened = DiagnosticStore(dir, cipher)
        val output = ByteArrayOutputStream()
        reopened.export(output, 0)
        val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
        assertTrue(events.contains("standalone-private"))
        assertTrue(events.contains("\"rootOperationId\":\"standalone\""))
        reopened.close()
    }

    @Test fun `partial conflict cleanup never reclassifies surviving private records`() {
        val dir = Files.createTempDirectory("oassist-legacy-partial-conflict").toFile()
        legacyEvent(dir, "root-a", """{"kind":"OPERATION_START","operationId":"root-a"}""")
        legacyEvent(dir, "root-b", """{"kind":"OPERATION_START","operationId":"root-b"}""")
        legacyEvent(dir, "conflict-a", """{"kind":"TOOL_RESULT","operationId":"conflict","parentOperationId":"root-a","content":"private-a"}""")
        legacyEvent(dir, "conflict-b", """{"kind":"TOOL_RESULT","operationId":"conflict","parentOperationId":"root-b","content":"private-b"}""")
        legacyEvent(dir, "child", """{"kind":"TOOL_RESULT","operationId":"child","parentOperationId":"conflict","content":"private-child"}""")
        legacyEvent(dir, "sibling", """{"kind":"TOOL_RESULT","operationId":"sibling","parentOperationId":"root-b","content":"retained-sibling"}""")
        val selectiveDelete: (File) -> Boolean = { it.name == "conflict-a.event" && it.delete() }
        val store = DiagnosticStore(dir, cipher, deleteFile = selectiveDelete)
        val first = ByteArrayOutputStream()
        store.export(first, 0)
        assertFalse(File(dir, "conflict-a.event").exists())
        assertTrue(File(dir, "conflict-b.event").exists())
        val firstEvents = unzip(first.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(firstEvents.contains("private"))
        assertTrue(firstEvents.contains("retained-sibling"))
        store.close()
        val reopened = DiagnosticStore(dir, cipher, deleteFile = { false })
        val second = ByteArrayOutputStream()
        reopened.export(second, 0)
        val secondEvents = unzip(second.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(secondEvents.contains("private"))
        assertTrue(secondEvents.contains("retained-sibling"))
        reopened.close()
    }

    @Test fun `conflicting ownership and unanchored cycle are excluded without losing unrelated roots`() {
        val dir = Files.createTempDirectory("oassist-legacy-conflict").toFile()
        legacyEvent(dir, "root-a", """{"kind":"OPERATION_START","operationId":"root-a"}""")
        legacyEvent(dir, "root-b", """{"kind":"OPERATION_START","operationId":"root-b"}""")
        legacyEvent(dir, "conflict-a", """{"kind":"TOOL_RESULT","operationId":"conflict","parentOperationId":"root-a","content":"conflict-private"}""")
        legacyEvent(dir, "conflict-b", """{"kind":"TOOL_RESULT","operationId":"conflict","parentOperationId":"root-b"}""")
        legacyEvent(dir, "descendant", """{"kind":"TOOL_RESULT","operationId":"descendant","parentOperationId":"conflict","content":"descendant-private"}""")
        legacyEvent(dir, "cycle-a", """{"kind":"TOOL_RESULT","operationId":"cycle-a","parentOperationId":"cycle-b","content":"cycle-private"}""")
        legacyEvent(dir, "cycle-b", """{"kind":"TOOL_RESULT","operationId":"cycle-b","parentOperationId":"cycle-a"}""")
        legacyEvent(dir, "sibling", """{"kind":"TOOL_RESULT","operationId":"sibling","parentOperationId":"root-a","content":"sibling-retained"}""")
        val store = DiagnosticStore(dir, cipher)
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("conflict-private"))
        assertFalse(events.contains("descendant-private"))
        assertFalse(events.contains("cycle-private"))
        assertTrue(events.contains("sibling-retained"))
        store.close()
    }

    @Test fun `failed orphan cleanup never exposes event or media`() {
        val dir = Files.createTempDirectory("oassist-orphan-delete-failure").toFile()
        legacyEvent(dir, "leftover", """{"kind":"TOOL_RESULT","operationId":"orphan","attributes":{"status":"private-attribute"},"content":"private-content"}""")
        File(dir, "leftover.blob.media").writeBytes(cipher.encrypt("private-media".toByteArray()))
        val store = DiagnosticStore(dir, cipher, deleteFile = { false })
        val output = ByteArrayOutputStream()
        store.export(output, 0, includeMedia = true)
        val archive = unzip(output.toByteArray())
        assertFalse(archive.getValue("events.jsonl").decodeToString().contains("private"))
        assertTrue(archive.keys.none { it.startsWith("media/") })
        assertTrue(File(dir, "leftover.event").exists())
        assertEquals("delete_failed", store.health().lastError)
        store.close()
        val reopened = DiagnosticStore(dir, cipher, deleteFile = { false })
        val again = ByteArrayOutputStream()
        reopened.export(again, 0, includeMedia = true)
        assertFalse(unzip(again.toByteArray()).getValue("events.jsonl").decodeToString().contains("private"))
        reopened.close()
    }

    @Test fun `deletion marker survives failed cleanup and reopen while siblings remain`() {
        var now = System.currentTimeMillis()
        val dir = Files.createTempDirectory("oassist-delete-marker").toFile()
        val first = DiagnosticStore(dir, cipher, clock = { now }, deleteFile = { false })
        first.bindConversation("chat", "root")
        val encoded = Base64.getEncoder().encodeToString("private-media".toByteArray())
        first.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "child", "root"))
        first.record(DiagnosticEvent(DiagnosticKind.PROVIDER_REQUEST, "child", content = """{"image_url":"data:image/png;base64,$encoded","detail":"private-content"}"""))
        first.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "other", content = "other-content"))
        first.export(ByteArrayOutputStream(), 0)
        first.removeConversation("chat")
        assertEquals("delete_failed", first.health().lastError)
        assertTrue(dir.listFiles()!!.any { it.extension == "deleted" })
        assertTrue(dir.listFiles()!!.any { it.extension == "media" })
        first.close()

        now += 8L * 24 * 60 * 60 * 1000
        val reopened = DiagnosticStore(dir, cipher, clock = { now }, deleteFile = { false })
        reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "late", "child", content = "late-private"))
        val output = ByteArrayOutputStream()
        reopened.export(output, 0, includeMedia = true)
        val archive = unzip(output.toByteArray())
        val events = archive.getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("private"))
        assertTrue(events.contains("other-content"))
        assertTrue(archive.keys.none { it.startsWith("media/") })
        assertTrue(dir.listFiles()!!.any { it.extension == "deleted" })
        reopened.close()

        val retry = DiagnosticStore(dir, cipher, clock = { now })
        retry.export(ByteArrayOutputStream(), 0, includeMedia = true)
        assertTrue(dir.listFiles()!!.none { it.extension == "media" })
        retry.close()
    }

    @Test fun `marker publication failure leaves owned files untouched`() {
        val dir = Files.createTempDirectory("oassist-marker-failure").toFile()
        val failingCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                if (data.decodeToString().contains("\"operationIds\"")) error("marker failure")
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val store = DiagnosticStore(dir, failingCipher)
        store.bindConversation("chat", "root")
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "root", content = "retained-private"))
        store.export(ByteArrayOutputStream(), 0)
        val before = dir.listFiles()!!.map { it.name }.toSet()
        assertTrue(runCatching { store.removeConversation("chat") }.isFailure)
        assertEquals(before, dir.listFiles()!!.map { it.name }.toSet())
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "root", content = "after-failed-deletion"))
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        assertTrue(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("retained-private"))
        assertTrue(unzip(output.toByteArray()).getValue("events.jsonl").decodeToString().contains("after-failed-deletion"))
        store.close()
    }

    @Test fun `unreadable deletion marker fails export closed`() {
        val dir = Files.createTempDirectory("oassist-corrupt-marker").toFile()
        val store = DiagnosticStore(dir, cipher)
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "root", content = "private"))
        store.export(ByteArrayOutputStream(), 0)
        store.close()
        File(dir, "corrupt.deleted").writeBytes(byteArrayOf(1, 2, 3))
        val reopened = DiagnosticStore(dir, cipher)
        assertFalse(reopened.health().available)
        assertEquals("deletion_marker_unreadable", reopened.health().lastError)
        val failure = runCatching { reopened.export(ByteArrayOutputStream(), 0) }.exceptionOrNull()
        assertEquals("deletion_marker_unreadable", failure?.message)
        assertTrue(runCatching { reopened.bindConversation("chat", "new-root") }.isSuccess)
        reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "new-root", content = "new-private"))
        assertTrue(runCatching { reopened.export(ByteArrayOutputStream(), 0) }.isFailure)
        reopened.close()
    }

    @Test fun `in flight export is invalidated before marker publication`() {
        val markerStarted = CountDownLatch(1)
        val releaseMarker = CountDownLatch(1)
        val pausedCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                if (data.decodeToString().contains("\"operationIds\"")) {
                    markerStarted.countDown()
                    check(releaseMarker.await(10, TimeUnit.SECONDS))
                }
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val store = DiagnosticStore(Files.createTempDirectory("oassist-delete-export-race").toFile(), pausedCipher)
        store.bindConversation("chat", "root")
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "root", content = "private"))
        store.export(ByteArrayOutputStream(), 0)
        val exportStarted = CountDownLatch(1)
        val releaseExport = CountDownLatch(1)
        val output = object : java.io.FilterOutputStream(ByteArrayOutputStream()) {
            override fun write(value: Int) { exportStarted.countDown(); check(releaseExport.await(10, TimeUnit.SECONDS)); out.write(value) }
            override fun write(value: ByteArray, offset: Int, length: Int) { exportStarted.countDown(); check(releaseExport.await(10, TimeUnit.SECONDS)); out.write(value, offset, length) }
        }
        val exportFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val deleteFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val generation = DiagnosticStore::class.java.getDeclaredField("generation").apply { isAccessible = true }
            .get(store) as java.util.concurrent.atomic.AtomicLong
        val beforeDelete = generation.get()
        val exporter = thread { exportFailure.set(runCatching { store.export(output, 0) }.exceptionOrNull()) }
        var deleter: Thread? = null
        try {
            assertTrue(exportStarted.await(10, TimeUnit.SECONDS))
            deleter = thread { deleteFailure.set(runCatching { store.removeConversation("chat") }.exceptionOrNull()) }
            assertTrue(markerStarted.await(10, TimeUnit.SECONDS))
            assertTrue(generation.get() > beforeDelete)
            releaseExport.countDown()
        } finally {
            releaseExport.countDown()
            releaseMarker.countDown()
            exporter.join(10000)
            deleter?.join(10000)
        }
        assertFalse(deleter!!.isAlive)
        assertFalse(exporter.isAlive)
        assertEquals("export_invalidated", exportFailure.get()?.message)
        assertNull(deleteFailure.get())
        store.close()
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

    @Test fun `escaped known secrets are removed from JSON and malformed bodies in export`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-escaped-secret").toFile(), cipher)
        val secret = "marker-secret"
        val bodies = listOf(
            """{"detail":"\u006darker-secret","nested":["marker-\u0073ecret"],"\u006darker-secret":"visible","count":42}""",
            """{"detail":"\u006darker-\u0073ecret""",
            """{"detail":"marker-secret","ok":true}""",
        )
        bodies.forEachIndexed { index, body ->
            store.record(DiagnosticEvent(DiagnosticKind.PROVIDER_ERROR, "run-$index", content = body), listOf(secret))
        }
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains(secret))
        assertFalse(events.contains("\\u006d"))
        assertTrue(events.contains("visible"))
        assertTrue(events.contains("count"))
        assertTrue(events.contains("REDACTED"))
        store.close()
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

    @Test fun `lost intermediate start retains ownership across reopen and conversation deletion`() {
        val dir = Files.createTempDirectory("oassist-lost-start").toFile()
        val failingCipher = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray): ByteArray {
                if (data.decodeToString().contains("fail-intermediate-start")) error("injected write failure")
                return cipher.encrypt(data)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val first = DiagnosticStore(dir, failingCipher)
        first.bindConversation("chat", "root")
        first.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "intermediate", "root", attributes = mapOf("marker" to "fail-intermediate-start")))
        first.export(ByteArrayOutputStream(), 0)
        assertEquals("write_failed_IllegalStateException", first.health().lastError)
        val encoded = Base64.getEncoder().encodeToString("private-media".toByteArray())
        first.record(DiagnosticEvent(DiagnosticKind.PROVIDER_ERROR, "child", "intermediate", content = """{"detail":"\u006darker-secret","image_url":"data:image/png;base64,$encoded"}"""), listOf("marker-secret"))
        first.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "other", content = "other-content"))
        first.export(ByteArrayOutputStream(), 0)
        first.close()

        val reopened = DiagnosticStore(dir, cipher)
        val before = ByteArrayOutputStream()
        reopened.export(before, 0, includeMedia = true, operationIds = setOf("root"))
        val entries = unzip(before.toByteArray())
        val events = entries.getValue("events.jsonl").decodeToString()
        assertTrue(events.contains("REDACTED"))
        assertFalse(events.contains("marker-secret"))
        assertTrue(entries.filterKeys { it.startsWith("media/") }.values.any { it.decodeToString() == "private-media" })
        reopened.removeConversation("chat")
        reopened.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "late", "child", content = "late-private"))
        reopened.record(DiagnosticEvent(DiagnosticKind.AGENT_ROUND, "intermediate", content = "late-intermediate-private"))
        val after = ByteArrayOutputStream()
        reopened.export(after, 0, includeMedia = true)
        val remaining = unzip(after.toByteArray())
        val remainingEvents = remaining.getValue("events.jsonl").decodeToString()
        assertFalse(remainingEvents.contains("child"))
        assertFalse(remainingEvents.contains("late-private"))
        assertFalse(remainingEvents.contains("late-intermediate-private"))
        assertTrue(remainingEvents.contains("other-content"))
        assertTrue(remaining.keys.none { it.startsWith("media/") })
        reopened.close()
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

    @Test fun `discarding a child keeps its parent and siblings writable`() {
        val store = DiagnosticStore(Files.createTempDirectory("oassist-discard-child").toFile(), cipher)
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "root"))
        store.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "parent", "root"))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "child", "parent", content = "discarded"))
        store.export(ByteArrayOutputStream(), 0)
        store.discardOperation("child")
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "parent", content = "parent-kept"))
        store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "sibling", "parent", content = "sibling-kept"))
        val output = ByteArrayOutputStream()
        store.export(output, 0)
        val events = unzip(output.toByteArray()).getValue("events.jsonl").decodeToString()
        assertFalse(events.contains("discarded"))
        assertTrue(events.contains("parent-kept"))
        assertTrue(events.contains("sibling-kept"))
        store.close()
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

    @Test fun `warm bind conversation performs no archive decrypts`() {
        val dir = Files.createTempDirectory("oassist-bind-warm").toFile()
        val decrypts = java.util.concurrent.atomic.AtomicInteger()
        val counting = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray) = cipher.encrypt(data)
            override fun decrypt(data: ByteArray): ByteArray {
                decrypts.incrementAndGet()
                return cipher.decrypt(data)
            }
        }
        val store = DiagnosticStore(dir, counting)
        repeat(20) { index -> store.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "run-$index", content = "payload-$index")) }
        store.export(ByteArrayOutputStream(), 0)
        decrypts.set(0)
        store.bindConversation("chat", "bound")
        assertEquals("bind must not rescan the archive", 0, decrypts.get())
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".binding") })
        store.close()
    }

    @Test fun `bind conversation does not wait for an in-flight archive scan`() {
        val dir = Files.createTempDirectory("oassist-bind-cold").toFile()
        val seed = DiagnosticStore(dir, cipher)
        repeat(20) { index -> seed.record(DiagnosticEvent(DiagnosticKind.TOOL_RESULT, "seed-$index", content = "seed-$index")) }
        seed.export(ByteArrayOutputStream(), 0)
        seed.close()

        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val blocking = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray) = cipher.encrypt(data)
            override fun decrypt(data: ByteArray): ByteArray {
                scanStarted.countDown()
                check(releaseScan.await(10, TimeUnit.SECONDS))
                return cipher.decrypt(data)
            }
        }
        val store = DiagnosticStore(dir, blocking)
        try {
            assertTrue(scanStarted.await(10, TimeUnit.SECONDS))
            val binder = thread { store.bindConversation("chat", "fresh") }
            binder.join(5000)
            assertFalse("bindConversation must not wait for the archive scan", binder.isAlive)
            assertTrue(dir.listFiles()!!.any { it.name.endsWith(".binding") })
        } finally {
            releaseScan.countDown()
        }
        store.close()
    }

    @Test fun `deferred maintenance still prunes expired bindings`() {
        var now = System.currentTimeMillis()
        val dir = Files.createTempDirectory("oassist-bind-deferred").toFile()
        val seed = DiagnosticStore(dir, cipher, clock = { now })
        seed.bindConversation("chat", "expired")
        seed.record(DiagnosticEvent(DiagnosticKind.OPERATION_START, "expired"))
        seed.record(DiagnosticEvent(DiagnosticKind.OPERATION_END, "expired"))
        seed.export(ByteArrayOutputStream(), 0)
        seed.close()

        now += 8L * 24 * 60 * 60 * 1000
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val blocking = object : DiagnosticCipher {
            override fun encrypt(data: ByteArray) = cipher.encrypt(data)
            override fun decrypt(data: ByteArray): ByteArray {
                scanStarted.countDown()
                check(releaseScan.await(10, TimeUnit.SECONDS))
                return cipher.decrypt(data)
            }
        }
        val store = DiagnosticStore(dir, blocking, clock = { now })
        try {
            assertTrue(scanStarted.await(10, TimeUnit.SECONDS))
            store.bindConversation("chat", "fresh")
            // Холодный bind не чистит архив синхронно: оба бинда ещё на диске.
            assertEquals(2, dir.listFiles()!!.count { it.extension == "binding" })
            releaseScan.countDown()
            // Экспорт ждёт writer: отложенное обслуживание выполнено к этому моменту.
            store.export(ByteArrayOutputStream(), 0)
            assertEquals(1, dir.listFiles()!!.count { it.extension == "binding" })
        } finally {
            releaseScan.countDown()
        }
        store.close()
    }

    @Test fun `rebinding the same operation writes a single binding`() {
        val dir = Files.createTempDirectory("oassist-bind-dup").toFile()
        val store = DiagnosticStore(dir, cipher)
        store.export(ByteArrayOutputStream(), 0)
        store.bindConversation("chat", "run")
        store.bindConversation("chat", "run")
        store.bindConversation("chat", "run")
        assertEquals(1, dir.listFiles()!!.count { it.extension == "binding" })
        store.close()
    }
}
