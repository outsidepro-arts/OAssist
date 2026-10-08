package ru.outsideproarts.oassist.core.agent

import android.Manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.speech.AgentVoiceCapture
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController

/**
 * Голосовые тулы: модель задаёт вопрос вслух (через TTS-озвучку своего
 * комментария) и слушает ответ пользователя.
 *
 * `voice_listen_start` — неблокирующий («слушаю»), `voice_listen_stop` —
 * останавливает и возвращает транскрипт результатом тула; дальше агент
 * читает ответ из истории tool-хода, [AgentLoop] менять не надо.
 * Режим захвата — всегда STT (модели нужен текст, не байты).
 */
class VoiceListenStartTool(private val capture: VoiceCaptureController) : Tool {
    override val schema = ToolSchema(
        name = "voice_listen_start",
        description = "Начать слушать микрофон и ждать голосовой ответ пользователя. " +
            "Неблокирующий: сразу возвращает статус. Ответ забирается voice_listen_stop.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("timeout_sec", buildJsonObject {
                    put("type", "integer")
                    put("description", "Сторожевой таймаут ожидания ответа, 5–300 с (по умолчанию 60)")
                })
                put("language", buildJsonObject {
                    put("type", "string")
                    put("description", "Язык распознавания, например ru-RU (по умолчанию ru-RU)")
                })
            })
        },
    )

    /** Включение микрофона по воле агента — прайваси-чувствительно, спрашиваем. */
    override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Включить микрофон и слушать голосовой ответ?"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val timeout = arguments["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 60
        val language = runCatching { arguments["language"]?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "ru-RU"
        return ToolResult(true, capture.startCapture(language, timeout))
    }
}

class VoiceListenStopTool(private val capture: VoiceCaptureController) : Tool {
    override val schema = ToolSchema(
        name = "voice_listen_stop",
        description = "Остановить прослушивание и транскрибировать ответ пользователя. " +
            "Возвращает распознанный текст (или диагноз: тишина/таймаут).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
            })
        },
    )

    /** Выключение микрофона безопасно — без подтверждения. */
    override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)

    override suspend fun execute(arguments: JsonObject): ToolResult =
        ToolResult(true, capture.stopCapture())
}
