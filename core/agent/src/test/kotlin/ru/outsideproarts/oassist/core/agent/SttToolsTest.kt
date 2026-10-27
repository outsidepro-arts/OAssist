package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ToolAccess
import ru.outsideproarts.oassist.core.model.ToolCategory
import ru.outsideproarts.oassist.core.model.ToolGroups
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.speech.CaptureError
import ru.outsideproarts.oassist.core.speech.SttError
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController
import ru.outsideproarts.oassist.core.speech.VoiceCaptureResult

class SttToolsTest {
    private class FakeCapture(
        val startReply: VoiceCaptureResult = VoiceCaptureResult.Transcript("мама"),
        val stopReply: VoiceCaptureResult = VoiceCaptureResult.NotRecording,
    ) : VoiceCaptureController {
        var starts = 0
        var stops = 0
        var lastLanguage = ""
        var lastTimeout = 0
        override suspend fun startCapture(language: String, timeoutSec: Int): VoiceCaptureResult {
            starts++
            lastLanguage = language
            lastTimeout = timeoutSec
            return startReply
        }
        override suspend fun stopCapture(): VoiceCaptureResult {
            stops++
            return stopReply
        }
    }

    @Test
    fun startReturnsTranscriptAndStopDoesNotReplayIt() = runTest {
        val capture = FakeCapture()
        val started = VoiceListenStartTool(capture).execute(buildJsonObject {
            put("timeout_sec", 30)
            put("language", "en-US")
        })
        assertTrue(started.success)
        assertEquals("мама", started.output)
        assertEquals("en-US", capture.lastLanguage)
        assertEquals(30, capture.lastTimeout)
        val stopped = VoiceListenStopTool(capture).execute(buildJsonObject { })
        assertTrue(stopped.success)
        assertEquals("Нет активного прослушивания для этого запроса; другие записи не остановлены.", stopped.output)
        assertEquals(1, capture.starts)
        assertEquals(1, capture.stops)
    }

    @Test
    fun idleStopReportsOnlyItsOwnScope() = runTest {
        val stopped = VoiceListenStopTool(FakeCapture()).execute(buildJsonObject { })
        assertTrue(stopped.success)
        assertEquals("Нет активного прослушивания для этого запроса; другие записи не остановлены.", stopped.output)
        assertFalse(stopped.output.contains("ничего не записывается"))
    }

    @Test
    fun startDefaultsToRussianAndSixtySeconds() = runTest {
        val capture = FakeCapture()
        VoiceListenStartTool(capture).execute(buildJsonObject { })
        assertEquals("ru-RU", capture.lastLanguage)
        assertEquals(60, capture.lastTimeout)
    }

    @Test
    fun malformedTimeoutDoesNotStartCapture() = runTest {
        val capture = FakeCapture()
        val registry = ToolRegistry(listOf(VoiceListenStartTool(capture)))
        val invalid = listOf(buildJsonObject { }, buildJsonArray { })
        invalid.forEach { timeout ->
            val result = registry.execute("voice_listen_start", buildJsonObject { put("timeout_sec", timeout) })
            assertFalse(result.success)
        }
        assertEquals(0, capture.starts)
    }

    @Test
    fun unsuccessfulOutcomesAreNotReportedAsSuccessfulToolCalls() = runTest {
        val outcomes = listOf(
            VoiceCaptureResult.NoSpeech,
            VoiceCaptureResult.Timeout(5),
            VoiceCaptureResult.StartFailed(CaptureError.BUSY),
            VoiceCaptureResult.StartFailed(CaptureError.NO_PERMISSION),
            VoiceCaptureResult.RecognitionFailed(SttError.NETWORK),
            VoiceCaptureResult.Cancelled,
            VoiceCaptureResult.Transcript("  "),
        )
        outcomes.forEach { result ->
            val capture = FakeCapture(startReply = result, stopReply = result)
            assertFalse("start: $result", VoiceListenStartTool(capture).execute(buildJsonObject { }).success)
            assertFalse("stop: $result", VoiceListenStopTool(capture).execute(buildJsonObject { }).success)
        }
    }

    @Test
    fun groupsRemainMicrophoneReadAndBothToolsNeedPermission() {
        val capture = FakeCapture()
        val group = ToolGroups.key(ToolCategory.MICROPHONE, ToolAccess.READ)
        assertEquals(group, VoiceListenStartTool(capture).groupKey)
        assertEquals(group, VoiceListenStopTool(capture).groupKey)
        assertTrue(VoiceListenStartTool(capture).requiredPermissions.isNotEmpty())
        assertTrue(VoiceListenStopTool(capture).requiredPermissions.isNotEmpty())
    }

    @Test
    fun catalogContainsBothNames() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue(names.contains("voice_listen_start"))
        assertTrue(names.contains("voice_listen_stop"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun agentBatchWaitsForStartAndPreservesTranscriptBeforeStop() = runTest {
        val heard = CompletableDeferred<VoiceCaptureResult>()
        var stops = 0
        val capture = object : VoiceCaptureController {
            override suspend fun startCapture(language: String, timeoutSec: Int) = heard.await()
            override suspend fun stopCapture(): VoiceCaptureResult {
                stops++
                return VoiceCaptureResult.NotRecording
            }
        }
        val requests = mutableListOf<List<ChatMessage>>()
        var round = 0
        val client = object : ModelClient {
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
                reasoningEnabled: Boolean,
            ): Flow<ChatChunk> {
                requests += messages
                return if (round++ == 0) {
                    flowOf(
                        ChatChunk.ToolCall("start", "voice_listen_start", "{}"),
                        ChatChunk.ToolCall("stop", "voice_listen_stop", "{}"),
                        ChatChunk.Finish("tool_calls"),
                    )
                } else flowOf(ChatChunk.Content("понял"), ChatChunk.Finish("stop"))
            }
        }
        val policy = InMemoryApprovalPolicy().apply {
            setRemembered(ToolGroups.key(ToolCategory.MICROPHONE, ToolAccess.READ), ApprovalState.ALLOW)
        }
        val agent = AgentLoop(
            modelClient = client,
            toolRegistry = ToolRegistry(listOf(VoiceListenStartTool(capture), VoiceListenStopTool(capture))),
            approvalPolicy = policy,
        )
        val run = async { agent.stream("model", null, emptyList(), "слушай").toList() }
        runCurrent()
        assertEquals(0, stops)
        assertFalse(run.isCompleted)
        heard.complete(VoiceCaptureResult.Transcript("голосовой ответ"))
        runCurrent()
        val events = run.await().filterIsInstance<ChatChunk.ToolResult>()
        assertEquals(2, events.size)
        assertEquals("голосовой ответ", events[0].output)
        assertEquals("Нет активного прослушивания для этого запроса; другие записи не остановлены.", events[1].output)
        assertEquals(1, stops)
        val replies = requests.last().filter { it.role == ChatRole.TOOL }
        assertEquals(2, replies.size)
        val first = Json.parseToJsonElement(replies[0].content).jsonObject
        assertEquals("success", first["status"]!!.jsonPrimitive.content)
        assertEquals("голосовой ответ", first["data"]!!.jsonPrimitive.content)
    }
}
