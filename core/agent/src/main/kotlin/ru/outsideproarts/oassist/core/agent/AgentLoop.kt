package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolFunctionSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto

/**
 * Агентный цикл: отправляет сообщения в модель, выполняет вызовы инструментов,
 * передаёт результаты обратно и стримит итоговый ответ.
 *
 * Инструменты с риском выше [ActionRisk.AUTO] без записи «всегда разрешать»
 * приостанавливают стрим: эмитится [ChatChunk.ApprovalNeeded] и цикл ждёт
 * решения в [approvalController] (UI отвечает через [resolveApproval]).
 * Headless-вызывающие ([complete], VIS, Share) без UI-резолвера зависнут
 * на ASK-инструментах — им нужен [approvalPolicy] с автоправилом.
 */
class AgentLoop(
    private val modelClient: ModelClient,
    private val toolRegistry: ToolRegistry? = null,
    val approvalController: ApprovalController = ApprovalController(),
    private val approvalPolicy: ApprovalPolicy = InMemoryApprovalPolicy(),
    private val events: AgentEvents? = null,
    /** У модели флаг VISION — иначе образы вырезаются с пометкой (провайдеры без vision их отвергают). */
    private val visionEnabled: Boolean = true,
    private val maxToolRounds: Int = ru.outsideproarts.oassist.core.model.ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
) {

    /** Ответить на запрос подтверждения; `false` — такого ожидания нет. */
    fun resolveApproval(callId: String, decision: ApprovalDecision): Boolean =
        approvalController.resolve(callId, decision)

    fun stream(
        modelId: String,
        systemPrompt: String?,
        history: List<ChatMessage>,
        userMessage: String,
        /** Голосовое приложение PTT к user-ходу (null — текстовый вопрос). */
        audio: ru.outsideproarts.oassist.core.provider.AudioPart? = null,
        /** Образы к user-сообщению (Share-вложения, скриншоты). Null — без образов. */
        images: List<ru.outsideproarts.oassist.core.provider.ImagePart>? = null,
        /** Сообщения завершённого хода (assistant с toolCalls → TOOL → финальный assistant), без system/user. */
        onTurnMessages: ((List<ChatMessage>) -> Unit)? = null,
    ): Flow<ChatChunk> = flow {
        val userImages = if (visionEnabled) images.orEmpty() else emptyList()
        val userText = if (!visionEnabled && !images.isNullOrEmpty()) {
            userMessage + IMAGE_DISABLED_NOTE
        } else {
            userMessage
        }
        val messages = buildList {
            systemPrompt?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            addAll(history)
            add(ChatMessage(ChatRole.USER, userText, audio = audio, images = userImages))
        }.toMutableList()
        val turnStart = messages.size

        val tools = toolRegistry?.schemas()?.map {
            ToolSchemaDto(
                function = ToolFunctionSchemaDto(
                    name = it.name,
                    description = it.description,
                    parameters = it.parameters,
                ),
            )
        } ?: emptyList()

        var rounds = 0
        while (maxToolRounds <= 0 || rounds < maxToolRounds) {
            rounds++
            val toolCalls = mutableListOf<ChatChunk.ToolCall>()
            val contentSb = StringBuilder()

            modelClient.chatCompletionStream(
                modelId = modelId,
                messages = enforceImageBudget(messages),
                tools = tools,
            ).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Content -> contentSb.append(chunk.delta)
                    is ChatChunk.ToolCall -> toolCalls.add(chunk)
                    is ChatChunk.Finish -> {}
                    // Модель подтверждений/результатов не присылает: ветки для exhaustiveness.
                    is ChatChunk.ApprovalNeeded -> {}
                    is ChatChunk.ToolResult -> {}
                }
            }

            if (toolCalls.isEmpty()) {
                if (contentSb.isNotEmpty()) emit(ChatChunk.Content(contentSb.toString()))
                emit(ChatChunk.Finish(reason = "stop"))
                val turn = messages.drop(turnStart).toList()
                onTurnMessages?.invoke(
                    if (contentSb.isNotEmpty()) {
                        turn + ChatMessage(ChatRole.ASSISTANT, contentSb.toString())
                    } else {
                        turn
                    },
                )
                return@flow
            }

            if (contentSb.isNotEmpty()) {
                // Текст перед тул-чунками — комментарий агента к вызовам
                // («ищу контакт…»): UI решает, озвучивать ли его, по флагу режима.
                emit(ChatChunk.Content(contentSb.toString(), isToolComment = true))
            }
            // Ход ассистента с вызовами — одним сообщением с tool_calls,
            // чтобы сервер связал последующие tool-ответы с этим ходом.
            messages.add(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = contentSb.toString(),
                    toolCalls = toolCalls.map { tc ->
                        ru.outsideproarts.oassist.core.provider.ToolCallRef(
                            id = tc.id,
                            name = tc.name,
                            argumentsJson = tc.argumentsJson,
                        )
                    },
                ),
            )

            for (tc in toolCalls) {
                emit(ChatChunk.ToolCall(id = tc.id, name = tc.name, argumentsJson = tc.argumentsJson))
                events?.onToolCall(tc.name, tc.argumentsJson)
                val args = runCatching {
                    Json.parseToJsonElement(tc.argumentsJson).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }
                val result = executeOneTool(tc, args) { emit(it) }
                events?.onToolResult(tc.name, result.output)
                // Результат — отдельной сущностью ToolResult (в полотно «Ход работы»,
                // не в основной ответ; в синтез не идёт). Текстовый маркер
                // `[name: output]` больше не эмитим — модель получает результат
                // через tool-сообщение ниже, UI — через этот чанк.
                emit(
                    ChatChunk.ToolResult(
                        id = tc.id,
                        name = tc.name,
                        output = result.output,
                        isError = !result.success,
                        images = result.images,
                    ),
                )
                // Результат — отдельным tool-сообщением с tool_call_id,
                // а не текстом от ассистента: иначе модель не узнаёт свой вызов и идёт по кругу.
                val toolImages = if (visionEnabled) result.images else emptyList()
                val toolOutput = if (!visionEnabled && result.images.isNotEmpty()) {
                    result.output + IMAGE_DISABLED_NOTE
                } else {
                    result.output
                }
                messages.add(
                    ChatMessage(
                        role = ChatRole.TOOL,
                        content = toolOutput,
                        toolCallId = tc.id,
                        images = toolImages,
                    ),
                )
            }
        }

        emit(ChatChunk.Content("Достигнут лимит вызовов инструментов."))
        emit(ChatChunk.Finish(reason = "max_tool_rounds"))
        onTurnMessages?.invoke(messages.drop(turnStart).toList())
    }

    suspend fun complete(
        modelId: String,
        systemPrompt: String?,
        history: List<ChatMessage>,
        userMessage: String,
    ): String {
        val sb = StringBuilder()
        stream(modelId, systemPrompt, history, userMessage).collect { chunk ->
            if (chunk is ChatChunk.Content) sb.append(chunk.delta)
        }
        return sb.toString()
    }

    /**
     * Выполнить один тул-чонк с учётом политики подтверждений.
     * Риск — эффективный из реестра (дефолт инструмента + переопределение
     * пользователя); неизвестный инструмент — ASK, fail-closed.
     * Выключенный в настройках тул не выполняется: явная ошибка в ленту,
     * а не молчаливый пропуск.
     * При паузе эмитит [ChatChunk.ApprovalNeeded] через [emitChunk] и ждёт
     * решения в [approvalController] (отмена коллектора снимает ожидание).
     */
    private suspend fun executeOneTool(
        tc: ChatChunk.ToolCall,
        args: JsonObject,
        emitChunk: suspend (ChatChunk) -> Unit,
    ): ToolResult {
        if (toolRegistry == null) return ToolResult(false, "Инструменты недоступны")
        val tool = toolRegistry.find(tc.name)
        if (tool != null && !toolRegistry.isEnabled(tc.name)) {
            return ToolResult(false, "Инструмент '${tc.name}' отключён в настройках")
        }
        val risk = toolRegistry.effectiveRisk(tc.name)
        if (approvalPolicy.shouldAutoApprove(tc.name, risk)) {
            return toolRegistry.execute(tc.name, args)
        }
        val summary = tool?.describeForConfirmation(args) ?: "${tc.name}: ${tc.argumentsJson}"
        approvalController.prepare(tc.id)
        emitChunk(
            ChatChunk.ApprovalNeeded(
                ApprovalRequest(
                    callId = tc.id,
                    toolName = tc.name,
                    argumentsJson = tc.argumentsJson,
                    summary = summary,
                    risk = risk,
                ),
            ),
        )
        return when (approvalController.awaitDecision(tc.id)) {
            ApprovalDecision.ALLOW_ONCE -> toolRegistry.execute(tc.name, args)
            ApprovalDecision.ALWAYS_ALLOW -> {
                approvalPolicy.setAlwaysAllow(tc.name, true)
                toolRegistry.execute(tc.name, args)
            }
            ApprovalDecision.DENY -> ToolResult(false, "Отклонено пользователем")
        }
    }
}
