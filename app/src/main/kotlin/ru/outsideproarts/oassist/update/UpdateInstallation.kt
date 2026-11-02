package ru.outsideproarts.oassist.update

import java.io.File
import kotlinx.coroutines.CancellationException

data class PreparedUpdate(val file: File, val generation: Long)

/** Re-check ownership on the caller's dispatcher immediately before launching Android. */
internal suspend fun handOffUpdate(
    prepare: suspend () -> PreparedUpdate?,
    current: (PreparedUpdate) -> Boolean,
    launch: (PreparedUpdate) -> Unit,
    interrupted: () -> Unit,
    failed: () -> Unit,
) {
    try {
        val prepared = prepare()
        if (prepared == null) failed()
        else if (current(prepared)) launch(prepared)
    } catch (cancel: CancellationException) {
        interrupted()
        throw cancel
    } catch (_: Exception) { failed() }
}
