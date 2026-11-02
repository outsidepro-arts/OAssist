package ru.outsideproarts.oassist.core.model.update

import org.junit.Assert.*
import org.junit.Test

class UpdateSelectorTest {
    private val sha = "a".repeat(40)
    private val hash = "b".repeat(64)
    private val installed = InstalledBuild("app.example", 10, "1.2", false, sha, hash, 34, listOf("arm64-v8a"))
    private fun candidate(version: String = "1.3.0", code: Long = 11, channel: UpdateChannel = UpdateChannel.STABLE): UpdateCandidate {
        val ref = if (channel == UpdateChannel.DEVELOPMENT) "refs/heads/master" else "refs/tags/v$version"
        return UpdateCandidate(UpdateManifest(1, "app.example", code, version, channel, sha, 31, listOf("arm64-v8a"), hash, hash, 100, "app.apk", ref), "https://example.com/app.apk", "")
    }
    private fun select(vararg candidates: UpdateCandidate, build: InstalledBuild = installed, channel: UpdateChannel = UpdateChannel.DEVELOPMENT) =
        UpdateSelector.eligibleCandidates(candidates.toList(), build, channel)

    @Test fun defaultsAndManualSelectionSurviveBuildChanges() {
        val prefs = UpdatePreferences()
        assertTrue(prefs.automaticChecks)
        assertFalse(prefs.notifications)
        assertEquals(UpdateChannel.STABLE, prefs.effectiveChannel(installed))
        assertEquals(UpdateChannel.PRERELEASE, prefs.effectiveChannel(installed.copy(versionName = "1.3.0-rc.1")))
        assertEquals(UpdateChannel.DEVELOPMENT, prefs.effectiveChannel(installed.copy(debug = true)))
        val manual = prefs.copy(manualChannel = UpdateChannel.STABLE)
        assertEquals(UpdateChannel.STABLE, manual.effectiveChannel(installed.copy(debug = true)))
        assertEquals(UpdateChannelReason.MANUAL, manual.channelReason(installed.copy(debug = true)))
    }

    @Test fun semanticAndCounterGuards() {
        assertTrue(select(candidate("1.1.0")).isEmpty())
        assertTrue(select(candidate("1.2.0")).isEmpty())
        assertTrue(select(candidate("2.0.0", 9)).isEmpty())
        assertEquals(1, select(candidate("1.2.0"), build = installed.copy(debug = true)).size)
        assertEquals(1, select(candidate("1.3.0-rc.1", channel = UpdateChannel.PRERELEASE), build = installed.copy(versionName = "1.3.0-beta2")).size)
        assertTrue(select(candidate("1.3.0-beta2", channel = UpdateChannel.PRERELEASE), build = installed.copy(versionName = "1.3.0-rc.1")).isEmpty())
    }

    @Test fun channelsAndNewestFirst() {
        val stable = candidate(code = 11)
        val pre = candidate("1.4.0-beta", 12, UpdateChannel.PRERELEASE)
        val dev = candidate("1.2", 13, UpdateChannel.DEVELOPMENT)
        assertEquals(listOf(stable), select(stable, pre, dev, channel = UpdateChannel.STABLE))
        assertEquals(listOf(pre, stable), select(stable, pre, dev, channel = UpdateChannel.PRERELEASE))
        assertEquals(listOf(dev, pre, stable), select(stable, pre, dev))
    }

    @Test fun developmentBuildNameIsNotAReleaseVersionBaseline() {
        assertEquals(1, select(candidate("1.2.0"), build = installed.copy(debug = true, versionName = "1.2-dev")).size)
        assertEquals(1, select(candidate("1.2", channel = UpdateChannel.DEVELOPMENT), build = installed.copy(versionName = "custom")).size)
        assertTrue(select(candidate(), build = installed.copy(versionName = "custom")).isEmpty())
    }

    @Test fun rejectsInvalidOrIncompatibleMetadata() {
        val c = candidate()
        val m = c.manifest
        val invalid = listOf(
            m.copy(schemaVersion = 2), m.copy(versionName = "garbage"), m.copy(commitSha = "bad"),
            m.copy(apkSha256 = "bad"), m.copy(certificateSha256 = "c".repeat(64)),
            m.copy(applicationId = "other"), m.copy(minSdk = 35), m.copy(abis = listOf("x86")),
            m.copy(apkName = "../app.apk"), m.copy(apkSize = 0), m.copy(apkSize = 2_147_483_648L),
            m.copy(versionCode = 2_100_000_001), m.copy(sourceRef = "refs/tags/v1.1.0"),
            candidate("1.2", channel = UpdateChannel.DEVELOPMENT).manifest.copy(sourceRef = "refs/heads/feature"),
        )
        invalid.forEach { assertTrue("accepted $it", select(c.copy(manifest = it)).isEmpty()) }
        assertTrue(select(c.copy(apkUrl = "http://example.com/app.apk")).isEmpty())
        assertEquals(1, select(c.copy(manifest = m.copy(abis = emptyList()))).size)
    }
}
