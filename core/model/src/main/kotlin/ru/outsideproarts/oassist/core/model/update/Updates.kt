package ru.outsideproarts.oassist.core.model.update

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UpdateChannel(val checkIntervalMillis: Long) {
    @SerialName("stable") STABLE(24 * 60 * 60 * 1000L),
    @SerialName("prerelease") PRERELEASE(4 * 60 * 60 * 1000L),
    @SerialName("development") DEVELOPMENT(15 * 60 * 1000L),
}

enum class UpdateChannelReason { MANUAL, AUTOMATIC_DEBUG, AUTOMATIC_PRERELEASE, AUTOMATIC_STABLE }

data class UpdatePreferences(
    val automaticChecks: Boolean = true,
    val notifications: Boolean = false,
    val manualChannel: UpdateChannel? = null,
) {
    fun effectiveChannel(installed: InstalledBuild): UpdateChannel = manualChannel ?: when {
        installed.debug -> UpdateChannel.DEVELOPMENT
        SemanticVersion.parse(installed.versionName)?.stage != null -> UpdateChannel.PRERELEASE
        else -> UpdateChannel.STABLE
    }

    fun channelReason(installed: InstalledBuild): UpdateChannelReason = when {
        manualChannel != null -> UpdateChannelReason.MANUAL
        installed.debug -> UpdateChannelReason.AUTOMATIC_DEBUG
        effectiveChannel(installed) == UpdateChannel.PRERELEASE -> UpdateChannelReason.AUTOMATIC_PRERELEASE
        else -> UpdateChannelReason.AUTOMATIC_STABLE
    }
}

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val channel: UpdateChannel,
    val commitSha: String,
    val minSdk: Int,
    val abis: List<String>,
    val certificateSha256: String,
    val apkSha256: String,
    val apkSize: Long,
    val apkName: String,
    val sourceRef: String,
)

@Serializable
data class UpdateCandidate(val manifest: UpdateManifest, val apkUrl: String, val releaseNotes: String)

data class InstalledBuild(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val debug: Boolean,
    val commitSha: String,
    val certificateSha256: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
)

object UpdateSelector {
    fun eligibleCandidates(candidates: List<UpdateCandidate>, installed: InstalledBuild, channel: UpdateChannel): List<UpdateCandidate> =
        candidates.filter { eligible(it, installed, channel) }.sortedByDescending { it.manifest.versionCode }

    private fun eligible(candidate: UpdateCandidate, installed: InstalledBuild, channel: UpdateChannel): Boolean {
        val m = candidate.manifest
        val version = SemanticVersion.parse(m.versionName) ?: return false
        val installedVersion = SemanticVersion.parse(installed.versionName)
        if (!installed.debug && m.channel != UpdateChannel.DEVELOPMENT && installedVersion == null) return false
        if (m.schemaVersion != 1 || m.applicationId != installed.applicationId || m.versionCode <= installed.versionCode ||
            m.versionCode > 2_100_000_000L || m.minSdk <= 0 || m.minSdk > installed.sdkInt || m.apkSize !in 1..2_147_483_647L ||
            !SHA40.matches(m.commitSha) || !SHA256.matches(m.apkSha256) || !SHA256.matches(m.certificateSha256) ||
            !m.certificateSha256.equals(installed.certificateSha256, ignoreCase = true) ||
            !APK_NAME.matches(m.apkName) || !validUrl(candidate.apkUrl) ||
            m.abis.any { !ABI.matches(it) } || (m.abis.isNotEmpty() && m.abis.none { it in installed.supportedAbis })) return false
        if (channel == UpdateChannel.STABLE && m.channel != UpdateChannel.STABLE ||
            channel == UpdateChannel.PRERELEASE && m.channel == UpdateChannel.DEVELOPMENT) return false
        when (m.channel) {
            UpdateChannel.DEVELOPMENT -> if (m.sourceRef != "refs/heads/master") return false
            UpdateChannel.STABLE -> if (version.stage != null || m.sourceRef != "refs/tags/v${m.versionName}") return false
            UpdateChannel.PRERELEASE -> if (version.stage == null || m.sourceRef != "refs/tags/v${m.versionName}") return false
        }
        return installed.debug || m.channel == UpdateChannel.DEVELOPMENT || version > requireNotNull(installedVersion)
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    private val SHA40 = Regex("[0-9a-fA-F]{40}")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val ABI = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
}

private data class SemanticVersion(val numbers: List<Long>, val stage: String?, val sequence: Long) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val result = (numbers.getOrElse(index) { 0 }).compareTo(other.numbers.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        val rank = when (stage) { "beta" -> 0; "rc" -> 1; else -> 2 }
        val otherRank = when (other.stage) { "beta" -> 0; "rc" -> 1; else -> 2 }
        return rank.compareTo(otherRank).takeIf { it != 0 } ?: sequence.compareTo(other.sequence)
    }

    companion object {
        private val PATTERN = Regex("(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*)){1,2}(?:-(beta|rc)(?:\\.?([1-9][0-9]*))?)?")
        fun parse(value: String): SemanticVersion? {
            val match = PATTERN.matchEntire(value) ?: return null
            val numbers = value.substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
            val sequence = match.groupValues[4].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: if (match.groupValues[4].isEmpty()) 0L else return null
            return SemanticVersion(numbers, match.groupValues[3].ifEmpty { null }, sequence)
        }
    }
}
