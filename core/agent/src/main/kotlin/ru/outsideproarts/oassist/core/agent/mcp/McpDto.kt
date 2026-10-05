package ru.outsideproarts.oassist.core.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Lenient-парсер MCP: серверы присылают поля вне DTO — незнакомое игнорируется.
 * Свой экземпляр (а не общий из `core:provider`): тот `internal` для модуля.
 */
internal val McpJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** JSON-RPC 2.0 поверх Streamable HTTP (один объект на запрос/ответ). */
@Serializable
data class McpRequestDto(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpResponseDto(
    val jsonrpc: String? = null,
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: McpErrorDto? = null,
)

@Serializable
data class McpErrorDto(
    val code: Int = 0,
    val message: String = "",
    val data: JsonElement? = null,
)

@Serializable
data class McpInitializeParamsDto(
    @SerialName("protocolVersion") val protocolVersion: String,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    @SerialName("clientInfo") val clientInfo: McpClientInfoDto,
)

@Serializable
data class McpClientInfoDto(
    val name: String,
    val version: String,
)

@Serializable
data class McpInitializeResultDto(
    @SerialName("protocolVersion") val protocolVersion: String? = null,
    val serverInfo: McpServerInfoDto? = null,
)

@Serializable
data class McpServerInfoDto(
    val name: String? = null,
    val version: String? = null,
)

@Serializable
data class McpToolsListResultDto(
    val tools: List<McpToolDescriptionDto> = emptyList(),
)

@Serializable
data class McpToolDescriptionDto(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject? = null,
)

@Serializable
data class McpCallParamsDto(
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class McpCallResultDto(
    val content: List<McpContentBlockDto> = emptyList(),
    @SerialName("isError") val isError: Boolean = false,
)

@Serializable
data class McpContentBlockDto(
    val type: String? = null,
    val text: String? = null,
)
