package ru.outsideproarts.oassist.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import ru.outsideproarts.oassist.core.model.update.*

/** Serializes discovery and rejects results belonging to an obsolete settings generation. */
class UpdateCheckCoordinator(
    private val candidates: (UpdateChannel, Long) -> Flow<UpdateCandidate>,
    private val isDescendant: suspend (String, String) -> Boolean,
) {
    private val mutex = Mutex()
    private var generation = 0L

    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun token(): Long = generation
    @Synchronized fun isCurrent(token: Long): Boolean = generation == token

    suspend fun find(installed: InstalledBuild, channel: UpdateChannel, token: Long): Result = mutex.withLock {
        if (!isCurrent(token)) return@withLock Result.Obsolete
        val found = candidates(channel, installed.versionCode).firstOrNull { candidate ->
            if (!isCurrent(token)) return@firstOrNull true
            if (UpdateSelector.eligibleCandidates(listOf(candidate), installed, channel).isEmpty()) return@firstOrNull false
            val sameCommit = installed.commitSha.isNotEmpty() && installed.commitSha == candidate.manifest.commitSha
            if (sameCommit && candidate.manifest.channel == UpdateChannel.DEVELOPMENT) return@firstOrNull false
            if (installed.commitSha.isNotEmpty() && !sameCommit &&
                !isDescendant(installed.commitSha, candidate.manifest.commitSha)) return@firstOrNull false
            true
        }
        when {
            !isCurrent(token) -> Result.Obsolete
            found != null -> Result.Available(found)
            else -> Result.NoUpdate
        }
    }

    sealed interface Result {
        data class Available(val candidate: UpdateCandidate) : Result
        data object NoUpdate : Result
        data object Obsolete : Result
    }
}
