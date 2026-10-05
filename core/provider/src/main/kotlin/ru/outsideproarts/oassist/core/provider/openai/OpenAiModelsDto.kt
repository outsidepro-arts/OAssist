package ru.outsideproarts.oassist.core.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответ GET /models для OpenAI-совместимых провайдеров. */
@Serializable
data class OpenAiModelsResponseDto(
    val data: List<OpenAiModelDto> = emptyList(),
    @SerialName("object") val objectType: String? = null,
)

@Serializable
data class OpenAiModelDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("object") val objectType: String? = null,
)