package ru.outsideproarts.oassist.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.AgentToolEvent
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.AudioPart
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.Narrator
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.components.ApprovalDialog
import ru.outsideproarts.oassist.core.ui.components.OAssistPanel

/**
 * Одна реплика ленты. У ассистента три раздельные проекции:
 * [thought] — размышление/комментарий, [tools] — полотно вызовов,
 * [answer] — основной ответ. У пользователя [answer] — текст,
 * [thought]/[tools] пусты.
 */
data class ChatItem(
    val role: ChatRole,
    val thought: String = "",
    val tools: List<AgentToolEvent> = emptyList(),
    val answer: String = "",
    val isStreaming: Boolean = false,
    /** Голосовой ход PTT (история для модели несёт байты, пузырь — плейсхолдер). */
    val audio: AudioPart? = null,
    /** Статистика хода (токены/cost от провайдера); null — провайдер не прислал. */
    val usage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null,
)

/** Холд Send короче — случайный тап: отмена с хинтом, не отправка. */
private const val HOLD_MIN_MS = 500L

/** Отложенный ход: сеть пропала до/в начале стрима, повторим фронтом online. */
private data class PendingChatTurn(val text: String, val audio: AudioPart?)

/** Проекция пузыря ленты в историю модели: только основной ответ ассистента —
 * мысль и тулы уже связаны tool-протоколом и не должны дублироваться текстом. */
private fun ChatItem.toHistoryMessage(): ChatMessage = ChatMessage(
    role = role,
    content = answer,
    audio = audio,
)

@Composable
fun ChatScreen(
    sendMessage: suspend (String, List<ChatMessage>, AudioPart?) -> AgentSession,
    modifier: Modifier = Modifier,
    /** Озвучить одно завершённое предложение (неблокирующе). Null — озвучка выключена. */
    speakSentence: ((String) -> Unit)? = null,
    /** Стоп / barge-in для озвучки. */
    stopSpeech: (() -> Unit)? = null,
    /** Озвучивать комментарии к вызовам тулов (флаг TtsConfig режима). Тип тула нем всегда. */
    speakToolCalls: Boolean = true,
    /** Голосовой ввод режима (движок + язык). */
    stt: SttConfig = SttConfig.DEFAULT,
    /** У модели флаг AUDIO_IN (только ручной) — иначе MODEL_AUDIO падает в STT. */
    audioIn: Boolean = false,
    /** Лента нарратива для сворачиваемого timeline. Null — без timeline. */
    timeline: SharedFlow<Narrator.TimelineEntry>? = null,
    /** Итоги использования за жизнь процесса (in-memory). Null — без строки итогов. */
    usageTotals: kotlinx.coroutines.flow.StateFlow<ru.outsideproarts.oassist.core.model.UsageTotalsState>? = null,
) {
    val messages = remember { mutableStateListOf<ChatItem>() }
    val timelineEntries = remember { mutableStateListOf<Narrator.TimelineEntry>() }
    val emptyTotals = remember { kotlinx.coroutines.flow.MutableStateFlow(ru.outsideproarts.oassist.core.model.UsageTotalsState()) }
    val usageTotalsState by (usageTotals ?: emptyTotals).collectAsState()
    var input by remember { mutableStateOf("") }
    /** Живой preview распознавания — НЕ в поле ввода, уходит сразу в сообщение. */
    var sttPreview by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    /** Ход, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    var pendingRetry by remember { mutableStateOf<PendingChatTurn?>(null) }
    var pendingApproval by remember { mutableStateOf<Pair<ApprovalRequest, AgentLoop>?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(timeline) {
        timeline?.collect { timelineEntries.add(it) }
    }

    val netHandle = remember { ru.outsideproarts.oassist.core.net.NetworkHandle.get() }
    val offlineRetryText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
    val repeatingText = stringResource(ru.outsideproarts.oassist.core.ui.R.string.net_repeating)

    // Конец диалога (уход с экрана) — забыть «разрешено на текущую сессию»
    // и обнулить итоги использования (статистика живёт в рамках сессии чата).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val factory = ru.outsideproarts.oassist.core.agent.AgentFactory.get()
                factory.clearSessionApprovals(ru.outsideproarts.oassist.core.model.AssistMode.CHAT)
                factory.resetUsageTotals()
            }
        }
    }

    val title = stringResource(R.string.chat_title)
    val emptyTitle = stringResource(R.string.chat_empty_title)
    val emptyBody = stringResource(R.string.chat_empty_body)
    val sendLabel = stringResource(R.string.chat_send)
    val stopLabel = stringResource(R.string.chat_stop)
    val inputLabel = stringResource(R.string.chat_input_hint)
    val speakAnswerLabel = stringResource(R.string.chat_speak_answer)
    val context = LocalContext.current
    val micLabel = stringResource(R.string.chat_mic)
    val micDeniedText = stringResource(R.string.chat_mic_denied)
    val noRecognizerText = stringResource(R.string.chat_no_recognizer)
    val micEmptyText = stringResource(R.string.chat_mic_empty)
    val micErrorText = stringResource(R.string.chat_mic_error)
    val holdHintText = stringResource(R.string.chat_hold_hint)
    val voiceShortText = stringResource(R.string.chat_voice_short)
    val listeningText = stringResource(R.string.chat_listening)

    // Голосовой ввод — единый in-app захват (VoiceHandle поверх VoiceListener).
    // Распознанное НЕ пишется в поле: финал уходит сразу в send(), partials —
    // только в preview-статус. Порядок: send → слушатель → старт/стоп → mic.
    val voice = remember { VoiceHandle.get() }
    var capturing by remember { mutableStateOf(false) }
    LaunchedEffect(voice) {
        voice?.captureState()?.collect { capturing = it is CaptureState.Listening }
    }
    // Запуск/повтор стрима. Висящий пузырь ожидания сети (пустой, streaming)
    // переиспользуется — иначе после повтора в ленте было бы два пузыря ответа.
    // Текстовый порядок: launchTurn раньше send (локальные fun вызываются только ниже).
    fun launchTurn(userMsg: String, audio: AudioPart?) {
        val history: List<ChatMessage>
        val assistantIndex: Int
        val waiting = messages.lastOrNull()
        if (waiting != null && waiting.role == ChatRole.ASSISTANT && waiting.isStreaming &&
            waiting.thought.isEmpty() && waiting.answer.isEmpty() && waiting.tools.isEmpty()
        ) {
            assistantIndex = messages.size - 1
            history = messages.dropLast(1).map { it.toHistoryMessage() }
        } else {
            history = messages.map { it.toHistoryMessage() }
            assistantIndex = messages.size
            messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
        }
        streamJob = scope.launch {
            val thoughtSb = StringBuilder()
            val answerSb = StringBuilder()
            val toolEvents = mutableListOf<AgentToolEvent>()
            var turnUsage: ru.outsideproarts.oassist.core.model.ProviderUsage? = null
            // Разделение в синтезе: мысль и ответ — два независимых спикера
            // с собственными счётчиками; тулы немы всегда (раньше вырезались
            // stripServiceLines из общей каши — теперь не попадают вовсе).
            val thoughtSpeaker = StreamSpeaker(
                speak = { speakSentence?.invoke(it) },
                speakToolCalls = speakToolCalls,
            )
            val answerSpeaker = StreamSpeaker(
                speak = { speakSentence?.invoke(it) },
                speakToolCalls = true,
            )
            fun publish(streaming: Boolean) {
                messages[assistantIndex] = ChatItem(
                    role = ChatRole.ASSISTANT,
                    thought = thoughtSb.toString(),
                    tools = toolEvents.toList(),
                    answer = answerSb.toString(),
                    isStreaming = streaming,
                    usage = turnUsage,
                )
            }
            var error: ru.outsideproarts.oassist.core.provider.ProviderError? = null
            var cancelled = false
            var sawAbrupt = false
            try {
                val session = sendMessage(userMsg, history, audio)
                error = session.chunks.collectSafe { chunk ->
                    when (chunk) {
                        is ChatChunk.Content -> {
                            if (chunk.isToolComment) {
                                thoughtSb.append(chunk.delta)
                                publish(true)
                                thoughtSpeaker.onText(thoughtSb.toString(), true)
                            } else {
                                answerSb.append(chunk.delta)
                                publish(true)
                                answerSpeaker.onText(answerSb.toString(), false)
                            }
                        }
                        is ChatChunk.ToolCall -> {
                            toolEvents.add(AgentToolEvent(name = chunk.name))
                            publish(true)
                        }
                        is ChatChunk.ToolResult -> {
                            val pending = toolEvents.indexOfLast {
                                it.name == chunk.name && it.output == null
                            }
                            val event = AgentToolEvent(
                                name = chunk.name,
                                output = chunk.output,
                                isError = chunk.isError,
                            )
                            if (pending >= 0) toolEvents[pending] = event
                            else toolEvents.add(event)
                            publish(true)
                        }
                        is ChatChunk.ApprovalNeeded -> {
                            pendingApproval = chunk.request to session.agent
                        }
                        is ChatChunk.Finish -> {
                            thoughtSpeaker.onFinish(thoughtSb.toString())
                            answerSpeaker.onFinish(answerSb.toString())
                            turnUsage = chunk.usage
                            publish(false)
                            // Поток закрылся без терминатора: пометку покажем после
                            // сбора (`error` внутри своей лямбды-инициализатора
                            // ещё не в scope — поэтому отдельный флаг).
                            if (chunk.abrupt) sawAbrupt = true
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                // onAskAgent бросает ProviderError.NotConfigured до создания сессии
                // (нет провайдера/модели): без перехвата — падение процесса.
                error = ru.outsideproarts.oassist.core.provider.ProviderError.from(e)
            } finally {
                // finally, а не строки после collect: при отмене
                // CancellationException пропускает всё ниже collect.
                isStreaming = false
                pendingApproval = null
                streamJob = null
                if (thoughtSb.isEmpty() && answerSb.isEmpty() && toolEvents.isEmpty() &&
                    (error != null || cancelled)
                ) {
                    messages.removeAt(assistantIndex)
                } else {
                    publish(false)
                }
            }
            if (error != null) {
                val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                    thoughtSb.isEmpty() && answerSb.isEmpty() && toolEvents.isEmpty()
                ) {
                    // Упали до первого чанка без сети (пустой пузырь уже убран в finally).
                    // Возвращаем состояние ожидания — как в пре-чеке send(): ждущий
                    // пузырь + isStreaming=true. Иначе эффект фронта не отличит
                    // ожидание от готовности и не повторит ход.
                    pendingRetry = PendingChatTurn(userMsg, audio)
                    messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
                    isStreaming = true
                    Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, error.userMessage(context), Toast.LENGTH_LONG).show()
                }
            } else if (sawAbrupt) {
                // Поток закрылся без терминатора: ответ мог быть усечён.
                Toast.makeText(
                    context,
                    ru.outsideproarts.oassist.core.provider.R.string.provider_stream_truncated,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    fun send(text: String, audio: AudioPart? = null) {
        val userMsg = text.trim()
        if ((userMsg.isEmpty() && audio == null) || isStreaming) return
        messages.add(ChatItem(ChatRole.USER, answer = userMsg, audio = audio))
        isStreaming = true
        // Barge-in: новый вопрос прерывает озвучку предыдущего ответа.
        stopSpeech?.invoke()
        messages.add(ChatItem(ChatRole.ASSISTANT, isStreaming = true))
        val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
        if (net != null && !net.isOnline.value) {
            // Оффлайн: пузыри уже в ленте, стрим стартует фронтом сети (эффект ниже).
            pendingRetry = PendingChatTurn(userMsg, audio)
            Toast.makeText(context, offlineRetryText, Toast.LENGTH_LONG).show()
            return
        }
        pendingRetry = null
        launchTurn(userMsg, audio)
    }
    // Стоп хода: отмена стрима или снятие ожидания сети. Без запущенной job
    // висящие пузыри ожидания убираются и флаг сбрасывается здесь
    // (finally(back) несуществующего стрима не будет).
    fun cancelTurn() {
        pendingRetry = null
        val hadJob = streamJob != null
        streamJob?.cancel()
        stopSpeech?.invoke()
        if (!hadJob && isStreaming) {
            messages.lastOrNull()
                ?.takeIf {
                    it.role == ChatRole.ASSISTANT && it.isStreaming &&
                        it.thought.isEmpty() && it.answer.isEmpty() && it.tools.isEmpty()
                }?.let { messages.removeAt(messages.size - 1) }
            isStreaming = false
        }
    }
    // Авто-повтор отложенного хода только по фронту offline→online:
    // повтор «по уровню» дал бы петлю при флэппинге сети.
    // Позиция — после launchTurn/send/cancelTurn: эффект их вызывает,
    // локальные fun видны только ниже объявления.
    LaunchedEffect(netHandle) {
        var wasOnline = netHandle?.isOnline?.value ?: true
        netHandle?.isOnline?.collect { online ->
            if (online && !wasOnline) {
                val retry = pendingRetry
                // Стэш снимаем только при фактическом потреблении: потерянный
                // ход не должен молча исчезнуть при гонке с активным стримом.
                if (retry != null && streamJob == null && isStreaming) {
                    pendingRetry = null
                    Toast.makeText(context, repeatingText, Toast.LENGTH_SHORT).show()
                    launchTurn(retry.text, retry.audio)
                }
            }
            wasOnline = online
        }
    }
    // Слушатель системного STT: финал — сразу в send(), в поле не пишем.
    fun sttListener(): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            sttPreview = text
        }

        override fun onFinal(text: String) {
            sttPreview = ""
            if (text.isNotBlank()) {
                input = ""
                send(text)
            }
        }

        override fun onSttError(error: SttError) {
            sttPreview = ""
            Toast.makeText(
                context,
                when (error) {
                    SttError.NO_SPEECH -> micEmptyText
                    SttError.NETWORK -> micErrorText
                    SttError.BUSY -> micErrorText
                    SttError.UNKNOWN -> micErrorText
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun sttErrorToast(error: CaptureError) {
        sttPreview = ""
        Toast.makeText(
            context,
            when (error) {
                CaptureError.NO_RECOGNIZER -> noRecognizerText
                CaptureError.NO_PERMISSION -> micDeniedText
                CaptureError.BUSY,
                CaptureError.INIT_FAILED -> micErrorText
            },
            Toast.LENGTH_LONG,
        ).show()
    }
    // mic-разрешение выдаём системным диалогом; дальше — сразу слушать.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice?.takeIf { it.isSttAvailable() }?.startStt(stt.language, sttListener())
                ?.let { sttErrorToast(it) }
                ?: voice?.let { sttErrorToast(CaptureError.NO_RECOGNIZER) }
        } else {
            Toast.makeText(context, micDeniedText, Toast.LENGTH_LONG).show()
        }
    }
    // Тап микрофона — toggle STT (доступная альтернатива удержанию).
    fun toggleSttMic() {
        val handle = voice ?: return
        // Barge-in: прерываем и стрим, и озвучку, и активный захват
        // (и ожидание сети, если ход отложен).
        if (isStreaming) {
            cancelTurn()
        }
        when (val current = handle.captureState().value) {
            is CaptureState.Listening -> {
                if (current.mode == ru.outsideproarts.oassist.core.speech.CaptureMode.STT) {
                    handle.stopStt()
                } else {
                    handle.cancelRaw()
                }
            }
            else -> {
                if (!handle.isSttAvailable()) {
                    sttErrorToast(CaptureError.NO_RECOGNIZER)
                    return
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    handle.startStt(stt.language, sttListener())?.let { sttErrorToast(it) }
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    // Удержание Mic при пустом поле: RAW (аудио модели) или STT по режиму.
    // MODEL_AUDIO без флага AUDIO_IN у модели — молча в STT (fail-safe).
    fun useRawCapture(): Boolean = stt.engine == SttEngine.MODEL_AUDIO && audioIn
    fun startHold(): Boolean {
        val handle = voice ?: return false
        if (handle.captureState().value is CaptureState.Listening) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        if (useRawCapture()) {
            handle.startRaw()?.let { sttErrorToast(it); return false }
        } else {
            if (!handle.isSttAvailable()) {
                sttErrorToast(CaptureError.NO_RECOGNIZER)
                return false
            }
            handle.startStt(stt.language, sttListener())?.let { sttErrorToast(it); return false }
        }
        return true
    }
    // Случайное короткое касание — отмена без шума в эфир.
    fun cancelHold() {
        val handle = voice ?: return
        when ((handle.captureState().value as? CaptureState.Listening)?.mode) {
            ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> handle.cancelStt()
            ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> handle.cancelRaw()
            null -> Unit
        }
        sttPreview = ""
        Toast.makeText(context, holdHintText, Toast.LENGTH_SHORT).show()
    }
    // Отпускание холда: STT — финал придёт колбэком, RAW — забираем WAV.
    fun stopHold() {
        val handle = voice ?: return
        when ((handle.captureState().value as? CaptureState.Listening)?.mode) {
            ru.outsideproarts.oassist.core.speech.CaptureMode.STT -> handle.stopStt()
            ru.outsideproarts.oassist.core.speech.CaptureMode.RAW -> {
                val wav = handle.stopRaw()
                if (wav == null) {
                    Toast.makeText(context, voiceShortText, Toast.LENGTH_SHORT).show()
                } else {
                    val base64 = android.util.Base64.encodeToString(wav.wav, android.util.Base64.NO_WRAP)
                    send(
                        "",
                        AudioPart(base64, durationSec = wav.durationSec),
                    )
                }
            }
            null -> Unit
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // Метка последнего холда: клик сразу после удержания — эхо onClick, игнор.
    var lastHoldEnd by remember { mutableStateOf(0L) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OAssistDimens.ScreenContentPadding)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .oassistHeading(),
        )

        // Лента всегда с весом: поле ввода прибито к низу и не гуляет.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            if (messages.isEmpty()) {
                item {
                    OAssistPanel(title = emptyTitle) {
                        Text(
                            text = emptyBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(messages) { item ->
                    val canSpeak = speakSentence != null &&
                        item.role == ChatRole.ASSISTANT &&
                        item.answer.isNotBlank()
                    MessageBubble(
                        item = item,
                        onSpeak = if (canSpeak) {
                            {
                                SentenceSplitter.splitSentences(item.answer).forEach {
                                    speakSentence?.invoke(it)
                                }
                            }
                        } else {
                            null
                        },
                        speakLabel = speakAnswerLabel,
                    )
                }
                // Итоги всего чата — сворачиваемый блок «Ход работы» внизу ленты.
                if (!usageTotalsState.isEmpty) {
                    item {
                        ChatTotalsBlock(totals = usageTotalsState)
                    }
                }
            }
        }

        if (timeline != null) {
            TimelinePanel(entries = timelineEntries)
        }

        if (capturing) {
            // Статус захвата — уместный live region (не стрим контента).
            // Preview распознавания — здесь, не в поле ввода.
            val status = if (sttPreview.isBlank()) listeningText else "$listeningText $sttPreview"
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Нижняя панель мессенджера: поле + одна action-кнопка
        // (Send / Stop / Mic). Войс — холд Mic, тап — toggle для TalkBack.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = OAssistDimens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(inputLabel) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            val actionContent: String
            val actionIcon = when {
                isStreaming -> {
                    actionContent = stopLabel
                    Icons.Default.Stop
                }
                capturing -> {
                    actionContent = stopLabel
                    Icons.Default.Stop
                }
                input.isBlank() -> {
                    actionContent = holdHintText
                    Icons.Default.Mic
                }
                else -> {
                    actionContent = sendLabel
                    Icons.AutoMirrored.Filled.Send
                }
            }
            IconButton(
                onClick = {
                    if (isStreaming) {
                        // Стоп: отмена коллекции снимает стрим, паузу подтверждения и HTTP-запрос.
                        // Озвучку тоже гасим (barge-in): недоговорённое не должно дочитываться.
                        // Без job — снимает ожидание сети (см. cancelTurn).
                        cancelTurn()
                        return@IconButton
                    }
                    // Эхо клика после холда — игнор.
                    if (System.currentTimeMillis() - lastHoldEnd < 300L) return@IconButton
                    if (capturing) {
                        toggleSttMic()
                        return@IconButton
                    }
                    if (input.isNotBlank()) {
                        val userMsg = input.trim()
                        input = ""
                        sttPreview = ""
                        send(userMsg)
                    } else {
                        toggleSttMic()
                    }
                },
                modifier = Modifier
                    .oassistTouchTarget()
                    .pointerInput(input.isBlank(), isStreaming, capturing) {
                        detectTapGestures(
                            onPress = {
                                if (input.isBlank() && !isStreaming && !capturing && startHold()) {
                                    val downAt = System.currentTimeMillis()
                                    tryAwaitRelease()
                                    lastHoldEnd = System.currentTimeMillis()
                                    // Случайный короткий тап — отмена с хинтом, не «не расслышал».
                                    if (System.currentTimeMillis() - downAt < HOLD_MIN_MS) {
                                        cancelHold()
                                    } else {
                                        stopHold()
                                    }
                                }
                            },
                        )
                    },
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = if (input.isBlank() && !isStreaming && !capturing) {
                        // Подсказка холда вслух; имя «Голосовой ввод» — в toggle-пути.
                        "$micLabel. $actionContent"
                    } else {
                        actionContent
                    },
                )
            }
        }
    }

    pendingApproval?.let { (request, agent) ->
        ApprovalDialog(
            request = request,
            onDecision = { decision ->
                agent.resolveApproval(request.callId, decision)
                pendingApproval = null
            },
        )
    }
}

@Composable
private fun MessageBubble(
    item: ChatItem,
    onSpeak: (() -> Unit)?,
    speakLabel: String,
) {
    val isUser = item.role == ChatRole.USER
    val roleLabel = if (isUser) stringResource(R.string.chat_role_user) else stringResource(R.string.chat_role_assistant)
    // Голосовой ход: байты в пузырь не кладём — плейсхолдер с длительностью.
    val bodyText = item.audio?.let { stringResource(R.string.chat_voice_message, it.durationSec) }
        ?: item.answer.ifEmpty { "…" }

    // Видимый текст — НЕ live region: иначе TalkBack перечитывал бы весь ответ
    // на каждой дельте. Озвучка идёт через канал речи (TTS/скринридер) попредложенно,
    // повтор — действием «Озвучить ответ».
    val speakModifier = if (onSpeak != null) {
        Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(speakLabel) { onSpeak(); true },
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { paneTitle = roleLabel },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Полотно «Ход работы» (мысль + вызовы + статистика хода) — сворачиваемо,
        // по умолчанию свёрнуто. Статистика живёт внутри блока, не отдельной строкой.
        if (!isUser && (item.thought.isNotBlank() || item.tools.isNotEmpty() || item.usage != null)) {
            WorkBlock(thought = item.thought, tools = item.tools, usage = item.usage)
        }
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = speakModifier,
        )
    }
}

/**
 * Свёрнутое по умолчанию полотно мысли и вызовов инструментов.
 * Мысль и вызовы объединены в одну сущность (разворачиваются вместе).
 */
@Composable
private fun WorkBlock(
    thought: String,
    tools: List<AgentToolEvent>,
    usage: ru.outsideproarts.oassist.core.model.ProviderUsage?,
) {
    val title = stringResource(R.string.chat_work_title)
    val expandedState = stringResource(R.string.chat_timeline_expanded)
    val collapsedState = stringResource(R.string.chat_timeline_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedState else collapsedState
    val toggleLabel = "$title. $state"
    val actionLabel = if (expanded) stringResource(R.string.chat_action_collapse)
                      else stringResource(R.string.chat_action_expand)

    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics {
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            expanded = !expanded
                            true
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = actionLabel },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            ru.outsideproarts.oassist.core.ui.components.OAssistUsageLine(usage = usage)
            if (thought.isNotBlank()) {
                Text(
                    text = thought.take(2000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tools.takeLast(20).forEach { tool ->
                val out = tool.output
                val line = if (out.isNullOrBlank()) {
                    stringResource(R.string.chat_timeline_tool_call, tool.name)
                } else {
                    stringResource(
                        R.string.chat_timeline_tool_result,
                        tool.name,
                    ) + ": " + out.take(500)
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tool.images.isNotEmpty()) {
                    val imageDesc = stringResource(R.string.chat_image_attached)
                    tool.images.forEach { img ->
                        ru.outsideproarts.oassist.core.ui.components.OAssistImageThumbnail(
                            base64 = img.imageBase64,
                            contentDescription = imageDesc,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Итоги всего чата — сворачиваемый блок «Ход работы» внизу ленты
 * (аналог per-message WorkBlock, но без мысли/тулов — только «Всего»).
 */
@Composable
private fun ChatTotalsBlock(totals: ru.outsideproarts.oassist.core.model.UsageTotalsState) {
    val title = stringResource(R.string.chat_work_title)
    val expandedState = stringResource(R.string.chat_timeline_expanded)
    val collapsedState = stringResource(R.string.chat_timeline_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedState else collapsedState
    val toggleLabel = "$title. $state"
    val actionLabel = if (expanded) stringResource(R.string.chat_action_collapse)
                      else stringResource(R.string.chat_action_expand)

    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics {
                    stateDescription = state
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            expanded = !expanded
                            true
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = actionLabel },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            ru.outsideproarts.oassist.core.ui.components.OAssistUsageTotalsLine(totals = totals)
        }
    }
}

@Composable
private fun TimelinePanel(entries: List<Narrator.TimelineEntry>) {
    val title = stringResource(R.string.chat_timeline_title)
    val emptyText = stringResource(R.string.chat_timeline_empty)
    val expandedText = stringResource(R.string.chat_timeline_expanded)
    val collapsedText = stringResource(R.string.chat_timeline_collapsed)
    var expanded by remember { mutableStateOf(false) }
    val state = if (expanded) expandedText else collapsedText
    val actionLabel = if (expanded) stringResource(R.string.chat_action_collapse)
                      else stringResource(R.string.chat_action_expand)

    OAssistPanel(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .oassistTouchTarget()
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .oassistHeading(),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = actionLabel },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) {
            val visible = entries.filter {
                it is Narrator.TimelineEntry.Thought ||
                    it is Narrator.TimelineEntry.ToolCall ||
                    it is Narrator.TimelineEntry.ToolResult
            }
            if (visible.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.takeLast(20).forEach { entry ->
                    Text(
                        text = timelineText(entry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun timelineText(entry: Narrator.TimelineEntry): String = when (entry) {
    is Narrator.TimelineEntry.Thought -> entry.text.take(300)
    is Narrator.TimelineEntry.ToolCall ->
        stringResource(R.string.chat_timeline_tool_call, entry.toolName)
    is Narrator.TimelineEntry.ToolResult ->
        stringResource(R.string.chat_timeline_tool_result, entry.toolName)
    is Narrator.TimelineEntry.UserMessage -> entry.text.take(300)
    is Narrator.TimelineEntry.AgentMessage -> entry.text.take(300)
}
