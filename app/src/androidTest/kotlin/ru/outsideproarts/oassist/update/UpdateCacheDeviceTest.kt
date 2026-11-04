package ru.outsideproarts.oassist.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.net.update.GitHubUpdateTransport

@RunWith(AndroidJUnit4::class)
class UpdateCacheDeviceTest {
    @Test fun persistedProtectionAndProviderSurviveManagerRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val id = "update-device-${UUID.randomUUID()}"
        val root = File(base.cacheDir, "updates/$id").apply { check(mkdirs()) }
        val updates = File(root, "updates").apply { check(mkdirs()) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isolated = object : ContextWrapper(base) {
            override fun getCacheDir() = root
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                return base.getSharedPreferences("$id-$name", mode)
            }
        }
        fun manager(beforeMaintenance: suspend () -> Unit = {}) = UpdateManager(isolated, GitHubUpdateTransport(HttpClient(MockEngine) {
            engine { addHandler { error("Unexpected network request") } }
        }), beforeMaintenance)
        fun start(manager: UpdateManager) = instrumentation.runOnMainSync { manager.start() }
        fun awaitReady(manager: UpdateManager) = runBlocking {
            withTimeout(10_000) { manager.state.first { it.preferences != null } }
        }
        // Android 12 File.lastModified() truncates milliseconds to whole seconds.
        val staleTime = (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)) / 1_000 * 1_000
        fun age(file: File) {
            assertTrue("Could not age ${file.name}", file.setLastModified(staleTime))
            val modified = file.lastModified()
            assertTrue("${file.name}: expected a positive stale timestamp, got $modified", modified in 1..staleTime)
        }
        var first: UpdateManager? = null
        var second: UpdateManager? = null
        try {
            val legacy = File(updates, "legacy.apk").apply { writeText("legacy") }
            val orphan = File(updates, ".orphan.apk.12345678-1234-1234-1234-123456789abc.part").apply { writeText("part") }
            val firstManager = manager { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            first = firstManager
            start(firstManager)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { firstManager.download() }
            assertNull(firstManager.state.value.preferences)
            assertTrue(orphan.exists())
            release.countDown()
            awaitReady(firstManager)
            assertFalse(orphan.exists())
            val handed = File(updates, "handed.apk").apply { writeText("pending installer") }
            assertTrue(firstManager.protectInstall(PreparedUpdate(handed, 0)))
            val persisted = File(isolated.noBackupFilesDir, "update-cache-ownership.json")
            assertTrue(persisted.readText().contains("handed.apk"))
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.updates", handed)
            assertEquals("pending installer", base.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val stale = File(updates, "stale.apk").apply { writeText("stale"); age(this) }
            age(legacy)
            age(handed)
            instrumentation.runOnMainSync { firstManager.close() }
            first = null
            val secondManager = manager()
            second = secondManager
            start(secondManager)
            awaitReady(secondManager)
            assertFalse(stale.exists())
            assertTrue(legacy.exists())
            assertEquals("pending installer", base.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertEquals(setOf("legacy.apk", "handed.apk"), UpdateCacheOwnership(persisted).read()[UpdateCacheMaintenance.NAMES])
            instrumentation.runOnMainSync { secondManager.close() }
            second = null
            persisted.writeText("corrupt")
            val retained = File(updates, "retained.apk").apply { writeText("retained"); age(this) }
            val third = manager()
            second = third
            start(third)
            awaitReady(third)
            assertTrue(retained.exists())
            assertFalse(third.protectInstall(PreparedUpdate(handed, 0)))
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { first?.close(); second?.close() }
            require(root.canonicalFile.toPath().startsWith(File(base.cacheDir, "updates").canonicalFile.toPath()))
            check(root.deleteRecursively())
            base.deleteSharedPreferences("$id-update_state")
        }
    }

    @Test fun atomicFileRestoresBackupAndRejectsUnwritableTarget() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "update-atomic-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val path = File(root, "ledger.json")
            assertTrue(UpdateCacheOwnership(path).write(setOf("protected.apk")))
            check(path.renameTo(File("${path.path}.bak")))
            path.writeText("interrupted")
            assertEquals(setOf("protected.apk"), UpdateCacheOwnership(path).read()[UpdateCacheMaintenance.NAMES])
            val invalidParent = File(root, "file-parent").apply { writeText("block") }
            assertFalse(UpdateCacheOwnership(File(invalidParent, "ledger.json")).write(setOf("new.apk")))
            assertEquals(setOf("protected.apk"), UpdateCacheOwnership(path).read()[UpdateCacheMaintenance.NAMES])
        } finally {
            require(root.canonicalFile.toPath().startsWith(base.cacheDir.canonicalFile.toPath()))
            check(root.deleteRecursively())
        }
    }
}
