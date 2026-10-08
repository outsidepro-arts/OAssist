package ru.outsideproarts.oassist.core.agent

import android.Manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppLaunchTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "app_launch",
        description = "Запустить приложение по имени пакета. Используй когда пользователь хочет открыть приложение.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Имя пакета Android, например com.android.settings")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("package_name")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val packageName = arguments["package_name"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан package_name")
        return actions.launchApp(packageName)
    }
}

class AppListTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "app_list",
        description = "Список установленных приложений с ярлыками на рабочем столе: название и имя пакета. " +
            "Приложения без иконки не перечисляются. Используй чтобы узнать что установлено или найти пакет " +
            "по названию перед app_launch.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательный фильтр: подстрока названия или имени пакета")
                })
                put("include_system", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Включать системные приложения, по умолчанию true")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Максимум записей, по умолчанию 100")
                })
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val query = runCatching { arguments["query"]?.jsonPrimitive?.content }.getOrNull()
        return if (query.isNullOrBlank()) {
            "Показать список установленных приложений"
        } else {
            "Показать установленные приложения по запросу '$query'"
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = runCatching { arguments["query"]?.jsonPrimitive?.content }.getOrNull()
        val includeSystem = runCatching {
            arguments["include_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull() ?: true
        val limit = runCatching {
            arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: DEFAULT_APP_LIST_LIMIT
        return actions.listApps(query, includeSystem, limit)
    }
}

class WebOpenTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "web_open",
        description = "Открыть URL в браузере пользователю (показать страницу человеку). Содержимое в модель НЕ возвращается — для чтения используй web_fetch, для поиска web_search.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL для открытия, например https://example.com")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val url = arguments["url"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан url")
        return actions.openWeb(url)
    }
}

class WebSearchTool(private val search: SearchActions) : Tool {
    override val schema = ToolSchema(
        name = "web_search",
        description = "Поиск в интернете: возвращает список результатов (заголовок/URL/сниппет) через настроенную поисковую систему. Для чтения страницы используй web_fetch; открыть браузер человеку — web_open.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Поисковый запрос")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан query")
        return search.search(query)
    }
}

class AlarmSetTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "alarm_set",
        description = "Установить будильник. Используй когда пользователь просит поставить будильник или напоминание.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Час (0-23)")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Минуты (0-59)")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "Метка будильника (необязательно)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("hour"))
                add(kotlinx.serialization.json.JsonPrimitive("minute"))
            })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val hour = arguments["hour"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан hour")
        val minute = arguments["minute"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан minute")
        val label = arguments["label"]?.jsonPrimitive?.content
        return actions.setAlarm(hour, minute, label)
    }
}

class OpenSettingsTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "open_settings",
        description = "Открыть системные настройки. Используй когда пользователь хочет открыть настройки Wi-Fi, Bluetooth, звука и т.д.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("section", buildJsonObject {
                    put("type", "string")
                    put("description", "Раздел настроек: wifi, bluetooth, display, sound, location (или пусто для общих)")
                })
            })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val section = arguments["section"]?.jsonPrimitive?.content
        return actions.openSettings(section)
    }
}

class CallPlaceTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.CALL_PHONE)
    override val schema = ToolSchema(
        name = "call_place",
        description = "Позвонить на номер телефона. Используй когда пользователь просит позвонить.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("number", buildJsonObject {
                    put("type", "string")
                    put("description", "Номер телефона, например +79161234567")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("number")) })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val number = arguments["number"]?.jsonPrimitive?.content ?: "?"
        return "Позвонить на номер $number"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val number = arguments["number"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан number")
        return actions.placeCall(number)
    }
}

class SmsSendTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.SEND_SMS)
    override val schema = ToolSchema(
        name = "sms_send",
        description = "Отправить SMS. Используй когда пользователь просит написать SMS.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("to", buildJsonObject {
                    put("type", "string")
                    put("description", "Номер получателя")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст сообщения")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("to"))
                add(kotlinx.serialization.json.JsonPrimitive("body"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val to = arguments["to"]?.jsonPrimitive?.content ?: "?"
        val body = arguments["body"]?.jsonPrimitive?.content ?: ""
        return "Отправить SMS на номер $to: «$body»"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val to = arguments["to"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан to")
        val body = arguments["body"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан body")
        return actions.sendSms(to, body)
    }
}

class ContactFindTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.READ_CONTACTS)
    override val schema = ToolSchema(
        name = "contact_find",
        description = "Найти контакты по имени. Используй чтобы узнать номер человека перед звонком или SMS.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Имя или его часть, например Саша")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан query")
        return actions.findContacts(query)
    }
}

class CalendarAddTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.WRITE_CALENDAR)
    override val schema = ToolSchema(
        name = "calendar_add",
        description = "Добавить событие в календарь. Время — миллисекунды epoch UTC.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Название события")
                })
                put("start_millis", buildJsonObject {
                    put("type", "integer")
                    put("description", "Начало события, миллисекунды epoch UTC")
                })
                put("end_millis", buildJsonObject {
                    put("type", "integer")
                    put("description", "Конец события, миллисекунды epoch UTC")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Описание события (необязательно)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("title"))
                add(kotlinx.serialization.json.JsonPrimitive("start_millis"))
                add(kotlinx.serialization.json.JsonPrimitive("end_millis"))
            })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String {
        val title = arguments["title"]?.jsonPrimitive?.content ?: "?"
        return "Добавить событие в календарь: «$title»"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments["title"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан title")
        val start = arguments["start_millis"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return ToolResult(false, "Не указан start_millis")
        val end = arguments["end_millis"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return ToolResult(false, "Не указан end_millis")
        val description = arguments["description"]?.jsonPrimitive?.content
        return actions.addCalendarEvent(title, start, end, description)
    }
}

class TimerSetTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "timer_set",
        description = "Запустить таймер. Используй когда пользователь просит засечь время.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Длительность в секундах")
                })
                put("label", buildJsonObject {
                    put("type", "string")
                    put("description", "Метка таймера (необязательно)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("seconds")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val seconds = arguments["seconds"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult(false, "Не указан seconds")
        val label = arguments["label"]?.jsonPrimitive?.content
        return actions.setTimer(seconds, label)
    }
}

class DeviceInfoTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "device_info",
        description = "Сведения об устройстве: модель, версия Android, локаль, часовой пояс.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = actions.deviceInfo()
}

class ClipboardCopyTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "clipboard_copy",
        description = "Скопировать текст в буфер обмена.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст для копирования")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        return actions.copyToClipboard(text)
    }
}

class NavOpenTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "nav_open",
        description = "Открыть карты: поиск места или точка по координатам. Используй для маршрутов и адресов.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Место или адрес, например Красная площадь")
                })
                put("lat", buildJsonObject {
                    put("type", "number")
                    put("description", "Широта (вместо query, вместе с lng)")
                })
                put("lng", buildJsonObject {
                    put("type", "number")
                    put("description", "Долгота (вместе с lat)")
                })
            })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content
        val lat = arguments["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lng = arguments["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
        return actions.openNavigation(query, lat, lng)
    }
}

class ShareTextTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "share_text",
        description = "Поделиться текстом через системный выбор приложения. Выбор подтверждает пользователь.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст для отправки")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан text")
        return actions.shareText(text)
    }
}

class FlashlightTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)
    override val schema = ToolSchema(
        name = "flashlight",
        description = "Включить или выключить фонарик.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("on", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true — включить, false — выключить")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("on")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val on = arguments["on"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: return ToolResult(false, "Не указан on (true/false)")
        return actions.setFlashlight(on)
    }
}

class MediaButtonTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "media_button",
        description = "Нажать медиа-кнопку активного плеера: play_pause, next, prev.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("button", buildJsonObject {
                    put("type", "string")
                    put("description", "Кнопка: play_pause/next/prev")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("button")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val button = arguments["button"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан button")
        return actions.pressMediaButton(button)
    }
}

class LocationGetTool(private val actions: DeviceActions) : Tool {
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override val schema = ToolSchema(
        name = "location_get",
        description = "Текущие координаты устройства: запрашивает свежий фикс (до ~20 секунд), кэш не используется.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override fun describeForConfirmation(arguments: JsonObject): String =
        "Передать агенту текущее местоположение"

    override suspend fun execute(arguments: JsonObject): ToolResult = actions.freshLocation()
}

class DeviceStateTool(private val actions: DeviceActions) : Tool {
    override val schema = ToolSchema(
        name = "device_state",
        description = "Живое состояние устройства одной строкой: фонарик, музыка, Wi-Fi, батарея, экран, звук. Сверяйся перед действиями.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = actions.deviceState()
}
