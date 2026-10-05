package ru.outsideproarts.oassist.core.provider.openai

import java.util.UUID

/**
 * Заголовки OpenCode Zen (и Go): relay пинает запросы диалога на один backend
 * по `x-opencode-session` — это греет prompt cache. `x-opencode-request` —
 * идентификатор каждого HTTP-запроса.
 */
internal object OpenCodeZen {
    const val SESSION_HEADER = "x-opencode-session"
    const val REQUEST_HEADER = "x-opencode-request"

    /**
     * Стабильный id сессии диалога: детерминированный UUID от «головы» диалога
     * (первое system-сообщение + первый user-вопрос). Одинаков на всех ходах
     * диалога, stateless.
     */
    fun sessionId(dialogHead: String): String =
        UUID.nameUUIDFromBytes(dialogHead.toByteArray(Charsets.UTF_8)).toString()

    /** Свежий id запроса в формате `msg_<uuid>`. */
    fun requestId(): String = "msg_" + UUID.randomUUID()
}
