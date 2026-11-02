package ru.outsideproarts.oassist.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.model.update.UpdateCandidate
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, ERROR }
enum class UpdateFailure { CHECK, DOWNLOAD, INSTALL, SETTINGS }
data class UpdateState(
    val preferences: UpdatePreferences? = null,
    val candidate: UpdateCandidate? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Int = 0,
    val failure: UpdateFailure? = null,
    val prompt: Boolean = false,
    val installAttempt: Long = 0,
)

/** A failed read is a terminal result for this attempt, so a scheduled job can finish. */
internal suspend fun Flow<UpdateState>.awaitUpdatePreferences(): UpdatePreferences? =
    first { it.preferences != null || it.failure == UpdateFailure.SETTINGS }.preferences
