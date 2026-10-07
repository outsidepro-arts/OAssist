package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.outsideproarts.oassist.core.model.ActionRisk

/**
 * Инструменты управления экраном через пассивный AccessibilityService.
 * Индексы узлов — из последнего `ui_get_screen`; перед действием снимок
 * перечитывается, протухший индекс даёт явную ошибку.
 */
class UiGetScreenTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_get_screen",
        description = "Снимок экрана: список элементов с индексами, текстами и координатами. Вызывай перед нажатиями.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return ui.snapshot().fold(
            onSuccess = { ToolResult(true, formatSnapshot(it.nodes, it.total, it.screenHeight, it.fromOwnWindow)) },
            onFailure = { ToolResult(false, it.message ?: "Нет снимка") },
        )
    }
}

class UiClickTool(private val ui: UiActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "ui_click",
        description = "Нажать на элемент экрана по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Нажать на элемент экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        return ui.click(index)
    }
}

class UiLongClickTool(private val ui: UiActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "ui_long_click",
        description = "Долгое нажатие на элемент экрана по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Долго нажать на элемент экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        return ui.longClick(index)
    }
}

class UiSetTextTool(private val ui: UiActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "ui_set_text",
        description = "Ввести текст в поле ввода по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс поля ввода из снимка")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст для ввода")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("index"))
                add(kotlinx.serialization.json.JsonPrimitive("text"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        val text = arguments["text"]?.jsonPrimitive?.content ?: ""
        return "Ввести текст в поле №$index: «$text»"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        return ui.setText(index, text)
    }
}

class UiScrollTool(private val ui: UiActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "ui_scroll",
        description = "Прокрутить список на экране. Без index — первый прокручиваемый узел.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс прокручиваемого узла (необязательно)")
                })
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("description", "Направление: down/up")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("direction")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val direction = arguments["direction"]?.jsonPrimitive?.content ?: "?"
        return "Прокрутить экран ($direction)"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан direction")
        return ui.scroll(index, direction)
    }
}

class UiSwipeTool(private val ui: UiActions) : Tool {
    override val risk = ActionRisk.ASK
    override val schema = ToolSchema(
        name = "ui_swipe",
        description = "Свайп по координатам экрана (из снимка ui_get_screen).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("from_x", buildJsonObject { put("type", "integer") })
                put("from_y", buildJsonObject { put("type", "integer") })
                put("to_x", buildJsonObject { put("type", "integer") })
                put("to_y", buildJsonObject { put("type", "integer") })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Длительность в мс (необязательно, по умолчанию 300)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("from_x"))
                add(kotlinx.serialization.json.JsonPrimitive("from_y"))
                add(kotlinx.serialization.json.JsonPrimitive("to_x"))
                add(kotlinx.serialization.json.JsonPrimitive("to_y"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Свайп по экрану (${arguments["from_x"]?.jsonPrimitive?.content},${arguments["from_y"]?.jsonPrimitive?.content} → ${arguments["to_x"]?.jsonPrimitive?.content},${arguments["to_y"]?.jsonPrimitive?.content})"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val fromX = arguments["from_x"]?.jsonPrimitive?.content?.toIntOrNull()
        val fromY = arguments["from_y"]?.jsonPrimitive?.content?.toIntOrNull()
        val toX = arguments["to_x"]?.jsonPrimitive?.content?.toIntOrNull()
        val toY = arguments["to_y"]?.jsonPrimitive?.content?.toIntOrNull()
        if (fromX == null || fromY == null || toX == null || toY == null) {
            return ToolResult(false, "Нужны from_x/from_y/to_x/to_y")
        }
        val duration = arguments["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
        return ui.swipe(fromX, fromY, toX, toY, duration)
    }
}

class UiGlobalTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_global",
        description = "Системная навигация: back (назад), home (домой), recents (недавние).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Действие: back/home/recents")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("action")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан action")
        return ui.globalAction(action)
    }
}

class UiWaitForTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_wait_for",
        description = "Ждать появления текста на экране. Проверка перед следующим действием.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст, появления которого ждём")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Таймаут в мс (необязательно, по умолчанию 5000, макс 10000)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        val timeout = arguments["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5000L
        return ui.waitForText(text, timeout)
    }
}
