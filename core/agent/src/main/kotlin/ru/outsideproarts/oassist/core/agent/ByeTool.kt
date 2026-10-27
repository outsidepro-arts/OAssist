package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Завершает сессию после успешного единственного вызова. */
class ByeTool : Tool {
    override val schema = ToolSchema(
        name = "bye",
        description = "Завершить работу ассистента при явном намерении пользователя закончить диалог " +
            "или прекратить работу либо при самостоятельной благодарности без новой просьбы и " +
            "незавершённой задачи. Передай короткое естественное прощание в farewell. " +
            "Вызов bye должен быть единственным вызовом этого раунда.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("farewell", buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("description", "Короткое естественное прощание для пользователя.")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("farewell")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        ToolResult(true, "Сессия завершена")
}
