package ru.outsideproarts.oassist.core.model

/**
 * Предметная категория инструмента — ось «чему вредит действие».
 * Группа разрешений = категория + [ToolAccess] (или `mcp:<id>` для внешних серверов).
 */
enum class ToolCategory {
    /** Звонки, SMS, ответы людям. */
    COMMUNICATION,

    /** Контакты, календарь — данные телефона. */
    PHONE_DATA,

    /** Память и навыки — данные приложения. */
    APP_DATA,

    /** Уведомления. */
    NOTIFICATIONS,

    /** Экран: снимки, нажатия, ввод, жесты. */
    SCREEN_UI,

    /** Микрофон. */
    MICROPHONE,

    /** Геопозиция. */
    GEOLOCATION,

    /** Устройство: звук, яркость, фонарик, медиа, будильники, буфер обмена. */
    DEVICE,

    /** Веб: поиск и чтение страниц. */
    WEB,

    /** Запуск приложений, сайтов, навигация, шаринг. */
    APPS_NAVIGATION,

    /** Shizuku/root: shell и системные настройки. */
    PRIVILEGED,

    /** Инструменты внешних MCP-серверов (ключ — per-сервер). */
    MCP,

    /** Служебные (завершение диалога) — всегда разрешены, пресеты не трогают. */
    SERVICE,

    /** Неизвестный инструмент — fail-closed. */
    OTHER,
}

/** Тип доступа группы: чтение или запись/действие. */
enum class ToolAccess { READ, WRITE }

/** Ключи групп разрешений и маппинг категорий. */
object ToolGroups {
    const val SERVICE = "SERVICE"
    const val UNKNOWN = "UNKNOWN"

    /** Дефолт для MCP-групп без своей записи (пресеты «Разрешить/Запретить все» пишут сюда). */
    const val MCP_DEFAULT = "mcp:*"
    private const val MCP_PREFIX = "mcp:"

    fun key(category: ToolCategory, access: ToolAccess): String =
        "${category.name}.${access.name}"

    fun mcp(serverId: String): String = MCP_PREFIX + serverId

    fun isMcp(key: String): Boolean = key.startsWith(MCP_PREFIX)

    fun mcpServerId(key: String): String = key.removePrefix(MCP_PREFIX)

    /** Тип доступа группы; `null` — MCP/служебные/неизвестные (вне оси чтение/запись). */
    fun accessOf(key: String): ToolAccess? = when {
        key == SERVICE || key == UNKNOWN || isMcp(key) -> null
        key.endsWith(".${ToolAccess.READ.name}") -> ToolAccess.READ
        key.endsWith(".${ToolAccess.WRITE.name}") -> ToolAccess.WRITE
        else -> null
    }

    /**
     * Известные встроенные инструменты, по порядку групп (для UI настроек).
     * MCP-тулы добавляются с ключами `mcp:<id>` и в список не входят.
     */
    val knownToolNames: List<String> = listOf(
        "call_place", "sms_send", "notify_reply",
        "contact_find",
        "calendar_add",
        "memory_get", "skill_list", "skill_get",
        "memory_put", "memory_forget",
        "notify_list",
        "notify_open", "notify_clear",
        "ui_get_screen", "ui_screenshot", "ui_wait_for",
        "ui_click", "ui_long_click", "ui_set_text", "ui_scroll", "ui_swipe", "ui_global",
        "voice_listen_start", "voice_listen_stop",
        "location_get",
        "device_info", "device_state",
        "volume_set", "dnd_set", "brightness_set", "flashlight", "media_button",
        "alarm_set", "timer_set", "clipboard_copy",
        "web_search", "web_fetch",
        "app_list", "app_launch", "open_settings", "web_open", "nav_open", "share_text",
        "shell_get_setting",
        "shell_exec", "shell_put_setting",
        "bye",
    )

    /** Группа разрешений встроенного инструмента; неизвестный — [UNKNOWN] (fail-closed). */
    fun groupOf(toolName: String): String = when (toolName) {
        "call_place", "sms_send", "notify_reply" ->
            key(ToolCategory.COMMUNICATION, ToolAccess.WRITE)
        "contact_find" -> key(ToolCategory.PHONE_DATA, ToolAccess.READ)
        "calendar_add" -> key(ToolCategory.PHONE_DATA, ToolAccess.WRITE)
        "memory_get", "skill_list", "skill_get" ->
            key(ToolCategory.APP_DATA, ToolAccess.READ)
        "memory_put", "memory_forget" ->
            key(ToolCategory.APP_DATA, ToolAccess.WRITE)
        "notify_list" -> key(ToolCategory.NOTIFICATIONS, ToolAccess.READ)
        "notify_open", "notify_clear" ->
            key(ToolCategory.NOTIFICATIONS, ToolAccess.WRITE)
        "ui_get_screen", "ui_screenshot", "ui_wait_for" ->
            key(ToolCategory.SCREEN_UI, ToolAccess.READ)
        "ui_click", "ui_long_click", "ui_set_text", "ui_scroll", "ui_swipe", "ui_global" ->
            key(ToolCategory.SCREEN_UI, ToolAccess.WRITE)
        "voice_listen_start", "voice_listen_stop" ->
            key(ToolCategory.MICROPHONE, ToolAccess.READ)
        "location_get" -> key(ToolCategory.GEOLOCATION, ToolAccess.READ)
        "device_info", "device_state" -> key(ToolCategory.DEVICE, ToolAccess.READ)
        "volume_set", "dnd_set", "brightness_set", "flashlight", "media_button",
        "alarm_set", "timer_set", "clipboard_copy",
        -> key(ToolCategory.DEVICE, ToolAccess.WRITE)
        "web_search", "web_fetch" -> key(ToolCategory.WEB, ToolAccess.READ)
        "app_list" -> key(ToolCategory.APPS_NAVIGATION, ToolAccess.READ)
        "app_launch", "open_settings", "web_open", "nav_open", "share_text" ->
            key(ToolCategory.APPS_NAVIGATION, ToolAccess.WRITE)
        "shell_get_setting" -> key(ToolCategory.PRIVILEGED, ToolAccess.READ)
        "shell_exec", "shell_put_setting" ->
            key(ToolCategory.PRIVILEGED, ToolAccess.WRITE)
        "bye" -> SERVICE
        else -> UNKNOWN
    }

    /** Категорийные группы известных тулов (для пресетов и списка «Запомненные разрешения»). */
    fun categoryGroups(): Set<String> =
        knownToolNames.map(::groupOf).toSet() - SERVICE
}
