package ru.outsideproarts.oassist.vis

import android.Manifest
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.data.chat.ChatHandle
import ru.outsideproarts.oassist.core.data.chat.ChatMediaStore
import ru.outsideproarts.oassist.core.data.chat.ChatRepository
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ImageCodec
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.speech.*
import ru.outsideproarts.oassist.feature.assistant.AssistantOverlayService
import ru.outsideproarts.oassist.feature.assistant.AssistScreenHandoff

@RunWith(AndroidJUnit4::class)
class AssistTurnSnapshotTest {
    @Test
    fun newVisSnapshotCancelsSuspendedTurnAndNextModelInputUsesOnlyNewPair() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Use tools/run-assist-capture-tests.ps1", "true",
            InstrumentationRegistry.getArguments().getString("assistCaptureFixture"))
        val context = instrumentation.targetContext
        assertTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val previousFactory = AgentFactory.get()
        val previousVoice = requireNotNull(VoiceHandle.get())
        val previousSpeech = requireNotNull(SpeechHandle.get())
        val previousNetwork = requireNotNull(NetworkHandle.get())
        val previousRepository = requireNotNull(ChatHandle.get())
        val database = Room.inMemoryDatabaseBuilder(context, OAssistDatabase::class.java).build()
        val media = File(context.cacheDir, "vis-turn-${UUID.randomUUID()}")
        val repository = ChatRepository(database, ChatMediaStore(media))
        val enteredFactory = CountDownLatch(1)
        val cancelledFactory = CountDownLatch(1)
        val releaseFactory = CompletableDeferred<Unit>()
        val factoryCalls = AtomicInteger()
        val sttStarts = AtomicInteger()
        val requests = CopyOnWriteArrayList<List<ChatMessage>>()
        val service = Intent(context, AssistantOverlayService::class.java)
        val profile = ProviderProfile("vis-fixture", "VIS fixture", ProviderPreset.CUSTOM, "https://invalid.example")
        val client = object : ModelClient {
            override suspend fun fetchModels() = emptyList<ModelInfo>()
            override fun chatCompletionStream(modelId: String, messages: List<ChatMessage>, tools: List<ToolSchemaDto>, reasoningEnabled: Boolean) = flow {
                requests += messages
                emit(ChatChunk.Content("fixture answer"))
                emit(ChatChunk.Finish("stop"))
            }
        }
        fun image(color: Int): ImagePart {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ImageCodec.fromBitmap(bitmap)
            } finally { bitmap.recycle() }
        }
        fun publish(text: String, image: ImagePart) {
            val token = AtomicReference<String>()
            instrumentation.runOnMainSync {
                token.set(AssistScreenHandoff.publish(AssistSessionStore.beginScreenCapture(), text, image))
            }
            context.startForegroundService(Intent(service).putExtra(AssistScreenHandoff.EXTRA_TOKEN, requireNotNull(token.get())))
        }
        fun reply(text: String) {
            val command = Intent(service).putExtra(AssistantOverlayService.EXTRA_CMD, AssistantOverlayService.CMD_REPLY)
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AssistantOverlayService.EXTRA_REPLY).build()), command,
                Bundle().apply { putCharSequence(AssistantOverlayService.EXTRA_REPLY, text) })
            context.startService(command)
        }
        val previousDiagnostics = Diagnostics.swapForTest(null)
        try {
            AssistSessionStore.clear()
            ChatHandle.register(repository)
            NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(true) })
            SpeechHandle.register(QuietAssistSpeechHandle(previousSpeech))
            VoiceHandle.register(object : VoiceHandle by previousVoice {
                private var sttOwner: SttListener? = null
                override fun cancelStt(owner: SttListener): Boolean {
                    if (sttOwner !== owner) return false
                    sttOwner = null
                    cancelStt()
                    return true
                }
                override fun isSttAvailable(recognizerComponent: String?) = true
                override fun bluetoothInputDecision(enabled: Boolean) = BtInputDecision.USE_MIC
                override fun startStt(language: String, listener: SttListener, recognizerComponent: String?, bluetoothInput: Boolean): CaptureError? {
                    sttOwner = listener
                    sttStarts.incrementAndGet()
                    return null
                }
                override fun cancelStt() = Unit
                override fun stopStt() = Unit
            })
            AgentFactory.register(object : AgentFactory by previousFactory {
                override suspend fun modeConfig(mode: AssistMode) = ModeConfig(
                    mode = mode, modelId = "fixture", soundEnabled = false, stt = SttConfig(bluetoothInput = false),
                    assistant = AssistantExtras(showOverlay = false, attachScreenshot = true, autoSpeak = false, continueDialog = false),
                )
                override suspend fun providerForMode(mode: AssistMode) = profile
                override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode): AgentLoop {
                    if (factoryCalls.incrementAndGet() == 1) {
                        enteredFactory.countDown()
                        try { releaseFactory.await() } catch (cancelled: CancellationException) {
                            cancelledFactory.countDown()
                            throw cancelled
                        }
                    }
                    return AgentLoop(client, diagnostics = Diagnostics)
                }
                override suspend fun systemPromptFor(mode: AssistMode, screenContext: String?, sharedContent: String?) =
                    RuntimePrompt("fixture", listOf(ContextData(ContextSource.SCREEN, requireNotNull(screenContext))))
            })
            val imageA = image(Color.RED)
            val imageB = image(Color.BLUE)
            publish("screen A", imageA)
            await { sttStarts.get() == 1 && AssistSessionStore.current()?.screenContext == "screen A" }
            val owner = requireNotNull(AssistSessionStore.current())
            reply("question A")
            assertTrue("Turn did not reach controlled factory suspension", enteredFactory.await(10, TimeUnit.SECONDS))
            assertNull("Image A must already belong to the suspended turn", owner.screenImage)
            publish("screen B", imageB)
            assertTrue("New VIS did not cancel the old turn", cancelledFactory.await(10, TimeUnit.SECONDS))
            await { sttStarts.get() == 2 && owner.screenContext == "screen B" }
            assertSame(owner, AssistSessionStore.current())
            releaseFactory.complete(Unit)
            reply("question B")
            await { requests.isNotEmpty() && owner.history.any { it.role == ChatRole.ASSISTANT } }
            assertEquals("Cancelled A must never reach the model", 1, requests.size)
            val user = requests.single().last { it.role == ChatRole.USER }
            assertEquals("question B", Json.parseToJsonElement(user.content).jsonObject["request"]?.jsonPrimitive?.content)
            assertEquals(listOf(imageB), user.images)
            assertTrue(user.contextData.contains(ContextData(ContextSource.SCREEN, "screen B")))
            assertFalse(requests.single().any { it.content.contains("screen A") || it.images.contains(imageA) ||
                it.contextData.any { data -> data.text == "screen A" } })
            await { runBlocking { repository.loadTurns(ChatRepository.ASSISTANT_CHAT_ID).any { it.userText == "question B" } } }
            context.stopService(service)
            await { AssistSessionStore.current() == null }
        } finally {
            try {
                releaseFactory.complete(Unit)
                context.stopService(service)
                instrumentation.waitForIdleSync()
                AgentFactory.register(previousFactory)
                VoiceHandle.register(previousVoice)
                SpeechHandle.register(previousSpeech)
                NetworkHandle.register(previousNetwork)
                ChatHandle.register(previousRepository)
                AssistSessionStore.clear()
                database.close()
                check(media.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                media.deleteRecursively()
            } finally {
                Diagnostics.swapForTest(previousDiagnostics)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Service did not reach expected turn state" }
            SystemClock.sleep(25)
        }
    }
}
