package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние голосового цикла оверлея ассистента. */
sealed interface AssistVoiceState {
    data object Listening : AssistVoiceState
    data object Busy : AssistVoiceState
    data object Idle : AssistVoiceState
    data object Ended : AssistVoiceState
}

/** Команды хосту (оверлей-сервису) после перехода состояния. */
sealed interface AssistVoiceEffect {
    data object StartStt : AssistVoiceEffect
    data object CancelStt : AssistVoiceEffect
    data class SendText(val text: String) : AssistVoiceEffect
    data object CancelTurn : AssistVoiceEffect
}

/**
 * Машина состояний голосового цикла оверлея ассистента (pure, тестируемая без Android).
 *
 * play/pause — переключатель голосового ввода: умная остановка (partial уходит агенту,
 * если непустой) с подавлением авто-диалога до следующего play/pause; в Busy — barge-in.
 * assist-жест — полный сброс в ON.
 */
class AssistVoiceFlow {

    private val _state = MutableStateFlow<AssistVoiceState>(AssistVoiceState.Idle)
    val state: StateFlow<AssistVoiceState> = _state.asStateFlow()

    /** Переключатель голосового ввода (play/pause); assist-жест всегда включает. */
    var voiceLoopOn: Boolean = true
        private set

    private var lastPartial: String = ""

    /** Первый показ оверлея: авто-старт микрофона, голосовой цикл включён. */
    fun onActivate(): List<AssistVoiceEffect> {
        lastPartial = ""
        voiceLoopOn = true
        _state.value = AssistVoiceState.Listening
        return listOf(AssistVoiceEffect.StartStt)
    }

    /** Повторный assist-жест при открытом окне: полный сброс ON + barge-in. */
    fun onReactivate(): List<AssistVoiceEffect> {
        lastPartial = ""
        voiceLoopOn = true
        _state.value = AssistVoiceState.Listening
        return listOf(
            AssistVoiceEffect.CancelTurn,
            AssistVoiceEffect.CancelStt,
            AssistVoiceEffect.StartStt,
        )
    }

    fun onSttPartial(text: String) {
        if (_state.value is AssistVoiceState.Listening) lastPartial = text
    }

    /** Финал распознавания: непустой текст уходит агенту; пусто — ничего не делаем. */
    fun onSttFinal(text: String): List<AssistVoiceEffect> {
        if (_state.value !is AssistVoiceState.Listening) return emptyList()
        lastPartial = ""
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) {
            _state.value = AssistVoiceState.Idle
            emptyList()
        } else {
            _state.value = AssistVoiceState.Busy
            listOf(AssistVoiceEffect.SendText(trimmed))
        }
    }

    /** Отправка текста из поля ввода: заменяет текущее прослушивание; в Busy игнорируется. */
    fun onUserSubmit(text: String): List<AssistVoiceEffect> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return when (_state.value) {
            is AssistVoiceState.Listening -> {
                lastPartial = ""
                _state.value = AssistVoiceState.Busy
                listOf(AssistVoiceEffect.CancelStt, AssistVoiceEffect.SendText(trimmed))
            }
            is AssistVoiceState.Idle -> {
                _state.value = AssistVoiceState.Busy
                listOf(AssistVoiceEffect.SendText(trimmed))
            }
            else -> emptyList()
        }
    }

    /** Хост вызывает, когда ответ договорен (Finish + озвучка дошла до idle). */
    fun onTurnFinished(continueDialog: Boolean): List<AssistVoiceEffect> {
        if (_state.value !is AssistVoiceState.Busy) return emptyList()
        return if (continueDialog && voiceLoopOn) {
            lastPartial = ""
            _state.value = AssistVoiceState.Listening
            listOf(AssistVoiceEffect.StartStt)
        } else {
            _state.value = AssistVoiceState.Idle
            emptyList()
        }
    }

    /**
     * play/pause (или тап микрофона) — переключатель голосового ввода.
     * Listening → умная остановка (partial агенту, если есть) и подавление авто-диалога;
     * Busy → barge-in + слушать; Idle → слушать (авто-диалог снова включён).
     */
    fun onPlayPause(): List<AssistVoiceEffect> {
        return when (_state.value) {
            is AssistVoiceState.Listening -> {
                val partial = lastPartial.trim()
                lastPartial = ""
                voiceLoopOn = false
                _state.value =
                    if (partial.isNotEmpty()) AssistVoiceState.Busy else AssistVoiceState.Idle
                buildList {
                    add(AssistVoiceEffect.CancelStt)
                    if (partial.isNotEmpty()) add(AssistVoiceEffect.SendText(partial))
                }
            }
            is AssistVoiceState.Busy -> {
                lastPartial = ""
                voiceLoopOn = true
                _state.value = AssistVoiceState.Listening
                listOf(AssistVoiceEffect.CancelTurn, AssistVoiceEffect.StartStt)
            }
            is AssistVoiceState.Idle -> {
                lastPartial = ""
                voiceLoopOn = true
                _state.value = AssistVoiceState.Listening
                listOf(AssistVoiceEffect.StartStt)
            }
            is AssistVoiceState.Ended -> emptyList()
        }
    }

    /** Старт STT не удался (нет разрешения/распознавателя) — откат в Idle. */
    fun onSttStartFailed() {
        if (_state.value is AssistVoiceState.Listening) _state.value = AssistVoiceState.Idle
    }

    /** Закрытие оверлея: сессия умирает вместе с контекстом. */
    fun onClose(): List<AssistVoiceEffect> {
        val wasActive = _state.value !is AssistVoiceState.Ended
        _state.value = AssistVoiceState.Ended
        if (!wasActive) return emptyList()
        return listOf(AssistVoiceEffect.CancelStt, AssistVoiceEffect.CancelTurn)
    }
}
