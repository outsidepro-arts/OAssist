package ru.outsideproarts.oassist.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.view.View
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.data.chat.ChatMediaStore
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.speech.*
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService

@RunWith(AndroidJUnit4::class)
class AssistantOverlayModeTest {
    @Test fun modeRemainsFixedUntilServiceIsStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("Use tools/run-system-permission-tests.ps1", "true",
            InstrumentationRegistry.getArguments().getString("overlayModeFixture"))
        assertTrue("Fixture needs overlay access", Settings.canDrawOverlays(context))
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue("Fixture needs notifications enabled", notifications.areNotificationsEnabled())
        val oldFactory = AgentFactory.get()
        val oldVoice = requireNotNull(VoiceHandle.get())
        val oldSpeech = requireNotNull(SpeechHandle.get())
        val oldNetwork = requireNotNull(NetworkHandle.get())
        val oldRepository = requireNotNull(ChatHandle.get())
        val database = Room.inMemoryDatabaseBuilder(context, OAssistDatabase::class.java).build()
        val media = File(context.cacheDir, "screen-reader-${UUID.randomUUID()}")
        val repository = ChatRepository(database, ChatMediaStore(media))
        val online = MutableStateFlow(true)
        val desiredOverlay = AtomicBoolean(false)
        val configLoads = AtomicInteger()
        val anchor = AtomicReference<View?>(null)
        val starts = AtomicInteger()
        val speechStops = AtomicInteger()
        val idleReceipts = AtomicInteger()
        val requests = AtomicInteger()
        val answer = "A long answer for the screen reader. ".repeat(30)
        val engine = SseEngine(MockEngineConfig().apply { requestHandlers.add {
            requests.incrementAndGet()
            respond("event: message_start\ndata: {\"type\":\"message_start\"}\n\n" +
                "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"$answer\"}}\n\n" +
                "event: content_block_stop\ndata: {\"index\":0}\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        } })
        val http = HttpClient(engine) { install(SSE); install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val profile = ProviderProfile("fixture", "fixture", ProviderPreset.CUSTOM, "https://api.anthropic.com/v1", protocol = ModelProtocol.ANTHROPIC_MESSAGES)
        val client = runBlocking { ModelClient.create(Diagnostics, profile, EmptySecrets, http) }
        val service = Intent(context, AssistantOverlayService::class.java)
        try {
            AssistSessionStore.clear()
            ChatHandle.register(repository)
            AgentFactory.register(object : AgentFactory by oldFactory {
                override suspend fun modeConfig(mode: AssistMode): ModeConfig {
                    configLoads.incrementAndGet()
                    return ModeConfig(mode, modelId = "fixture", soundEnabled = false,
                    tts = TtsConfig(channel = SpeechOutputChannel.SCREEN_READER, narrative = false),
                    assistant = AssistantExtras(autoSpeak = false, continueDialog = true, showOverlay = desiredOverlay.get()),
                    stt = SttConfig(bluetoothInput = false))
                }
                override suspend fun providerForMode(mode: AssistMode) = profile
                override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode) = AgentLoop(client, diagnostics = Diagnostics)
                override suspend fun systemPromptFor(mode: AssistMode, screenContext: String?, sharedContent: String?) = RuntimePrompt("system")
                override fun clearSessionApprovals(mode: AssistMode) = Unit
            })
            NetworkHandle.register(object : NetworkHandle { override val isOnline = online })
            SpeechHandle.register(object : SpeechHandle by oldSpeech {
                override fun setScreenReaderAnchor(view: View?) { anchor.set(view) }
                override fun speak(sentence: String) = Unit
                override fun stop() { speechStops.incrementAndGet() }
                override suspend fun awaitIdle() { idleReceipts.incrementAndGet() }
                override fun applyTts(config: TtsConfig) = Unit
                override fun setNarrativeEnabled(enabled: Boolean) = Unit
            })
            VoiceHandle.register(object : VoiceHandle by oldVoice {
                override fun isSttAvailable(recognizerComponent: String?) = true
                override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
                override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
                    starts.incrementAndGet(); return null
                }
                override fun cancelStt() = Unit
                override fun cancelStt(owner: SttListener) = true
                override fun stopStt() = Unit
            })
            context.startForegroundService(service)
            await { starts.get() == 1 }
            fun hasInteractiveNotification(): Boolean = notifications.activeNotifications.any { entry ->
                entry.notification.actions.orEmpty().any {
                    it.title.toString() == context.getString(ru.outsideproarts.oassist.feature.assistant.R.string.assistant_close)
                }
            }
            await { hasInteractiveNotification() }
            assertNull(anchor.get())

            // Repeated starts and notification commands must retain the first session's UI.
            desiredOverlay.set(true)
            repeat(2) {
                val previous = configLoads.get()
                context.startService(service)
                await { configLoads.get() > previous }
            }
            online.value = false
            val reply = Intent(service).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_REPLY)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AssistantOverlayService.EXTRA_REPLY).build()), reply,
                Bundle().apply { putCharSequence(AssistantOverlayService.EXTRA_REPLY, "offline question") })
            context.startService(reply)
            await { notifications.activeNotifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                    context.getString(ru.outsideproarts.oassist.core.ui.R.string.net_offline_retry)
            } }
            assertNull(anchor.get())
            assertTrue(hasInteractiveNotification())

            val close = notifications.activeNotifications.asSequence()
                .flatMap { it.notification.actions.orEmpty().asSequence() }
                .first { it.title.toString() == context.getString(ru.outsideproarts.oassist.feature.assistant.R.string.assistant_close) }
            close.actionIntent.send()
            await { AssistSessionStore.current() == null }
            context.startForegroundService(service)
            await { anchor.get() != null }
            await { !hasInteractiveNotification() }
            val overlayAnchor = anchor.get()

            desiredOverlay.set(false)
            repeat(2) {
                val previous = configLoads.get()
                context.startService(service)
                await { configLoads.get() > previous }
            }
            instrumentation.waitForIdleSync()
            assertSame(overlayAnchor, anchor.get())
            assertFalse(hasInteractiveNotification())

            context.stopService(service)
            await { AssistSessionStore.current() == null && anchor.get() == null }
            context.startForegroundService(service)
            await { AssistSessionStore.current() != null && hasInteractiveNotification() }
            assertNull(anchor.get())
        } finally {
            context.stopService(service)
            await { AssistSessionStore.current() == null }
            instrumentation.waitForIdleSync()
            AgentFactory.register(oldFactory); VoiceHandle.register(oldVoice); SpeechHandle.register(oldSpeech); NetworkHandle.register(oldNetwork); ChatHandle.register(oldRepository)
            AssistSessionStore.clear(); http.close(); database.close()
            check(media.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            media.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Service did not reach expected state" }; SystemClock.sleep(25) }
    }

    private class SseEngine(config: MockEngineConfig) : MockEngine(config) {
        override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = super.supportedCapabilities + SSECapability
        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val body = response.body as? ByteReadChannel ?: return response
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(data, response.statusCode, response.headers, body, data.body, response.callContext) ?: return response
            return HttpResponseData(response.statusCode, response.requestTime, response.headers, response.version, adapted, response.callContext)
        }
    }
    private object EmptySecrets : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) = Unit
        override suspend fun getPair(firstAlias: String?, secondAlias: String?) = null to null
        override suspend fun contains(alias: String) = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases() = emptyList<String>()
    }
}
