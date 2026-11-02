package ru.outsideproarts.oassist.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.update.*

class UpdateCheckCoordinatorTest {
    private val installed = InstalledBuild("ru.outsideproarts.oassist", 100, "1.1", false, "a".repeat(40),
        "b".repeat(64), 36, listOf("arm64-v8a"))
    private fun candidate(code: Long, channel: UpdateChannel = UpdateChannel.STABLE) = UpdateCandidate(
        UpdateManifest(1, installed.applicationId, code, "1.2.0", channel, "c".repeat(40), 31, emptyList(),
            installed.certificateSha256, "d".repeat(64), 123, "oassist-$code.apk",
            if (channel == UpdateChannel.DEVELOPMENT) "refs/heads/master" else "refs/tags/v1.2.0"),
        "https://github.com/outsidepro-arts/OAssist/releases/download/v1.2.0/oassist-$code.apk", "Notes",
    )

    @Test fun newestCompatibleStopsBeforeDownloadingOlderMetadata() = runTest {
        var olderRequested = false
        val newest = candidate(102)
        val checker = UpdateCheckCoordinator({ _, _ -> flow {
            emit(newest)
            olderRequested = true
            emit(candidate(101))
        } }, { _, _ -> true })
        assertEquals(UpdateCheckCoordinator.Result.Available(newest), checker.find(installed, UpdateChannel.STABLE, checker.token()))
        assertFalse(olderRequested)
    }

    @Test fun releaseFromOlderTreeCannotUpgradeDevelopmentBuild() = runTest {
        val newestOldTree = candidate(103)
        val compatible = candidate(102).let { it.copy(manifest = it.manifest.copy(commitSha = "e".repeat(40))) }
        val checker = UpdateCheckCoordinator({ _, _ -> flowOf(newestOldTree, compatible) }, { _, target -> target == "e".repeat(40) })
        assertEquals(UpdateCheckCoordinator.Result.Available(compatible), checker.find(installed.copy(debug = true), UpdateChannel.STABLE, checker.token()))
    }

    @Test fun settingsChangeDuringAncestryCheckRejectsLateResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val checker = UpdateCheckCoordinator({ _, _ -> flowOf(candidate(101)) }, { _, _ -> entered.complete(Unit); finish.await(); true })
        val job = async { checker.find(installed, UpdateChannel.STABLE, checker.token()) }
        entered.await()
        checker.invalidate()
        finish.complete(Unit)
        assertEquals(UpdateCheckCoordinator.Result.Obsolete, job.await())
    }

    @Test fun staleQueuedCheckDoesNotReadCatalog() = runTest {
        val checker = UpdateCheckCoordinator({ _, _ -> error("obsolete request") }, { _, _ -> true })
        val token = checker.token()
        checker.invalidate()
        assertEquals(UpdateCheckCoordinator.Result.Obsolete, checker.find(installed, UpdateChannel.STABLE, token))
    }

    @Test fun startingDownloadInvalidatesSuspendedCatalogRefresh() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val checker = UpdateCheckCoordinator({ _, _ -> flow {
            entered.complete(Unit)
            finish.await()
            emit(candidate(102))
        } }, { _, _ -> true })
        val refresh = async { checker.find(installed, UpdateChannel.STABLE, checker.token()) }
        entered.await()
        checker.invalidate()
        finish.complete(Unit)
        assertEquals(UpdateCheckCoordinator.Result.Obsolete, refresh.await())
    }

    @Test fun sameCommitDevelopmentRebuildIsNotNewCommit() = runTest {
        val same = candidate(101, UpdateChannel.DEVELOPMENT).let { it.copy(manifest = it.manifest.copy(commitSha = installed.commitSha)) }
        val checker = UpdateCheckCoordinator({ _, _ -> flowOf(same) }, { _, _ -> error("No comparison needed") })
        assertEquals(UpdateCheckCoordinator.Result.NoUpdate, checker.find(installed, UpdateChannel.DEVELOPMENT, checker.token()))
    }

    @Test fun releaseOfSameCommitCanReplaceDevelopmentBuild() = runTest {
        val release = candidate(101).let { it.copy(manifest = it.manifest.copy(commitSha = installed.commitSha)) }
        val checker = UpdateCheckCoordinator({ _, _ -> flowOf(release) }, { _, _ -> error("No comparison needed") })
        assertEquals(UpdateCheckCoordinator.Result.Available(release), checker.find(installed.copy(debug = true), UpdateChannel.STABLE, checker.token()))
    }

    @Test fun failedComparisonIsNotReportedAsNoUpdate() = runTest {
        val checker = UpdateCheckCoordinator({ _, _ -> flowOf(candidate(101)) }, { _, _ -> throw java.io.IOException("Offline") })
        try { checker.find(installed, UpdateChannel.STABLE, checker.token()); fail("Expected error") }
        catch (_: java.io.IOException) { }
    }

    @Test fun cancellingCheckDoesNotPublishOrPreventNextCheck() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var first = true
        val checker = UpdateCheckCoordinator({ _, _ -> flow {
            if (first) { first = false; entered.complete(Unit); resume.await() }
            emit(candidate(101))
        } }, { _, _ -> true })
        val job = async { checker.find(installed, UpdateChannel.STABLE, checker.token()) }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(checker.find(installed, UpdateChannel.STABLE, checker.token()) is UpdateCheckCoordinator.Result.Available)
    }
}
