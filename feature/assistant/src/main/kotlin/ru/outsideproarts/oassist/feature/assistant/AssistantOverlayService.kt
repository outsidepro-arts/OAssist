package ru.outsideproarts.oassist.feature.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.agent.AgentFactory
import ru.outsideproarts.oassist.core.agent.AgentLoop
import ru.outsideproarts.oassist.core.agent.AgentSession
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.agent.AssistVoiceEffect
import ru.outsideproarts.oassist.core.agent.AssistVoiceFlow
import ru.outsideproarts.oassist.core.agent.AssistVoiceState
import ru.outsideproarts.oassist.core.agent.BlockedPermissionRequest
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.provider.ProviderError
import ru.outsideproarts.oassist.core.provider.collectSafe
import ru.outsideproarts.oassist.core.provider.isEarlyRetryable
import ru.outsideproarts.oassist.core.speech.AgentSound
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.SentenceSplitter
import ru.outsideproarts.oassist.core.speech.SoundHandle
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.SttListener
import ru.outsideproarts.oassist.core.speech.StreamSpeaker
import ru.outsideproarts.oassist.core.speech.VoiceHandle
import ru.outsideproarts.oassist.core.ui.components.ApprovalPanel
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

/**
 * Оверлей ассистента: нижняя панель поверх текущего приложения (ClawDroid-паттерн).
 * Владеет голосовым циклом [AssistVoiceFlow] и жизнью сессии [AssistSessionStore]:
 * закрытие окна уничтожает контекст и продолжает поставленные нами на паузу плееры.
 */
class AssistantOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            // Неожиданное падение хода вне collectSafe: честный хинт вместо тишины
            // (finally хода уже сбросил isStreaming/фокус).
            runCatching {
                hintText = runCatching {
                    ProviderError.from(e).userMessage(this)
                }.getOrNull() ?: getString(R.string.assistant_no_provider)
            }
        },
    )
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlayView: android.view.View? = null
    private val voiceFlow = AssistVoiceFlow()
    private val pendingSttStart = PendingSttStart(serviceScope)
    private var turnJob: Job? = null
    private var mediaRouter: MediaButtonRouter? = null

    private var answer by mutableStateOf("")
    private var workEvents by mutableStateOf<List<ru.outsideproarts.oassist.core.model.WorkEvent>>(emptyList())
    private var turnUsage by mutableStateOf<ru.outsideproarts.oassist.core.model.ProviderUsage?>(null)

/** Ходы текущей сессии для закреплённого диалога «Ассистент» (перезапись целиком). */
private val sessionTurns = mutableListOf<ru.outsideproarts.oassist.core.model.StoredTurn>()

/** Транскрипт текущего хода (assistant/tool пары) — для сохранённого диалога. */
private var turnTranscript: List<ChatMessage> = emptyList()
    private var sttPreview by mutableStateOf("")
    private var isStreaming by mutableStateOf(false)
    private var expanded by mutableStateOf(false)
    private var hintText by mutableStateOf<String?>(null)

    /** Пауза на подтверждение действия: диалог живёт в композиции оверлея (showOverlay-режим). */
    private var pendingApproval by mutableStateOf<Pair<ApprovalRequest, AgentLoop>?>(null)
    /**
     * Ход идёт в канале SCREEN_READER: окну нужен фокус, иначе accessibility-
     * события анонсов глотаются (прецедент M9 — диалог подтверждения).
     */
    private var srKeepFocusable = false
    /** Поле ввода в фокусе — окну нужен фокус, иначе IME не придёт. */
    private var inputFocused = false
    private var listening = false

    private var sttLanguage: String = "ru-RU"
    private var sttRecognizerComponent: String? = null
    private var sttBluetoothInput: Boolean = true
    private var continueDialog: Boolean = false
    private var showOverlay: Boolean = true
    private var soundEnabled: Boolean = true
    private var sessionActive = false
    private var pendingBye = false
    /** Ход, отложенный до появления сети (пре-чек или ранний обрыв без сети). */
    private var pendingRetryText: String? = null
    private var lastNotifAt = 0L

    /**
     * Запрос системных разрешений, чей диалог заблокирован системой (старт окна из фона):
     * в notification-режиме показываем действие «Выдать» в сессионном уведомлении.
     */
    private var pendingPermission by mutableStateOf<BlockedPermissionRequest?>(null)

    private val voice get() = VoiceHandle.get()
    private val speech get() = SpeechHandle.get()
    private val sounds get() = SoundHandle.get()

    private val overlayHideMutex = kotlinx.coroutines.sync.Mutex()

    /** Скрытие панели на время координатных жестов агента: тапы должны доставаться приложению. */
    private val overlayControlImpl = object : ru.outsideproarts.oassist.core.agent.OverlayControl {
        override suspend fun <T> withOverlayHidden(block: suspend () -> T): T {
            overlayHideMutex.lock()
            try {
                withContext(Dispatchers.Main) { setOverlayVisible(false) }
                return block()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    setOverlayVisible(true)
                }
                overlayHideMutex.unlock()
            }
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (visible) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.visibility = android.view.View.VISIBLE
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = android.view.View.INVISIBLE
        }
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        ensureChannel()
        ru.outsideproarts.oassist.core.agent.OverlayControl.register(overlayControlImpl)
        // Авто-повтор отложенного хода только по фронту offline→online.
        serviceScope.launch {
            val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
            var wasOnline = net?.isOnline?.value ?: true
            net?.isOnline?.collect { online ->
                if (online && !wasOnline) {
                    val retry = pendingRetryText
                    // Стэш снимаем только при фактическом потреблении.
                    if (retry != null && !isStreaming) {
                        pendingRetryText = null
                        hintText = getString(
                            ru.outsideproarts.oassist.core.ui.R.string.net_repeating,
                        )
                        startTurn(retry)
                    }
                }
                wasOnline = online
            }
        }
        // Фолбэк заблокированного системного диалога разрешений: действие в уведомлении.
        serviceScope.launch {
            PermissionRequestBridge.blocked.collect { request ->
                pendingPermission = request
                refreshSessionNotification(force = true)
            }
        }
        // Фолбэк с гарнитуры mid-capture: подсказка в панели/уведомлении.
        // Штатный стоп снимает маршрут уже после конца захвата — без подсказки.
        serviceScope.launch {
            var prev = ru.outsideproarts.oassist.core.speech.VoiceInputRoute.MICROPHONE
            voice?.routeState()?.collect { route ->
                if (prev == ru.outsideproarts.oassist.core.speech.VoiceInputRoute.BLUETOOTH &&
                    route == ru.outsideproarts.oassist.core.speech.VoiceInputRoute.MICROPHONE &&
                    listening
                ) {
                    hintText = getString(R.string.assistant_mic_bt_fallback)
                    refreshSessionNotification(force = true)
                }
                prev = route
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        kotlinx.coroutines.runBlocking { loadModeConfig() }
        startForegroundCompat()
        when (intent?.getStringExtra(EXTRA_CMD)) {
            CMD_MIC -> {
                execute(voiceFlow.onPlayPause())
                return START_NOT_STICKY
            }
            CMD_CLOSE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            CMD_REPLY -> {
                val text = android.app.RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(EXTRA_REPLY)?.toString()
                if (!text.isNullOrBlank()) execute(voiceFlow.onUserSubmit(text))
                return START_NOT_STICKY
            }
        }
        val session = AssistSessionStore.getOrCreate()
        intent?.getStringExtra(EXTRA_SCREEN_CONTEXT)?.let { session.screenContext = it }
        intent?.getStringExtra(EXTRA_SCREEN_IMAGE)?.let {
            session.screenImage = ru.outsideproarts.oassist.core.model.ImagePart(imageBase64 = it)
        }
        if (!sessionActive) {
            if (showOverlay && !Settings.canDrawOverlays(this)) {
                hintText = getString(R.string.assistant_no_overlay)
                refreshSessionNotification(force = true)
                return START_NOT_STICKY
            }
            mediaRouter = MediaButtonRouter(this) { execute(voiceFlow.onPlayPause()) }.also { it.start() }
            if (showOverlay) addOverlay()
            sessionActive = true
            // Появление сессии звучит целиком ДО старта STT: эффекты
            // (включая StartStt) исполняем после конца overlay_start.
            val boot = voiceFlow.onActivate()
            serviceScope.launch {
                if (soundEnabled) {
                    runCatching { sounds?.playOverlayStartAndAwait() }
                }
                execute(boot)
            }
        } else {
            execute(voiceFlow.onReactivate())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pendingSttStart.cancel()
        ru.outsideproarts.oassist.core.agent.OverlayControl.unregister(overlayControlImpl)
        execute(voiceFlow.onClose())
        sounds?.playIfEnabled(AgentSound.OVERLAY_STOP, soundEnabled)
        mediaRouter?.release()
        mediaRouter = null
        AssistSessionStore.clear()
        runCatching { AgentFactory.get().clearSessionApprovals(AssistMode.ASSISTANT) }
        // Сессия оверлея кончилась — итоги использования не переживают закрытие.
        runCatching { AgentFactory.get().resetUsageTotals() }
        turnJob?.cancel()
        speech?.stop()
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun loadModeConfig() {
        val config = runCatching {
            AgentFactory.get().modeConfig(AssistMode.ASSISTANT)
        }.getOrNull() ?: return
        sttLanguage = config.effectiveStt().language
        sttRecognizerComponent = config.effectiveStt().recognizerComponent
        sttBluetoothInput = config.effectiveStt().bluetoothInput
        continueDialog = config.assistant?.continueDialog ?: false
        // showOverlay фиксируется при старте сессии: решение «окно или уведомление» структурное,
        // переключение посреди сессии смешивало бы два UI (панель + живое уведомление).
        showOverlay = config.assistant?.showOverlay ?: true
        soundEnabled = config.effectiveSoundEnabled()
    }

    @SuppressLint("InlinedApi")
    private fun startForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Android 14+ валидирует типы и требует RECORD_AUDIO для типа microphone — без него specialUse.
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val type = if (micGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, activeNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, activeNotification())
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.assistant_fgs_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        if (manager.getNotificationChannel(SESSION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SESSION_CHANNEL_ID,
                    getString(R.string.assistant_session_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.assistant_title))
            .setContentText(getString(R.string.assistant_fgs_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun activeNotification(): Notification =
        if (showOverlay) buildNotification() else buildSessionNotification()

    private fun buildSessionNotification(): Notification {
        val title = when {
            listening -> getString(R.string.assistant_mic_state_on)
            isStreaming -> getString(R.string.assistant_pill_thinking)
            else -> getString(R.string.assistant_title)
        }
        val sttPreviewText = sttPreview.takeIf { it.isNotBlank() }
        val pendingPerm = pendingPermission
        val permissionLine = getString(R.string.assistant_permission_needed)
        val text = when {
            pendingPerm != null -> permissionLine
            sttPreviewText != null -> sttPreviewText
            else -> answer.ifBlank { hintText ?: getString(R.string.assistant_fgs_text) }
        }
        val micAction = Notification.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            getString(R.string.assistant_mic),
            commandIntent(CMD_MIC),
        ).build()
        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.assistant_reply_action),
            commandIntent(CMD_REPLY, mutable = true),
        ).addRemoteInput(
            RemoteInput.Builder(EXTRA_REPLY)
                .setLabel(getString(R.string.assistant_reply_hint))
                .build(),
        ).setAllowGeneratedReplies(true).build()
        val closeAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.assistant_close),
            commandIntent(CMD_CLOSE),
        ).build()
        val builder = Notification.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(micAction)
            .addAction(replyAction)
            .addAction(closeAction)
        if (pendingPerm != null) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_lock_idle_lock,
                    getString(R.string.assistant_permission_grant),
                    grantPermissionIntent(pendingPerm),
                ).build(),
            )
        }
        return builder.build()
    }

    /**
     * Тап по действию — легальный путь показать системный диалог из фона:
     * PendingIntent стартует `ConsentActivity` уже от имени пользователя.
     */
    private fun grantPermissionIntent(request: BlockedPermissionRequest): android.app.PendingIntent {
        val intent = ConsentActivity.permissionIntent(this, request.requestId, request.permissions)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return android.app.PendingIntent.getActivity(
            this,
            request.requestId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun commandIntent(cmd: String, mutable: Boolean = false): android.app.PendingIntent {
        val intent = Intent(this, javaClass).putExtra(EXTRA_CMD, cmd)
        val flags = if (mutable) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        }
        return android.app.PendingIntent.getService(this, cmd.hashCode(), intent, flags)
    }

    /** Обновление сессионного уведомления в режиме без оверлея; [force] — мимо троттла. */
    @SuppressLint("NotificationPermission")
    private fun refreshSessionNotification(force: Boolean = false) {
        if (showOverlay) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifAt < NOTIF_MIN_INTERVAL_MS) return
        lastNotifAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildSessionNotification())
        }
    }

    private fun execute(effects: List<AssistVoiceEffect>) {
        for (effect in effects) {
            when (effect) {
                AssistVoiceEffect.StartStt -> startStt()
                AssistVoiceEffect.CancelStt -> {
                    pendingSttStart.cancel()
                    if (listening) sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
                    listening = false
                    sttPreview = ""
                    voice?.cancelStt()
                }
                is AssistVoiceEffect.SendText -> {
                    sounds?.playIfEnabled(AgentSound.MSG_SEND, soundEnabled)
                    startTurn(effect.text)
                }
                AssistVoiceEffect.CancelTurn -> {
                    speech?.stop()
                    turnJob?.cancel()
                    turnJob = null
                    isStreaming = false
                }
            }
        }
    }

    private fun startStt() {
        pendingSttStart.start { isCurrent ->
            if (!isCurrent() || voiceFlow.state.value !is AssistVoiceState.Listening) return@start
            val handle = voice
            if (handle == null || !handle.isSttAvailable(sttRecognizerComponent)) {
                hintText = getString(R.string.assistant_no_recognizer)
                voiceFlow.onSttStartFailed()
                refreshSessionNotification(force = true)
                return@start
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                hintText = getString(R.string.assistant_mic_denied)
                voiceFlow.onSttStartFailed()
                refreshSessionNotification(force = true)
                return@start
            }
            // Сервис просит Bluetooth-разрешение через ConsentActivity; отказ не блокирует микрофон.
            if (handle.bluetoothInputDecision(sttBluetoothInput) ==
                ru.outsideproarts.oassist.core.speech.BtInputDecision.ASK_PERMISSION
            ) {
                handle.markBluetoothPermissionRequested()
                requestBluetoothPermission()
            }
            if (!isCurrent() || voiceFlow.state.value !is AssistVoiceState.Listening) return@start
            hintText = null
            // Тишина нашего TTS перед стартом: движок распознавания падает поверх звука.
            speech?.stop()
            val error = handle.startStt(sttLanguage, sttListener(), sttRecognizerComponent, sttBluetoothInput)
            if (!isCurrent() || voiceFlow.state.value !is AssistVoiceState.Listening) return@start
            if (error != null) {
                hintText = sttErrorText(error)
                voiceFlow.onSttStartFailed()
            } else {
                listening = true
                sounds?.playIfEnabled(AgentSound.VOICE_START, soundEnabled)
            }
            refreshSessionNotification(force = true)
        }
    }

    private fun sttListener(): SttListener = object : SttListener {
        override fun onPartial(text: String) {
            sttPreview = text
            voiceFlow.onSttPartial(text)
            refreshSessionNotification()
        }

        override fun onFinal(text: String) {
            sttPreview = ""
            listening = false
            sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
            refreshSessionNotification(force = true)
            execute(voiceFlow.onSttFinal(text))
        }

        override fun onSttError(error: SttError) {
            sttPreview = ""
            listening = false
            sounds?.playIfEnabled(AgentSound.VOICE_FINISH, soundEnabled)
            hintText = when (error) {
                SttError.NO_SPEECH -> getString(R.string.assistant_mic_empty)
                SttError.NETWORK,
                SttError.BUSY,
                SttError.UNKNOWN,
                -> getString(R.string.assistant_mic_error)
            }
            voiceFlow.onSttStartFailed()
            refreshSessionNotification(force = true)
        }
    }

    private fun sttErrorText(error: CaptureError): String = when (error) {
        CaptureError.NO_RECOGNIZER -> getString(R.string.assistant_no_recognizer)
        CaptureError.NO_PERMISSION -> getString(R.string.assistant_mic_denied)
        CaptureError.BUSY,
        CaptureError.INIT_FAILED,
        -> getString(R.string.assistant_mic_error)
    }

    /**
     * Запрос BLUETOOTH_CONNECT через translucent [ConsentActivity] + [PermissionRequestBridge] —
     * тот же механизм, что у тулов: работает из сервиса, при заблокированном старте окна
     * фолбэк-действие «Выдать» идёт в сессионном уведомлении (существующий collector blocked).
     * Результат не обрабатывается: отказ/таймаут — запись с микрофона.
     */
    private suspend fun requestBluetoothPermission() {
        val outcome = awaitBluetoothPermission { requestId, permissions ->
            val intent = ConsentActivity.permissionIntent(this, requestId, permissions)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }.isSuccess
        } ?: return
        ru.outsideproarts.oassist.core.model.OAssistLog.info(
            "AssistVoice",
            "Bluetooth permission request outcome=$outcome",
        )
    }

    private fun startTurn(text: String) {
        val session = AssistSessionStore.getOrCreate()
        val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
        if (net != null && !net.isOnline.value) {
            // Оффлайн: состояние не трогаем, ход повторится фронтом сети.
            pendingRetryText = text
            hintText = getString(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
            refreshSessionNotification(force = true)
            return
        }
        pendingRetryText = null
        isStreaming = true
        pendingBye = false
        pendingApproval = null
        srKeepFocusable = false
        inputFocused = false
        refreshOverlayFocusable()
        answer = ""
        workEvents = emptyList()
        turnUsage = null
        turnTranscript = emptyList()
        hintText = null
        speech?.stop()
        turnJob?.cancel()
        turnJob = serviceScope.launch {
            try {
                val factory = AgentFactory.get()
                val config = runCatching { factory.modeConfig(AssistMode.ASSISTANT) }.getOrNull()
                val profile = factory.providerForMode(AssistMode.ASSISTANT)
                if (profile == null) {
                    hintText = getString(R.string.assistant_no_provider)
                    return@launch
                }
                val modelId = config?.modelId?.takeIf { it.isNotBlank() }
                if (modelId == null) {
                    hintText = getString(R.string.assistant_no_provider)
                    return@launch
                }
        continueDialog = config.assistant?.continueDialog ?: false
        soundEnabled = config.effectiveSoundEnabled()
                val autoSpeak = config.assistant?.autoSpeak ?: true
                val tts = config.effectiveTts()
                speech?.applyTts(tts)
                speech?.setNarrativeEnabled(tts.narrative)
                // SR-стриму нужен фокус окна (иначе анонсы глотаются).
                srKeepFocusable =
                    tts.channel == ru.outsideproarts.oassist.core.model.SpeechOutputChannel.SCREEN_READER
                refreshOverlayFocusable()

                val thoughtSpeaker = StreamSpeaker(
                    speak = { if (autoSpeak) speech?.speak(it) },
                    speakToolCalls = tts.speakToolCalls,
                )
                val answerSpeaker = AssistantAnswerSpeech(
                    speak = { if (autoSpeak) speech?.speak(it) },
                )
                val screenImage = session.screenImage
                session.screenImage = null
                val agent = factory.agentFor(profile, AssistMode.ASSISTANT)
                val timeline = ru.outsideproarts.oassist.core.agent.WorkTimelineBuilder()
                val prompt = factory.systemPromptFor(
                    AssistMode.ASSISTANT,
                    screenContext = session.screenContext,
                )
                val agentSession = AgentSession(
                    chunks = agent.stream(
                        modelId = modelId,
                        systemPrompt = prompt.instructions,
                        promptContext = prompt.context,
                        history = session.history,
                        userMessage = text,
                        images = screenImage?.let { listOf(it) },
                        onTurnMessages = { turn ->
                            session.appendTurn(ChatMessage(ChatRole.USER, text), turn)
                            turnTranscript = turn
                        },
                    ),
                    agent = agent,
                )
                // Флаг обрыва стрима: `error` внутри своей лямбды-инициализатора
                // ещё не в scope (там `error` — stdlib-функция), читаем флаг после сбора.
                var sawAbrupt = false
                val error = agentSession.chunks.collectSafe { chunk ->
                    when (chunk) {
                        is ChatChunk.Reasoning -> {
                            // Мысли модели: live в полотно, без озвучки (дельты частые).
                            if (timeline.onChunk(chunk)) workEvents = timeline.work
                        }
                        is ChatChunk.Content -> {
                            if (chunk.isToolComment) {
                                timeline.onChunk(chunk)
                                workEvents = timeline.work
                                thoughtSpeaker.onText(timeline.commentText, true)
                                sounds?.playIfEnabled(AgentSound.THINKING, soundEnabled)
                            } else {
                                timeline.onChunk(chunk)
                                answer = timeline.answerText
                                answerSpeaker.onText(timeline.answerText, chunk.isTerminalFarewell)
                            }
                            refreshSessionNotification()
                        }
                        is ChatChunk.ToolCall -> {
                            timeline.onChunk(chunk)
                            workEvents = timeline.work
                            sounds?.playToolCallIfEnabled(chunk.name, soundEnabled)
                            refreshSessionNotification()
                        }
                        is ChatChunk.ToolResult -> {
                            timeline.onChunk(chunk)
                            workEvents = timeline.work
                        }
                        is ChatChunk.ApprovalNeeded -> {
                            sounds?.playIfEnabled(AgentSound.PERMISSION_REQUEST, soundEnabled)
                            if (chunk.request.screenLocked) {
                                speech?.speak(getString(ru.outsideproarts.oassist.core.ui.R.string.approval_locked_hint))
                            }
                            if (showOverlay) {
                                pendingApproval = chunk.request to agent
                                // Окно нефокусируемо (панель поверх приложения) — пока запрос
                                // висит, фокус нужен, чтобы accessibility-фокус сел в диалог
                                // и доходили анонсы (SR-стрим тоже держит фокус).
                                refreshOverlayFocusable()
                            } else {
                                // Notification-режим: диалог отдельным окном (ConsentActivity),
                                // резолвер держим на время паузы цикла.
                                ru.outsideproarts.oassist.core.agent.ApprovalUiBridge.register(
                                    chunk.request.callId,
                                ) { decision -> agent.resolveApproval(chunk.request.callId, decision) }
                                runCatching {
                                    startActivity(
                                        ConsentActivity.approvalIntent(this@AssistantOverlayService, chunk.request)
                                            .putExtra(ConsentActivity.EXTRA_SOUND_ENABLED, soundEnabled)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    ru.outsideproarts.oassist.core.agent.ApprovalUiBridge.clear(chunk.request.callId)
                                    agent.resolveApproval(chunk.request.callId, ApprovalDecision.DENY)
                                }
                            }
                        }
                        is ChatChunk.Finish -> {
                            timeline.onChunk(chunk)
                            thoughtSpeaker.onFinish(timeline.commentText)
                            answerSpeaker.onFinish(timeline.answerText, chunk.closeSession)
                            pendingBye = chunk.closeSession
                            turnUsage = timeline.usage
                            answer = timeline.answerText
                            workEvents = timeline.work
                            sounds?.playIfEnabled(AgentSound.ANSWER_READY, soundEnabled)
                            // Поток закрылся без терминатора: пометку покажем после
                            // сбора (`error` внутри своей лямбды-инициализатора
                            // ещё не в scope — поэтому отдельный флаг).
                            if (chunk.abrupt) sawAbrupt = true
                            refreshSessionNotification(force = true)
                        }
                    }
                }
                if (error != null) {
                    val net = ru.outsideproarts.oassist.core.net.NetworkHandle.get()
                    if (net != null && !net.isOnline.value && error.isEarlyRetryable() &&
                        timeline.isEmpty
                    ) {
                        // Упали до первого чанка без сети: ждём фронт online,
                        // тост ошибки не показываем.
                        pendingRetryText = text
                        hintText = getString(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
                    } else {
                        sounds?.playIfEnabled(AgentSound.ERROR, soundEnabled)
                        hintText = error.userMessage(this@AssistantOverlayService)
                    }
                } else if (sawAbrupt) {
                    // Поток закрылся без терминатора: ответ мог быть усечён.
                    android.widget.Toast.makeText(
                        this@AssistantOverlayService,
                        ru.outsideproarts.oassist.core.provider.R.string.provider_stream_truncated,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                if (pendingBye) {
                    val accepted = answerSpeaker.finishTerminal(
                        autoSpeak = autoSpeak,
                        speakWhenReady = { speech?.speakWhenReady(it) ?: false },
                        awaitIdle = { speech?.awaitIdle() },
                        stop = { speech?.stop() },
                    )
                    if (!accepted && autoSpeak && (
                            tts.channel == ru.outsideproarts.oassist.core.model.SpeechOutputChannel.SYSTEM_TTS ||
                                tts.channel == ru.outsideproarts.oassist.core.model.SpeechOutputChannel.SCREEN_READER
                            )
                    ) {
                        android.widget.Toast.makeText(
                            this@AssistantOverlayService,
                            R.string.assistant_farewell_speech_unavailable,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    refreshSessionNotification(force = true)
                    stopSelf()
                    return@launch
                }
                speech?.speaking?.first { !it }
                refreshSessionNotification(force = true)
                execute(voiceFlow.onTurnFinished(continueDialog))
            } finally {
                persistAssistantTurn(text)
                isStreaming = false
                srKeepFocusable = false
                refreshOverlayFocusable()
            }
        }
    }

    /**
     * Сохранить ход в закреплённый диалог «Ассистент»: вся сессия пишется
     * перезаписью (новая сессия — новый набор ходов). Пустой ход не пишется:
     * случайно открытый оверлей не затирает сохранённую историю.
     */
    private fun persistAssistantTurn(userText: String) {
        if (answer.isBlank() && workEvents.isEmpty()) return
        val stored = ru.outsideproarts.oassist.core.model.StoredTurn(
            userText = userText,
            work = workEvents,
            answer = answer,
            usage = turnUsage,
            turn = turnTranscript.ifEmpty {
                if (answer.isNotBlank()) listOf(ChatMessage(ChatRole.ASSISTANT, answer)) else emptyList()
            },
        )
        sessionTurns.add(stored)
        val repo = ru.outsideproarts.oassist.core.data.chat.ChatHandle.get() ?: return
        val snapshot = sessionTurns.toList()
        serviceScope.launch { repo.replaceAssistantTurns(snapshot) }
    }

    private fun onSpeakAnswer() {
        val text = answer
        if (text.isBlank()) return
        SentenceSplitter.splitSentences(text).forEach { speech?.speak(it) }
    }

    private fun addOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val wrapper = FrameLayout(this)
        wrapper.setViewTreeLifecycleOwner(this)
        wrapper.setViewTreeSavedStateRegistryOwner(this)

        ComposeView(this).apply {
            setContent {
                OAssistTheme {
                    val voiceState by voiceFlow.state.collectAsState()
                    androidx.compose.foundation.layout.Column {
                    pendingApproval?.let { (request, agent) ->
                        ApprovalPanel(
                            request = request,
                            onDecision = { decision ->
                                if (decision == ApprovalDecision.DENY ||
                                    decision == ApprovalDecision.ALWAYS_DENY
                                ) {
                                    sounds?.playIfEnabled(AgentSound.PERMISSION_DENIED, soundEnabled)
                                }
                                agent.resolveApproval(request.callId, decision)
                                pendingApproval = null
                                refreshOverlayFocusable()
                            },
                        )
                    }
                        AssistPill(
                            answer = answer,
                            work = workEvents,
                            usage = turnUsage,
                            usageTotals = runCatching { AgentFactory.get().usageTotals() }.getOrNull(),
                            statusHint = hintText,
                            listening = voiceState is AssistVoiceState.Listening && listening,
                            sttPreview = sttPreview,
                            isStreaming = isStreaming,
                            expanded = expanded,
                            announcer = speech?.announcer,
                            onExpandedChange = { expanded = it },
                            onSend = { execute(voiceFlow.onUserSubmit(it)) },
                            onMicToggle = { execute(voiceFlow.onPlayPause()) },
                            onClose = { stopSelf() },
                            onSpeakAnswer = ::onSpeakAnswer,
                            onInputFocusChanged = { focused ->
                                inputFocused = focused
                                refreshOverlayFocusable()
                            },
                        )
                    }
                }
            }
            wrapper.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        windowManager.addView(wrapper, params)
        overlayView = wrapper
        // Якорь анонсов скринридера — окно панели (аналог TeamTalk announcerObject).
        speech?.setScreenReaderAnchor(wrapper)
    }

    private fun removeOverlay() {
        speech?.setScreenReaderAnchor(null)
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
    }

    /**
     * Фокусируемость окна оверлея: нефокусируемое окно не пропускает
     * accessibility-события. Держим фокус, пока висит подтверждение или идёт
     * стрим в канале SCREEN_READER; иначе окно нефокусируемое (тапы мимо
     * панели идут в приложение, IME — только на фокусе поля).
     */
    private fun refreshOverlayFocusable() {
        if (!showOverlay) return
        setInputFocusable(
            inputFocused || pendingApproval != null || (isStreaming && srKeepFocusable),
        )
    }

    /** Поле ввода требует фокуса окна — иначе IME не придёт; снаружи окно остаётся нефокусируемым. */
    private fun setInputFocusable(focused: Boolean) {
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val wantNotFocusable = !focused
        val hasNotFocusable =
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        if (wantNotFocusable == hasNotFocusable) return
        lp.flags = if (wantNotFocusable) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    companion object {
        const val EXTRA_SCREEN_CONTEXT = "screen_context"
        const val EXTRA_SCREEN_IMAGE = "screen_image"
        private const val CHANNEL_ID = "oassist_overlay"
        private const val SESSION_CHANNEL_ID = "oassist_session"
        private const val NOTIFICATION_ID = 0x0A52
        private const val NOTIF_MIN_INTERVAL_MS = 500L
        const val EXTRA_CMD = "cmd"
        const val EXTRA_REPLY = "reply_text"
        const val CMD_MIC = "mic"
        const val CMD_CLOSE = "close"
        const val CMD_REPLY = "reply"
    }
}
