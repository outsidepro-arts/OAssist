package ru.outsideproarts.oassist.core.speech

/**
 * Точка доступа к озвучке для `feature:*` без зависимости от `app`.
 * Регистрируется в `AppGraph` (адаптер над [SpeechOutput] и `Narrator`),
 * зеркалит паттерн [ru.outsideproarts.oassist.core.agent.AgentFactory].
 */
interface SpeechHandle {
    /** Озвучить одно завершённое предложение (неблокирующе). */
    fun speak(sentence: String)

    /**
     * Озвучить фразу через текущий канал, дождавшись готовности движка
     * (после смены движка/голоса фраза иначе теряется). Для предпрослушивания
     * синтеза в настройках. false — канал не озвучивает или движок не поднялся.
     */
    suspend fun speakWhenReady(text: String): Boolean

    /** Дождаться обработки уже принятых фраз текущих каналов. */
    suspend fun awaitIdle()

    /** Стоп / barge-in. */
    fun stop()

    /** Идёт ли озвучка (очередь TTS непуста): false = всё договорено (для авто-продолжения диалога). */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean>

    /** Анонсер экранным чтецом (SCREEN_READER-канал); null — канал недоступен. */
    val announcer: ru.outsideproarts.oassist.core.a11y.FlowAnnouncer?

    /** Применить конфиг озвучки активного режима (канал + тембр + голос). */
    fun applyTts(config: ru.outsideproarts.oassist.core.model.TtsConfig)

    /** Вкл/выкл рассказ о работе для активного режима. */
    fun setNarrativeEnabled(enabled: Boolean)

    /**
     * Якорь анонсов скринридера (аналог TeamTalk `announcerObject`): View, от
     * которого постится `TYPE_ANNOUNCEMENT`. `null` — отвязать (не держать View).
     */
    fun setScreenReaderAnchor(view: android.view.View?)

    /** Объявление нарратива — всегда через экранный чтец, независимо от канала ответа. */
    fun announceToScreenReader(text: String)

    companion object {
        @Volatile
        private var instance: SpeechHandle? = null

        fun register(handle: SpeechHandle) {
            instance = handle
        }

        /** Null — озвучка недоступна (говорят только UI-подсказки, не падение). */
        fun get(): SpeechHandle? = instance
    }
}
