package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.StoredTurn
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Экспорт транскрипта хода через onTurnMessages: assistant(tool_calls) + tool(tool_call_id)
 * + финальный assistant, без system/user — для накопления истории оверлея ассистента.
 */
class AgentLoopTurnMessagesTest {

    private class ScriptedClient(val rounds: ArrayDeque<List<ChatChunk>>) : ModelClient {
        val requests = mutableListOf<List<ChatMessage>>()

        override suspend fun fetchModels(): List<ModelInfo> = emptyList()
        override fun chatCompletionStream(
            modelId: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchemaDto>,
            reasoningEnabled: Boolean,
        ): Flow<ChatChunk> {
            requests += messages.toList()
            return flowOf(*(rounds.removeFirstOrNull() ?: listOf(ChatChunk.Finish("stop"))).toTypedArray())
        }
    }

    private class PingTool : Tool {
        override val groupKey = ru.outsideproarts.oassist.core.model.ToolGroups.SERVICE
        override val schema = ToolSchema(
            name = "ping",
            description = "тест",
            parameters = buildJsonObject { put("type", "object") },
        )

        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult(true, "pong")
    }

    @Test
    fun simpleAnswer_exportsOnlyAssistantMessage() = runTest {
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("ответ"), ChatChunk.Finish("stop")))),
        )
        val agent = AgentLoop(client)
        var turn: List<ChatMessage>? = null

        agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertEquals(1, exported.size)
        assertEquals(ChatRole.ASSISTANT, exported[0].role)
        assertEquals("ответ", exported[0].content)
    }

    @Test
    fun toolRound_exportsPairedToolMessages() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(PingTool())))
        var turn: List<ChatMessage>? = null

        agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertEquals(3, exported.size)
        assertEquals(listOf("c1"), exported[0].toolCalls?.map { it.id })
        assertEquals(ChatRole.TOOL, exported[1].role)
        assertEquals("c1", exported[1].toolCallId)
        assertEquals("готово", exported[2].content)
    }

    @Test
    fun export_excludesSystemAndHistory() = runTest {
        val history = listOf(
            ChatMessage(ChatRole.USER, "старый вопрос"),
            ChatMessage(ChatRole.ASSISTANT, "старый ответ"),
        )
        val client = ScriptedClient(
            ArrayDeque(listOf(listOf(ChatChunk.Content("новый ответ"), ChatChunk.Finish("stop")))),
        )
        val agent = AgentLoop(client)
        var turn: List<ChatMessage>? = null

        agent.stream("m", "система", history, "вопрос", onTurnMessages = { turn = it }).toList()

        val exported = requireNotNull(turn)
        assertTrue(exported.none { it.role == ChatRole.SYSTEM })
        assertTrue(exported.none { it.content.contains("старый") })
    }

    @Test
    fun toolLoop_stopsAtConfiguredLimit() = runTest {
        val tool = PingTool()
        val client = object : ModelClient {
            val seen = mutableListOf<List<ChatMessage>>()
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
                reasoningEnabled: Boolean,
            ): Flow<ChatChunk> {
                seen.add(messages)
                return flowOf(
                    ChatChunk.ToolCall(id = "c${seen.size}", name = "ping", argumentsJson = "{}"),
                    ChatChunk.Finish("tool_calls"),
                )
            }
        }
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), maxToolRounds = 2)
        var turn: List<ChatMessage>? = null

        val chunks = agent.stream("m", null, emptyList(), "вопрос", onTurnMessages = { turn = it }).toList()

        assertEquals(2, client.seen.size)
        assertTrue(chunks.any { it is ChatChunk.Finish && it.reason == "max_tool_rounds" })
        val exported = requireNotNull(turn)
        assertEquals(4, exported.size)
    }

    @Test
    fun toolLoop_unlimitedCompletesBeyondOldCap() = runTest {
        val tool = PingTool()
        val client = object : ModelClient {
            var round = 0
            override suspend fun fetchModels(): List<ModelInfo> = emptyList()
            override fun chatCompletionStream(
                modelId: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchemaDto>,
                reasoningEnabled: Boolean,
            ): Flow<ChatChunk> {
                round++
                return if (round <= 8) {
                    flowOf(
                        ChatChunk.ToolCall(id = "c$round", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    )
                } else {
                    flowOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop"))
                }
            }
        }
        val agent = AgentLoop(client, ToolRegistry(listOf(tool)), maxToolRounds = 0)

        val chunks = agent.stream("m", null, emptyList(), "вопрос").toList()

        assertEquals(9, client.round)
        assertTrue(chunks.none { it is ChatChunk.Finish && it.reason == "max_tool_rounds" })
        assertTrue(chunks.any { it is ChatChunk.Content && it.delta == "готово" })
    }

    private suspend fun recordAtFinish(
        agent: AgentLoop,
        userText: String,
        history: List<ChatMessage> = emptyList(),
    ): StoredTurn {
        val answer = StringBuilder()
        var transcript: List<ChatMessage>? = null
        val stored = mutableListOf<StoredTurn>()
        agent.stream("m", null, history, userText, onTurnMessages = { transcript = it }).collect { chunk ->
            when (chunk) {
                is ChatChunk.Content -> if (!chunk.isToolComment) answer.append(chunk.delta)
                is ChatChunk.Finish -> stored += StoredTurn(
                    userText = userText,
                    answer = answer.toString(),
                    turn = requireNotNull(transcript) { "Transcript must be available at Finish" },
                )
                else -> Unit
            }
        }
        return stored.single()
    }

    @Test
    fun nextRequests_includePreviousAnswersRecordedAtFinish() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(ChatChunk.Content("45"), ChatChunk.Finish("stop")),
                    listOf(ChatChunk.Content("90"), ChatChunk.Finish("stop")),
                    listOf(ChatChunk.Content("90"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client)
        val first = recordAtFinish(agent, "Придумай случайное число от 1 до 100.")
        val second = recordAtFinish(agent, "Теперь умножь это число на 2", first.modelMessages())
        recordAtFinish(
            agent,
            "Процитируй дословно своё прошлое сообщение",
            first.modelMessages() + second.modelMessages(),
        )

        assertEquals("45", first.answer)
        assertEquals(listOf(ChatMessage(ChatRole.ASSISTANT, "45")), first.turn)
        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            client.requests[1].map { it.role },
        )
        assertEquals("45", client.requests[1][2].content)
        assertEquals(
            listOf("45", "90"),
            client.requests[2].filter { it.role == ChatRole.ASSISTANT }.map { it.content },
        )
    }

    @Test
    fun nextRequest_keepsToolPairsRecordedAtFinish() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                    listOf(ChatChunk.Content("готово"), ChatChunk.Finish("stop")),
                    listOf(ChatChunk.Content("помню"), ChatChunk.Finish("stop")),
                ),
            ),
        )
        val agent = AgentLoop(client, ToolRegistry(listOf(PingTool())))
        val first = recordAtFinish(agent, "Проверь")
        recordAtFinish(agent, "Что ты проверил?", first.modelMessages())

        assertEquals(3, first.turn.size)
        val replay = client.requests.last()
        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT, ChatRole.USER),
            replay.map { it.role },
        )
        assertEquals("ping", replay[2].toolCalls!!.single().name)
        assertEquals(replay[2].toolCalls!!.single().id, replay[3].toolCallId)
        assertTrue(replay[3].content.contains("pong"))
        assertEquals("готово", replay[4].content)
    }

    @Test
    fun terminalBye_exportsTranscriptBeforeFinish() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "bye1", name = "bye", argumentsJson = """{"farewell":"До встречи!"}"""),
                        ChatChunk.Finish("tool_calls"),
                    ),
                ),
            ),
        )
        val stored = recordAtFinish(AgentLoop(client, ToolRegistry(listOf(ByeTool()))), "Пока")

        assertEquals("До встречи!", stored.answer)
        assertEquals(listOf(ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT), stored.turn.map { it.role })
        assertEquals("До встречи!", stored.turn.last().content)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun toolLimit_exportsTranscriptBeforeFinish() = runTest {
        val client = ScriptedClient(
            ArrayDeque(
                listOf(
                    listOf(
                        ChatChunk.ToolCall(id = "c1", name = "ping", argumentsJson = "{}"),
                        ChatChunk.Finish("tool_calls"),
                    ),
                ),
            ),
        )
        val stored = recordAtFinish(AgentLoop(client, ToolRegistry(listOf(PingTool())), maxToolRounds = 1), "Проверь")

        assertEquals(listOf(ChatRole.ASSISTANT, ChatRole.TOOL), stored.turn.map { it.role })
        assertEquals("c1", stored.turn[0].toolCalls!!.single().id)
        assertEquals("c1", stored.turn[1].toolCallId)
        assertEquals("pong", stored.turn[1].content)
    }

    @Test
    fun emptyAnswer_exportsEmptyTranscriptBeforeFinish() = runTest {
        val client = ScriptedClient(ArrayDeque(listOf(listOf(ChatChunk.Finish("stop")))))
        val stored = recordAtFinish(AgentLoop(client), "вопрос")
        assertTrue(stored.turn.isEmpty())
        assertEquals(listOf(ChatMessage(ChatRole.USER, "вопрос")), stored.modelMessages())
    }

    @Test
    fun interruptedTurn_doesNotPublishCompletedTranscriptOrFinish() = runTest {
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("stream failed"))) {
            var callbacks = 0
            val chunks = mutableListOf<ChatChunk>()
            val client = object : ModelClient {
                override suspend fun fetchModels(): List<ModelInfo> = emptyList()
                override fun chatCompletionStream(
                    modelId: String,
                    messages: List<ChatMessage>,
                    tools: List<ToolSchemaDto>,
                    reasoningEnabled: Boolean,
                ): Flow<ChatChunk> = flow {
                    emit(ChatChunk.Reasoning("частичный ход"))
                    throw failure
                }
            }
            val error = runCatching {
                AgentLoop(client).stream("m", null, emptyList(), "вопрос", onTurnMessages = { callbacks++ })
                    .collect { chunks += it }
            }.exceptionOrNull()
            assertTrue(error === failure)
            assertEquals(0, callbacks)
            assertEquals(listOf(ChatChunk.Reasoning("частичный ход")), chunks)
        }
    }
}
