package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.ActionRisk

/**
 * Завершение сессии ассистента, когда пользователь попрощался или ясно дал понять,
 * что диалог окончен. Сигнал хосту идёт по имени тула через AgentEvents.onToolCall;
 * хост закрывает сессию после Finish текущего хода и конца озвучки — финальный
 * прощальный ответ должен прозвучать.
 */
class ByeTool : Tool {
    override val risk = ActionRisk.AUTO

    override val schema = ToolSchema(
        name = "bye",
        description = "Завершить работу ассистента. Вызывать, когда пользователь попрощался, " +
            "поблагодарил или ясно дал понять, что диалог окончен: сначала выдай короткий " +
            "прощальный ответ текстом, затем вызови этот инструмент. Не завершать по другим поводам.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        ToolResult(true, "Сессия завершена")
}
