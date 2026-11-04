package ru.outsideproarts.oassist.update

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCacheMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 2_000_000_000L
    private val old = now - 8L * 24 * 60 * 60 * 1000
    private val store = mutableMapOf<String, Any>()
    private var writesSucceed = true
    private fun maintenance(dir: File) = UpdateCacheMaintenance(dir, { store.toMap() }, { names ->
        if (!writesSucceed) false else {
            store[UpdateCacheMaintenance.MARKER] = true
            store[UpdateCacheMaintenance.NAMES] = names.toSet()
            true
        }
    }, { now })
    private fun file(dir: File, name: String, timestamp: Long = old) = File(dir, name).apply {
        writeText("fixture")
        assertTrue(setLastModified(timestamp))
    }

    @Test fun legacyAndHandedOffFilesSurviveRestartWhileOldUnhandedFilesAreRemoved() {
        val dir = temporary.newFolder()
        val legacy = file(dir, "legacy.apk")
        val first = maintenance(dir)
        first.run()
        val handed = file(dir, "handed.apk")
        assertTrue(first.protect(handed.name))
        val stale = file(dir, "stale.apk")
        val fresh = file(dir, "fresh.apk", now)
        val boundary = file(dir, "boundary.apk", now - 7L * 24 * 60 * 60 * 1000)
        val part = file(dir, ".stale.apk.12345678-1234-1234-1234-123456789abc.part")
        val unrelated = file(dir, ".stale.apk.invalid.part")
        val subdirectory = File(dir, "nested.apk").apply { mkdir() }
        val restarted = maintenance(dir)
        restarted.run()
        assertTrue(legacy.exists())
        assertTrue(handed.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        assertTrue(subdirectory.isDirectory)
        assertFalse(stale.exists())
        assertFalse(boundary.exists())
        assertFalse(part.exists())
        assertFalse(restarted.mayReplace(handed.name))
    }

    @Test fun missingProtectedFileCannotBeReplacedAndFailedCommitPreventsHandoff() {
        val dir = temporary.newFolder()
        val first = maintenance(dir)
        first.run()
        assertTrue(first.protect("protected.apk"))
        val restarted = maintenance(dir)
        restarted.run()
        assertFalse(restarted.mayReplace("protected.apk"))
        writesSucceed = false
        assertFalse(restarted.protect("another.apk"))
        assertFalse(restarted.protect("another.apk"))
        assertFalse(restarted.mayReplace("another.apk"))
    }

    @Test fun invalidMetadataOrFailedLegacySnapshotNeverDeletesApks() {
        val dir = temporary.newFolder()
        val legacy = file(dir, "legacy.apk")
        writesSucceed = false
        val failed = maintenance(dir)
        failed.run()
        assertTrue(legacy.exists())
        assertFalse(failed.mayReplace(legacy.name))
        writesSucceed = true
        store[UpdateCacheMaintenance.MARKER] = true
        store[UpdateCacheMaintenance.NAMES] = "corrupt"
        val corrupt = maintenance(dir)
        corrupt.run()
        assertTrue(legacy.exists())
        assertFalse(corrupt.protect(legacy.name))
        assertFalse(corrupt.mayReplace(legacy.name))
    }

    @Test fun handoffRequiresDurableProtectionBeforeLaunch() = runTest {
        val dir = temporary.newFolder()
        val owner = maintenance(dir)
        owner.run()
        val apk = file(dir, "update.apk", now)
        var launches = 0
        var failures = 0
        suspend fun attempt() = handOffUpdate(
            { PreparedUpdate(apk, 1) }, { true },
            { check(owner.protect(it.file.name)); launches++ }, {}, { failures++ },
        )
        writesSucceed = false
        attempt()
        assertEquals(0, launches)
        assertEquals(1, failures)
        writesSucceed = true
        val reopened = maintenance(dir)
        reopened.run()
        handOffUpdate({ PreparedUpdate(apk, 1) }, { true }, {
            check(reopened.protect(it.file.name))
            assertFalse(maintenance(dir).also { next -> next.run() }.mayReplace(it.file.name))
            launches++
        }, {}, { fail("Unexpected handoff failure") })
        assertEquals(1, launches)
    }

    @Test fun concurrentProtectionRetainsBothNames() {
        val dir = temporary.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockNextRead = false
        val owner = UpdateCacheMaintenance(dir, {
            val snapshot = store.toMap()
            if (blockNextRead) {
                blockNextRead = false
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            snapshot
        }, { names ->
            store[UpdateCacheMaintenance.MARKER] = true
            store[UpdateCacheMaintenance.NAMES] = names.toSet()
            true
        }, { now })
        owner.run()
        blockNextRead = true
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> { owner.protect("first.apk") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var secondResult = false
            val secondStarted = CountDownLatch(1)
            val second = Thread { secondStarted.countDown(); secondResult = owner.protect("second.apk") }
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertTrue(second.state == Thread.State.BLOCKED || !second.isAlive)
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            second.join(5000)
            assertFalse(second.isAlive)
            assertTrue(secondResult)
            assertEquals(setOf("first.apk", "second.apk"), store[UpdateCacheMaintenance.NAMES])
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun lateDownloadCommitCannotReplaceAProtectedApk() {
        val dir = temporary.newFolder()
        val owner = maintenance(dir)
        owner.run()
        val target = file(dir, "update.apk", now)
        val part = file(dir, ".update.apk.12345678-1234-1234-1234-123456789abc.part", now)
        target.writeText("installer copy")
        part.writeText("new download")
        assertTrue(owner.protect(target.name))
        try {
            owner.commitPart(part, target)
            fail("Protected APK must not be replaced")
        } catch (_: IllegalStateException) { }
        assertEquals("installer copy", target.readText())
        assertEquals("new download", part.readText())
    }

    @Test fun unprotectedDownloadCommitReplacesOnlyItsOwnTarget() {
        val dir = temporary.newFolder()
        val owner = maintenance(dir)
        owner.run()
        val target = file(dir, "update.apk", now)
        val part = file(dir, ".update.apk.12345678-1234-1234-1234-123456789abc.part", now)
        part.writeText("complete download")
        val outside = file(temporary.newFolder(), "update.apk", now)
        try {
            owner.commitPart(part, outside)
            fail("Outside target must be refused")
        } catch (_: IllegalStateException) { }
        assertEquals("fixture", outside.readText())
        owner.commitPart(part, target)
        assertEquals("complete download", target.readText())
        assertFalse(part.exists())
    }
}
