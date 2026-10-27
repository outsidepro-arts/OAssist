package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Инструменты управления экраном через пассивный AccessibilityService.
 * Индексы узлов — из последнего `ui_get_screen`; перед действием снимок
 * перечитывается, протухший индекс даёт явную ошибку.
 */
class UiGetScreenTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_get_screen",
        description = "Снимок экрана: список элементов с индексами, текстами и координатами. " +
            "Сохрани snapshot_id и передавай его вместе с индексом каждому инструменту элемента. Флаг has_actions — у элемента есть скрытые действия (ui_get_actions).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return ui.snapshot().fold(
            onSuccess = { ToolResult(true, "snapshot_id=${it.snapshotId}\n" + formatSnapshot(it.nodes, it.total, it.screenHeight, it.fromOwnWindow)) },
            onFailure = { ToolResult(false, it.message ?: "Нет снимка") },
        )
    }
}

class UiClickTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_click",
        description = "Нажать на элемент экрана по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Нажать на элемент экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        return ui.click(index, snapshotId)
    }
}

class UiLongClickTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_long_click",
        description = "Долгое нажатие на элемент экрана по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Долго нажать на элемент экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        return ui.longClick(index, snapshotId)
    }
}

class UiSetTextTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_set_text",
        description = "Ввести текст в поле ввода по индексу из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
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
                add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id"))
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
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        return ui.setText(index, text, snapshotId)
    }
}

class UiScrollTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_scroll",
        description = "Прокрутить список на экране. Без index — первый прокручиваемый узел; с index обязателен snapshot_id из ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
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
        if (arguments.containsKey("index") && index == null) return ToolResult(false, "Некорректный index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (index != null && snapshotId == null) return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан direction")
        return ui.scroll(index, direction, snapshotId)
    }
}

class UiSwipeTool(private val ui: UiActions) : Tool {
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

class UiScreenshotTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_screenshot",
        description = "Скриншот текущего окна: возвращает изображение экрана в модель (нужна модель с поддержкой изображений).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = ui.screenshot()
}

/**
 * Снимок области экрана. Дешевле полного кадра, когда интересует один блок
 * (поле с ошибкой, цена, заголовок) — в модель уходит ровно одна картинка.
 * Координаты — из `ui_get_screen`, выход за пределы окна отклоняется.
 */
class UiScreenshotRegionTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_screenshot_region",
        description = "Скриншот прямоугольной области экрана в модель: координаты как в ui_get_screen " +
            "(нужна модель с поддержкой изображений). Дешевле ui_screenshot, когда нужен один блок.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("left", buildJsonObject {
                    put("type", "integer")
                    put("description", "X левого края области")
                })
                put("top", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y верхнего края области")
                })
                put("right", buildJsonObject {
                    put("type", "integer")
                    put("description", "X правого края области")
                })
                put("bottom", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y нижнего края области")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("left"))
                add(kotlinx.serialization.json.JsonPrimitive("top"))
                add(kotlinx.serialization.json.JsonPrimitive("right"))
                add(kotlinx.serialization.json.JsonPrimitive("bottom"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val l = arguments["left"]?.jsonPrimitive?.content ?: "?"
        val t = arguments["top"]?.jsonPrimitive?.content ?: "?"
        val r = arguments["right"]?.jsonPrimitive?.content ?: "?"
        val b = arguments["bottom"]?.jsonPrimitive?.content ?: "?"
        return "Скриншот области экрана [$l,$t-$r,$b]"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val left = arguments["left"]?.jsonPrimitive?.content?.toIntOrNull()
        val top = arguments["top"]?.jsonPrimitive?.content?.toIntOrNull()
        val right = arguments["right"]?.jsonPrimitive?.content?.toIntOrNull()
        val bottom = arguments["bottom"]?.jsonPrimitive?.content?.toIntOrNull()
        if (left == null || top == null || right == null || bottom == null) {
            return ToolResult(false, "Нужны left/top/right/bottom")
        }
        return ui.screenshotRegion(left, top, right, bottom)
    }
}

/**
 * Снимок одного элемента по индексу из `ui_get_screen`. Выгоднее области: модели не нужно
 * угадывать координаты — она уже видит прямоугольник узла в снимке экрана.
 */
class UiScreenshotNodeTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_screenshot_node",
        description = "Скриншот элемента экрана по индексу из ui_get_screen в модель " +
            "(нужна модель с поддержкой изображений). Сначала вызови ui_get_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка ui_get_screen")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Скриншот элемента экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        return ui.screenshotNode(index, snapshotId)
    }
}

/**
 * Список скрытых действий элемента по индексу из `ui_get_screen` (у узлов
 * с флагом `has_actions`). Сами действия не выполняются — для этого
 * [UiPerformActionTool]: два шага, как снимок → нажатие.
 */
class UiGetActionsTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_get_actions",
        description = "Скрытые действия элемента по индексу из ui_get_screen — для элементов с флагом " +
            "has_actions (например «Ответить», «Переслать», долгое нажатие, раскрытие). " +
            "Выбранное действие выполни через ui_perform_action.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка ui_get_screen")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        return "Показать действия элемента экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        return ui.nodeActions(index, snapshotId)
    }
}

/**
 * Выполнение скрытого действия элемента по индексам из `ui_get_screen`
 * (`index`) и `ui_get_actions` (`action_index`). Протухший индекс или
 * сдвинувшийся список — честная ошибка с актуальным списком действий.
 */
class UiPerformActionTool(private val ui: UiActions) : Tool {
    override val schema = ToolSchema(
        name = "ui_perform_action",
        description = "Выполнить скрытое действие элемента: index — элемент из ui_get_screen, " +
            "action_index — действие из ui_get_actions. Например: ответить на сообщение, переслать, открыть меню.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("snapshot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "snapshot_id из ui_get_screen; обязателен при указании index")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс узла из снимка ui_get_screen")
                })
                put("action_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Индекс действия из ui_get_actions")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("index")); add(kotlinx.serialization.json.JsonPrimitive("snapshot_id"))
                add(kotlinx.serialization.json.JsonPrimitive("action_index"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val index = arguments["index"]?.jsonPrimitive?.content ?: "?"
        val action = arguments["action_index"]?.jsonPrimitive?.content ?: "?"
        return "Выполнить действие №$action элемента экрана №$index"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val index = arguments["index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан index")
        val snapshotId = arguments["snapshot_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Не указан snapshot_id — вызови ui_get_screen")
        val actionIndex = arguments["action_index"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан action_index")
        return ui.performNodeAction(index, actionIndex, snapshotId)
    }
}