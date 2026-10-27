package ru.outsideproarts.oassist.core.agent

import android.Manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.speech.AgentVoiceCapture
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController
import ru.outsideproarts.oassist.core.speech.VoiceCaptureResult

/** Голосовые инструменты агента используют системное распознавание и возвращают текст. */
class VoiceListenStartTool(private val capture: VoiceCaptureController) : Tool {
    override val schema = ToolSchema(
        name = "voice_listen_start",
        description = "Начать слушать микрофон и дождаться одного голосового ответа пользователя. " +
            "Возвращает распознанный текст или причину, почему ответа нет. " +
            "Для получения ответа вызывать voice_listen_stop не нужно.",
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

    override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Включить микрофон и слушать голосовой ответ?"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val timeout = arguments["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 60
        val language = runCatching { arguments["language"]?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "ru-RU"
        return capture.startCapture(language, timeout).toToolResult()
    }
}

class VoiceListenStopTool(private val capture: VoiceCaptureController) : Tool {
    override val schema = ToolSchema(
        name = "voice_listen_stop",
        description = "Остановить активное прослушивание этого агента и дождаться результата. " +
            "После завершения voice_listen_start ответ уже возвращён; stop не возвращает прошлые ответы.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)

    override suspend fun execute(arguments: JsonObject): ToolResult = capture.stopCapture().toToolResult()
}

private fun VoiceCaptureResult.toToolResult(): ToolResult = when (this) {
    is VoiceCaptureResult.Transcript -> if (text.isBlank()) {
        ToolResult(false, AgentVoiceCapture.SILENCE)
    } else {
        ToolResult(true, text)
    }
    VoiceCaptureResult.NoSpeech -> ToolResult(false, AgentVoiceCapture.SILENCE)
    is VoiceCaptureResult.Timeout -> ToolResult(false, "Время вышло ($seconds с) — ответа не расслышано.")
    is VoiceCaptureResult.StartFailed -> ToolResult(false, AgentVoiceCapture.failureText(error))
    is VoiceCaptureResult.RecognitionFailed -> ToolResult(false, AgentVoiceCapture.failureText(error))
    VoiceCaptureResult.Cancelled -> ToolResult(false, "Прослушивание прервано.")
    VoiceCaptureResult.NotRecording -> ToolResult(true, "Нет активного прослушивания для этого запроса; другие записи не остановлены.")
}
