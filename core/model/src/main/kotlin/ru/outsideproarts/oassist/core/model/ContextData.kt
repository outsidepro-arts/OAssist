package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable

/** Provenance label for data supplied to a model. No value represents an instruction role. */
@Serializable
enum class ContextSource {
    MEMORY,
    SCREEN,
    SHARED_CONTENT,
    UNKNOWN_HISTORY,
}

/** Untrusted contextual data, kept separate from owner-authored instructions. */
@Serializable
data class ContextData(
    val source: ContextSource,
    val text: String,
)

/** Current user request and explicitly attached contextual data. */
@Serializable
data class ModelUserInput(
    val requestText: String,
    val context: List<ContextData> = emptyList(),
)
