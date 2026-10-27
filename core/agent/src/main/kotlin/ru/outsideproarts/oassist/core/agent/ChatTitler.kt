package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CancellationException
import ru.outsideproarts.oassist.core.model.AssistMode

/**
 * Именование нового диалога агентом: мгновенный плейсхолдер из первого
 * сообщения + фоновый короткий запрос к модели («название до 5 слов»).
 * При ошибке/без сети остаётся плейсхолдер; ручное переименование главнее.
 */
object ChatTitler {

    const val MAX_TITLE_LENGTH = 40
    private const val MAX_TITLE_WORDS = 8
    private const val MAX_PROMPT_MESSAGE = 500

    /** Плейсхолдер: первая непустая строка, до 8 слов и 40 символов. */
    fun placeholder(firstMessage: String): String {
        val line = firstMessage.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return ""
        val words = line.split(Regex("\\s+")).take(MAX_TITLE_WORDS).joinToString(" ")
        return if (words.length <= MAX_TITLE_LENGTH) {
            words
        } else {
            words.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
        }
    }

    /**
     * Название от модели режима [mode]; null — не удалось (нет модели/провайдера,
     * сеть, мусорный ответ) — вызывающий оставляет плейсхолдер.
     *
     * [firstAnswer] — ответ ассистента на первый ход: используется как материал
     * для названия, когда текста пользователя нет (аудиосообщение для модели).
     */
    suspend fun generateTitle(
        mode: AssistMode,
        firstMessage: String,
        factory: AgentFactory,
        firstAnswer: String = "",
    ): String? {
        val byAnswer = firstMessage.isBlank()
        val seed = if (byAnswer) firstAnswer else firstMessage
        if (seed.isBlank()) return null
        return try {
            val profile = factory.providerForMode(mode) ?: return null
            val modelId = factory.modeConfig(mode).modelId?.takeIf { it.isNotBlank() } ?: return null
            val agent = factory.agentFor(profile, mode)
            val raw = agent.complete(
                modelId = modelId,
                systemPrompt = null,
                history = emptyList(),
                userMessage = prompt(seed, byAnswer),
            )
            parseTitle(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Разбор ответа модели: первая строка без кавычек/выделения, кап длины. */
    fun parseTitle(raw: String): String? {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return null
        val cleaned = line
            .removePrefix("**").removeSuffix("**")
            .removePrefix("«").removeSuffix("»")
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_TITLE_LENGTH)
    }

    private fun prompt(seed: String, byAnswer: Boolean): String {
        val task = if (byAnswer) {
            "Придумай короткое название диалога (до 5 слов) по ответу ассистента " +
                "на аудиосообщение пользователя. Ответ — только название: без кавычек, " +
                "точек и пояснений.\n\nОтвет ассистента:\n"
        } else {
            "Придумай короткое название диалога (до 5 слов) по первому сообщению пользователя. " +
                "Ответ — только название: без кавычек, точек и пояснений.\n\nСообщение:\n"
        }
        return task + seed.take(MAX_PROMPT_MESSAGE)
    }
}
