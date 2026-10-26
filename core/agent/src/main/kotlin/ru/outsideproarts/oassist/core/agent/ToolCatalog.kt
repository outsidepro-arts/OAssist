package ru.outsideproarts.oassist.core.agent

import ru.outsideproarts.oassist.core.data.memory.MemoryStore
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.device.PrivExecutor
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.model.ToolPreferences
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController

/** Метаданные инструмента для экрана настроек (без Context — имя и группа разрешений). */
data class ToolDescriptor(
    val name: String,
    val groupKey: String = ToolGroups.groupOf(name),
)

/**
 * Единая точка сборки каталога инструментов.
 * Раньше списки дублировались в `AppGraph` и `MainActivity` — теперь только здесь.
 */
object ToolCatalog {

    /**
     * Дескрипторы для UI настроек. Синхронизировать с [createTools].
     * Группы разрешений — [ToolGroups.groupOf]; порядок задаёт группировку на экране.
     */
    fun descriptors(): List<ToolDescriptor> =
        ToolGroups.knownToolNames.map { ToolDescriptor(it) }

    fun createTools(
        actions: DeviceActions,
        ui: UiActions,
        notify: NotifyActions,
        media: MediaActions,
        webContent: WebContentActions,
        webRender: WebRenderActions,
        voiceCapture: VoiceCaptureController,
        memory: MemoryStore,
        skills: SkillStore,
        priv: PrivExecutor,
        search: SearchActions,
    ): List<Tool> = listOf(
        AppListTool(actions),
        AppActivitiesTool(actions),
        AppLaunchTool(actions),
        AppStartActivityTool(actions),
        WebOpenTool(actions),
        WebSearchTool(search),
        AlarmSetTool(actions),
        OpenSettingsTool(actions),
        CallPlaceTool(actions),
        SmsSendTool(actions),
        ContactFindTool(actions),
        CalendarAddTool(actions),
        CallLogListTool(actions),
        TimerSetTool(actions),
        DeviceInfoTool(actions),
        ClipboardCopyTool(actions),
        UiGetScreenTool(ui),
        UiScreenshotTool(ui),
        UiScreenshotRegionTool(ui),
        UiScreenshotNodeTool(ui),
        UiClickTool(ui),
        UiLongClickTool(ui),
        UiSetTextTool(ui),
        UiScrollTool(ui),
        UiSwipeTool(ui),
        UiGlobalTool(ui),
        UiWaitForTool(ui),
        UiGetActionsTool(ui),
        UiPerformActionTool(ui),
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
        MediaNowPlayingTool(media),
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
        ShellGetSettingTool(priv, actions.settingsAccess),
        ShellPutSettingTool(priv, actions.settingsAccess),
        ByeTool(),
    )

    fun createRegistry(
        actions: DeviceActions,
        ui: UiActions,
        notify: NotifyActions,
        media: MediaActions,
        webContent: WebContentActions,
        webRender: WebRenderActions,
        voiceCapture: VoiceCaptureController,
        memory: MemoryStore,
        skills: SkillStore,
        prefs: ToolPreferences,
        priv: PrivExecutor,
        search: SearchActions,
        externalTools: List<Tool> = emptyList(),
    ): ToolRegistry {
        val builtIn = createTools(actions, ui, notify, media, webContent, webRender, voiceCapture, memory, skills, priv, search)
        val builtInNames = builtIn.map { it.schema.name }.toSet()
        // Внешние имена с коллизией отбрасываются: встроенные в приоритете.
        val external = externalTools.filter { it.schema.name !in builtInNames }
        return ToolRegistry(
            tools = builtIn + external,
            disabled = prefs.disabled,
        )
    }
}
