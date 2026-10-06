package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.data.memory.MemoryStore
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.device.PrivExecutor
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.ToolPreferences
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController

/** Метаданные инструмента для экрана настроек (без Context — только имя и дефолт). */
data class ToolDescriptor(
    val name: String,
    val defaultRisk: ActionRisk,
)

/**
 * Единая точка сборки каталога инструментов.
 * Раньше списки дублировались в `AppGraph` и `MainActivity` — теперь только здесь.
 */
object ToolCatalog {

    /**
     * Дескрипторы для UI настроек. Синхронизировать с [createTools]:
     * имена и дефолтные риски обязаны совпадать.
     */
    fun descriptors(): List<ToolDescriptor> = listOf(
        ToolDescriptor("app_launch", ActionRisk.AUTO),
        ToolDescriptor("web_open", ActionRisk.AUTO),
        ToolDescriptor("web_search", ActionRisk.AUTO),
        ToolDescriptor("alarm_set", ActionRisk.AUTO),
        ToolDescriptor("open_settings", ActionRisk.AUTO),
        ToolDescriptor("call_place", ActionRisk.ASK),
        ToolDescriptor("sms_send", ActionRisk.ASK),
        ToolDescriptor("contact_find", ActionRisk.AUTO),
        ToolDescriptor("calendar_add", ActionRisk.ASK),
        ToolDescriptor("timer_set", ActionRisk.AUTO),
        ToolDescriptor("device_info", ActionRisk.AUTO),
        ToolDescriptor("clipboard_copy", ActionRisk.AUTO),
        ToolDescriptor("ui_get_screen", ActionRisk.AUTO),
        ToolDescriptor("ui_click", ActionRisk.ASK),
        ToolDescriptor("ui_long_click", ActionRisk.ASK),
        ToolDescriptor("ui_set_text", ActionRisk.ASK),
        ToolDescriptor("ui_scroll", ActionRisk.ASK),
        ToolDescriptor("ui_swipe", ActionRisk.ASK),
        ToolDescriptor("ui_global", ActionRisk.AUTO),
        ToolDescriptor("ui_wait_for", ActionRisk.AUTO),
        ToolDescriptor("notify_list", ActionRisk.AUTO),
        ToolDescriptor("notify_open", ActionRisk.ASK),
        ToolDescriptor("notify_clear", ActionRisk.ASK),
        ToolDescriptor("web_fetch", ActionRisk.AUTO),
        ToolDescriptor("nav_open", ActionRisk.AUTO),
        ToolDescriptor("share_text", ActionRisk.AUTO),
        ToolDescriptor("flashlight", ActionRisk.AUTO),
        ToolDescriptor("media_button", ActionRisk.AUTO),
        ToolDescriptor("location_get", ActionRisk.ASK),
        ToolDescriptor("device_state", ActionRisk.AUTO),
        ToolDescriptor("voice_listen_start", ActionRisk.ASK),
        ToolDescriptor("voice_listen_stop", ActionRisk.AUTO),
        ToolDescriptor("memory_get", ActionRisk.AUTO),
        ToolDescriptor("memory_put", ActionRisk.ASK),
        ToolDescriptor("memory_forget", ActionRisk.ASK),
        ToolDescriptor("skill_list", ActionRisk.AUTO),
        ToolDescriptor("skill_get", ActionRisk.AUTO),
        ToolDescriptor("shell_exec", ActionRisk.ASK),
        ToolDescriptor("shell_get_setting", ActionRisk.AUTO),
        ToolDescriptor("shell_put_setting", ActionRisk.ASK),
        ToolDescriptor("notify_reply", ActionRisk.ASK),
        ToolDescriptor("volume_set", ActionRisk.ASK),
        ToolDescriptor("dnd_set", ActionRisk.ASK),
        ToolDescriptor("brightness_set", ActionRisk.ASK),
    )

    fun createTools(
        actions: DeviceActions,
        ui: UiActions,
        notify: NotifyActions,
        webContent: WebContentActions,
        webRender: WebRenderActions,
        voiceCapture: VoiceCaptureController,
        memory: MemoryStore,
        skills: SkillStore,
        priv: PrivExecutor,
    ): List<Tool> = listOf(
        AppLaunchTool(actions),
        WebOpenTool(actions),
        WebSearchTool(actions),
        AlarmSetTool(actions),
        OpenSettingsTool(actions),
        CallPlaceTool(actions),
        SmsSendTool(actions),
        ContactFindTool(actions),
        CalendarAddTool(actions),
        TimerSetTool(actions),
        DeviceInfoTool(actions),
        ClipboardCopyTool(actions),
        UiGetScreenTool(ui),
        UiClickTool(ui),
        UiLongClickTool(ui),
        UiSetTextTool(ui),
        UiScrollTool(ui),
        UiSwipeTool(ui),
        UiGlobalTool(ui),
        UiWaitForTool(ui),
        NotifyListTool(notify),
        NotifyOpenTool(notify),
        NotifyClearTool(notify),
        NotifyReplyTool(notify),
        WebFetchTool(WebFetcher(webContent, webRender)),
        NavOpenTool(actions),
        ShareTextTool(actions),
        FlashlightTool(actions),
        MediaButtonTool(actions),
        LocationGetTool(actions),
        DeviceStateTool(actions),
        VolumeSetTool(actions),
        DndSetTool(actions),
        BrightnessSetTool(actions),
        VoiceListenStartTool(voiceCapture),
        VoiceListenStopTool(voiceCapture),
        MemoryGetTool(memory),
        MemoryPutTool(memory),
        MemoryForgetTool(memory),
        SkillListTool(skills),
        SkillGetTool(skills),
        ShellExecTool(priv),
        ShellGetSettingTool(priv),
        ShellPutSettingTool(priv),
    )

    fun createRegistry(
        actions: DeviceActions,
        ui: UiActions,
        notify: NotifyActions,
        webContent: WebContentActions,
        webRender: WebRenderActions,
        voiceCapture: VoiceCaptureController,
        memory: MemoryStore,
        skills: SkillStore,
        prefs: ToolPreferences,
        priv: PrivExecutor,
        externalTools: List<Tool> = emptyList(),
    ): ToolRegistry {
        val builtIn = createTools(actions, ui, notify, webContent, webRender, voiceCapture, memory, skills, priv)
        val builtInNames = builtIn.map { it.schema.name }.toSet()
        // Внешние имена с коллизией отбрасываются: встроенные в приоритете.
        val external = externalTools.filter { it.schema.name !in builtInNames }
        return ToolRegistry(
            tools = builtIn + external,
            disabled = prefs.disabled,
            askOverrides = prefs.askOverrides,
        )
    }
}
