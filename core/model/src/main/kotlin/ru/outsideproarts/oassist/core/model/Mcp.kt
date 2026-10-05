package ru.outsideproarts.oassist.core.model

import kotlinx.serialization.Serializable

/**
 * Конфигурация внешнего MCP-сервера (Streamable HTTP).
 * Секреты хранятся отдельно: [bearerRef] и значения [extraHeaders] — alias
 * в `SecretStore`, сами значения никогда не попадают в модель/БД/логи.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** Alias Bearer-токена в SecretStore; null — без авторизации. */
    val bearerRef: String? = null,
    /** Имя заголовка → alias его значения в SecretStore. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)
