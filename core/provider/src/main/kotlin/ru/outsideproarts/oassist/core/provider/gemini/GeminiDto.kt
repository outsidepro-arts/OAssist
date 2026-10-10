package ru.outsideproarts.oassist.core.provider.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Ответ GET /v1beta/models. `name` — вида `models/gemini-2.0-flash`. */
@Serializable
data class GeminiModelsResponseDto(
    val models: List<GeminiModelDto> = emptyList(),
)

@Serializable
data class GeminiModelDto(
    val name: String = "",
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("supportedGenerationMethods") val supportedGenerationMethods: List<String> = emptyList(),
)

/** Запрос POST /v1beta/models/{model}:streamGenerateContent. */
@Serializable
data class GeminiGenerateRequest(
    @SerialName("system_instruction") val systemInstruction: GeminiContentDto? = null,
    val contents: List<GeminiContentDto>,
    val tools: List<GeminiToolsDto>? = null,
)

@Serializable
data class GeminiContentDto(
    val role: String? = null,
    val parts: List<GeminiPartDto>,
)

@Serializable
data class GeminiPartDto(
    val text: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCallDto? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponseDto? = null,
    @SerialName("inlineData") val inlineData: GeminiBlobDto? = null,
)

/** Бинарный парт (аудио PTT): base64 + MIME. */
@Serializable
data class GeminiBlobDto(
    @SerialName("mimeType") val mimeType: String,
    val data: String,
)

@Serializable
data class GeminiFunctionCallDto(
    val name: String,
    val args: JsonElement? = null,
)

@Serializable
data class GeminiFunctionResponseDto(
    val name: String,
    val response: JsonElement,
)

@Serializable
data class GeminiToolsDto(
    @SerialName("function_declarations") val functionDeclarations: List<GeminiFunctionDeclarationDto>,
)

@Serializable
data class GeminiFunctionDeclarationDto(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

/** Один чанк SSE-потока `streamGenerateContent` (первый кандидат). */
@Serializable
data class GeminiStreamChunkDto(
    val candidates: List<GeminiCandidateDto> = emptyList(),
)

@Serializable
data class GeminiCandidateDto(
    val content: GeminiContentDto? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)
