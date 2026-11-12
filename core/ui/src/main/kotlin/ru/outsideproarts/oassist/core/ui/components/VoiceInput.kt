package ru.outsideproarts.oassist.core.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.outsideproarts.oassist.core.model.AudioPart
import ru.outsideproarts.oassist.core.model.SttConfig
import ru.outsideproarts.oassist.core.model.SttEngine
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.BtInputDecision
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.CaptureMode
import ru.outsideproarts.oassist.core.speech.CaptureState
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.ToggleSttOwner
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.speech.VoiceHoldSession
import ru.outsideproarts.oassist.core.speech.VoiceInputRoute

/** Строки сообщений голосового ввода: каждый экран владеет своими ресурсами. */
data class VoiceInputStrings(
    val micDenied: String,
    val micEmpty: String,
    val micError: String,
    val noRecognizer: String,
    val btFallback: String,
    val voiceShort: String,
)

/**
 * Поведение голосового ввода: toggle-STT по тапу (доступная альтернатива
 * удержанию), hold-to-talk (STT или RAW-аудио модели при AUDIO_IN),
 * live-preview, системные разрешения и фолбэк с гарнитуры. Общий контроллер
 * Chat и Share; кнопку ([ChatInputButton]) и отображение превью рисует экран.
 */
class VoiceInputController internal constructor(
    private val context: Context,
    private val voice: VoiceHandle?,
    private val requestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
    private val requestBtPermission: (onResult: (Boolean) -> Unit) -> Unit,
) {
    // Входы — синхронизируются фабрикой при каждой рекомпозиции.
    internal var strings: VoiceInputStrings = VoiceInputStrings("", "", "", "", "", "")
    internal var stt: SttConfig = SttConfig.DEFAULT
    internal var audioIn: Boolean = false
    internal var soundEnabled: Boolean = true
    internal var canCapture: Boolean = true
    internal var isStreaming: Boolean = false
    internal var onBargeIn: () -> Unit = {}
    internal var stopSpeech: (() -> Unit)? = null
    internal var onSendText: (String) -> Unit = {}
    internal var onSendAudio: (AudioPart) -> Unit = {}

    /** Идёт захват (toggle-STT или удержание). */
    var capturing by mutableStateOf(false)
        internal set

    /** Живой текст распознавания (live-preview, live-region экрана). */
    var preview by mutableStateOf("")
        private set

    /** Очистить preview (например, при ручной отправке текста). */
    fun clearPreview() {
        preview = ""
    }

    /** Идёт удержание микрофона (hold-to-talk). */
    val holdActive: Boolean
        get() = holdSession != null

    private var holdSession: VoiceHoldSession? = null
    private val toggleOwner = ToggleSttOwner(voice)

    internal fun ownerResume() = toggleOwner.resume()
    internal fun ownerPause() = toggleOwner.pause()
    internal fun ownerDispose() = toggleOwner.dispose()
    internal fun leaveDestination() = toggleOwner.leaveDestination()
    internal fun whenActive(action: () -> Unit) = toggleOwner.whenActive(action)

    private fun sttListener(hold: VoiceHoldSession? = null): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            preview = text
        }

        override fun onFinal(text: String) {
            preview = ""
            SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
            if (hold == null) sendRecognizedText(text) else hold.onFinal(text)?.let(::sendRecognizedText)
        }

        override fun onSttError(error: SttError) {
            hold?.onError()
            preview = ""
            SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
            Toast.makeText(
                context,
                when (error) {
                    SttError.NO_SPEECH -> strings.micEmpty
                    SttError.NETWORK -> strings.micError
                    SttError.BUSY -> strings.micError
                    SttError.UNKNOWN -> strings.micError
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun sendRecognizedText(text: String) {
        if (text.isNotBlank()) onSendText(text)
    }

    private fun sttErrorToast(error: CaptureError) {
        preview = ""
        Toast.makeText(
            context,
            when (error) {
                CaptureError.NO_RECOGNIZER -> strings.noRecognizer
                CaptureError.NO_PERMISSION -> strings.micDenied
                CaptureError.BUSY,
                CaptureError.INIT_FAILED -> strings.micError
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    // Старт toggle-STT после выданных разрешений.
    private fun startToggleStt() {
        if (!toggleOwner.active) return
        val handle = voice
        if (handle == null || !handle.isSttAvailable(stt.recognizerComponent)) {
            sttErrorToast(CaptureError.NO_RECOGNIZER)
            return
        }
        val error = toggleOwner.start(stt.language, sttListener(), stt.recognizerComponent, stt.bluetoothInput)
        if (error != null) sttErrorToast(error)
        else SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
    }

    /** Тап микрофона — toggle STT; при идущем стриме — barge-in (отмена хода). */
    fun toggle() {
        val handle = voice ?: return
        if (isStreaming) onBargeIn()
        stopSpeech?.invoke()
        when (val current = handle.captureState().value) {
            is CaptureState.Listening -> {
                if (current.mode == CaptureMode.STT) {
                    handle.stopStt()
                } else {
                    handle.cancelRaw()
                    SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
                }
            }
            else -> {
                if (!canCapture) return
                if (!handle.isSttAvailable(stt.recognizerComponent)) {
                    sttErrorToast(CaptureError.NO_RECOGNIZER)
                    return
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestMicPermission { granted ->
                        if (granted) toggleOwner.whenActive { startToggleStt() }
                        else Toast.makeText(context, strings.micDenied, Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (handle.bluetoothInputDecision(stt.bluetoothInput) == BtInputDecision.ASK_PERMISSION) {
                    handle.markBluetoothPermissionRequested()
                    requestBtPermission { toggleOwner.whenActive { startToggleStt() } }
                    return
                }
                startToggleStt()
            }
        }
    }

    // Удержание Mic при пустом поле: RAW (аудио модели) или STT по режиму.
    // MODEL_AUDIO без флага AUDIO_IN у модели — молча в STT (fail-safe).
    private fun useRawCapture(): Boolean = stt.engine == SttEngine.MODEL_AUDIO && audioIn

    /** Старт удержания: false — захват невозможен (тап продолжит toggle). */
    fun startHold(): Boolean {
        stopSpeech?.invoke()
        val handle = voice ?: return false
        if (handle.captureState().value is CaptureState.Listening) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission { granted ->
                // Отказ — с обратной связью (как в прежнем Chat); выдача молчит:
                // из холда палец к этому моменту уже поднят.
                if (!granted) Toast.makeText(context, strings.micDenied, Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (handle.bluetoothInputDecision(stt.bluetoothInput) == BtInputDecision.ASK_PERMISSION) {
            handle.markBluetoothPermissionRequested()
            requestBtPermission { }
            return false
        }
        val hold = VoiceHoldSession(
            if (useRawCapture()) CaptureMode.RAW else CaptureMode.STT,
        )
        if (hold.mode == CaptureMode.RAW) {
            val error = handle.startRaw(bluetoothInput = stt.bluetoothInput)
            if (error != null) {
                sttErrorToast(error)
                return false
            }
            SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
        } else {
            if (!handle.isSttAvailable(stt.recognizerComponent)) {
                sttErrorToast(CaptureError.NO_RECOGNIZER)
                return false
            }
            val error = handle.startStt(
                stt.language,
                sttListener(hold),
                stt.recognizerComponent,
                stt.bluetoothInput,
            )
            if (error != null) {
                sttErrorToast(error)
                return false
            }
            SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
        }
        holdSession = hold
        return true
    }

    /** Слишком короткое удержание или отмена: захват снимается, текст сбрасывается. */
    fun cancelHold(tooShort: Boolean) {
        val handle = voice ?: return
        val hold = holdSession ?: return
        holdSession = null
        if (hold.cancel()) {
            val wasListening = handle.captureState().value is CaptureState.Listening
            when (hold.mode) {
                CaptureMode.STT -> handle.cancelStt()
                CaptureMode.RAW -> handle.cancelRaw()
            }
            if (wasListening) SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
        }
        preview = ""
        if (tooShort) Toast.makeText(context, strings.voiceShort, Toast.LENGTH_SHORT).show()
    }

    /** Отпускание холда: STT — финал придёт колбэком, RAW — забираем WAV. */
    fun releaseHold() {
        val handle = voice ?: return
        val hold = holdSession ?: return
        holdSession = null
        when (hold.mode) {
            CaptureMode.STT -> {
                val finalText = hold.release()
                if (finalText != null) sendRecognizedText(finalText)
                else if (hold.captureActive) handle.stopStt()
            }
            CaptureMode.RAW -> {
                val wasListening = handle.captureState().value is CaptureState.Listening
                val wav = handle.stopRaw()
                if (wasListening) SoundHandle.get()?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
                if (wav == null) {
                    Toast.makeText(context, strings.voiceShort, Toast.LENGTH_SHORT).show()
                } else {
                    val base64 = android.util.Base64.encodeToString(wav.wav, android.util.Base64.NO_WRAP)
                    onSendAudio(AudioPart(base64, durationSec = wav.durationSec))
                }
            }
        }
    }
}

/**
 * Фабрика [VoiceInputController]: системные разрешения, жизненный цикл
 * toggle-STT (пауза на сворачивание, отложенный старт после системного
 * диалога), состояние захвата и фолбэк маршрута с гарнитуры.
 */
@Composable
fun rememberVoiceInputController(
    stt: SttConfig,
    audioIn: Boolean,
    strings: VoiceInputStrings,
    soundEnabled: Boolean,
    /** Новый захват разрешён (нет стрима/загрузки истории). */
    canCapture: Boolean,
    /** Идёт стрим: голосовой ввод прерывает его (barge-in). */
    isStreaming: Boolean,
    onBargeIn: () -> Unit,
    stopSpeech: (() -> Unit)?,
    onSendText: (String) -> Unit,
    onSendAudio: (AudioPart) -> Unit,
    /** Хук навигации для снятия захвата при уходе с вкладки (Chat). */
    registerDestinationExit: (((() -> Unit)) -> (() -> Unit))? = null,
): VoiceInputController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val voice = remember { VoiceHandle.get() }
    val micResult = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micResult.value?.invoke(granted) }
    val btResult = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> btResult.value?.invoke(granted) }
    val controller = remember {
        VoiceInputController(
            context = context,
            voice = voice,
            requestMicPermission = { onResult ->
                micResult.value = onResult
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            requestBtPermission = { onResult ->
                btResult.value = onResult
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
        )
    }
    // Входы обновляются при каждой рекомпозиции — лямбды не застревают.
    val currentOnBargeIn by rememberUpdatedState(onBargeIn)
    val currentStopSpeech by rememberUpdatedState(stopSpeech)
    val currentOnSendText by rememberUpdatedState(onSendText)
    val currentOnSendAudio by rememberUpdatedState(onSendAudio)
    androidx.compose.runtime.SideEffect {
        controller.strings = strings
        controller.stt = stt
        controller.audioIn = audioIn
        controller.soundEnabled = soundEnabled
        controller.canCapture = canCapture
        controller.isStreaming = isStreaming
        controller.onBargeIn = { currentOnBargeIn() }
        controller.stopSpeech = currentStopSpeech?.let { { it() } }
        controller.onSendText = { currentOnSendText(it) }
        controller.onSendAudio = { currentOnSendAudio(it) }
    }
    // Состояние захвата — из процессного VoiceHandle.
    LaunchedEffect(voice) {
        voice?.captureState()?.collect { controller.capturing = it is CaptureState.Listening }
    }
    // Жизненный цикл toggle-STT: пауза на сворачивание, отложенный старт.
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.ownerResume()
                Lifecycle.Event.ON_PAUSE -> controller.ownerPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.ownerDispose()
        }
    }
    DisposableEffect(controller, registerDestinationExit) {
        val unregister = registerDestinationExit?.invoke { controller.leaveDestination() }
        onDispose { unregister?.invoke() }
    }
    // Фолбэк с гарнитуры mid-capture: route → микрофон при активном захвате.
    val voiceRouteFlow = remember {
        voice?.routeState() ?: kotlinx.coroutines.flow.MutableStateFlow(VoiceInputRoute.MICROPHONE)
    }
    val voiceRoute by voiceRouteFlow.collectAsState()
    var prevVoiceRoute by remember { mutableStateOf(VoiceInputRoute.MICROPHONE) }
    LaunchedEffect(voiceRoute) {
        if (prevVoiceRoute == VoiceInputRoute.BLUETOOTH && voiceRoute == VoiceInputRoute.MICROPHONE &&
            voice?.captureState()?.value is CaptureState.Listening
        ) {
            Toast.makeText(context, strings.btFallback, Toast.LENGTH_LONG).show()
        }
        prevVoiceRoute = voiceRoute
    }
    return controller
}
