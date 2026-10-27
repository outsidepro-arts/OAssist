package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Завершение сессии при явном завершении диалога или самостоятельной благодарности
 * без новой просьбы и незавершённой задачи. Сигнал хосту идёт по имени тула через AgentEvents.onToolCall;
 * хост закрывает сессию после Finish текущего хода и конца озвучки — финальный
 * прощальный ответ должен прозвучать.
 */
class ByeTool : Tool {
    override val schema = ToolSchema(
        name = "bye",
        description = "Завершить работу ассистента. Вызывать при явном намерении пользователя закончить диалог " +
            "или прекратить дальнейшую работу либо при самостоятельной благодарности без новой просьбы и " +
            "незавершённой задачи. Благодарность с новой просьбой не завершает диалог. Сначала выдай короткий " +
            "прощальный ответ текстом, затем вызови этот инструмент. Не завершать по другим поводам.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        ToolResult(true, "Сессия завершена")
}
