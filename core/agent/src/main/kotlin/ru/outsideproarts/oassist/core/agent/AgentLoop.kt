package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.CURRENT_TURN_ID
import ru.outsideproarts.oassist.core.model.MessagePin
import ru.outsideproarts.oassist.core.model.PinChange
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.mergeUsage
import ru.outsideproarts.oassist.core.model.parseMessageId
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.model.ChatMessage
import ru.outsideproarts.oassist.core.model.ChatRole
import ru.outsideproarts.oassist.core.model.ContextData
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolFunctionSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.provider.retryEarlyFailure
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticKind
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticContext
import ru.outsideproarts.oassist.core.model.diagnostics.DiagnosticRecorder
import ru.outsideproarts.oassist.core.model.diagnostics.emit
import kotlinx.serialization.encodeToString

/**
 * Агентный цикл: отправляет сообщения в модель, выполняет вызовы инструментов,
 * передаёт результаты обратно и стримит итоговый ответ.
 *
 * Подтверждения идут по группам разрешений: группа в состоянии ASK приостанавливает
 * стрим — эмитится [ChatChunk.ApprovalNeeded], цикл ждёт решения в [approvalController]
 * (UI отвечает через [resolveApproval]); ALLOW — без вопросов, DENY — молчаливый отказ.
 *
 * Системные runtime-разрешения запрашиваются раньше подтверждения: выдача возвращает
 * модели «попробуйте ещё раз» (повтор не даёт второй диалог подтверждения), отказ —
 * «действие отклонено».
 */
class AgentLoop(
    private val modelClient: ModelClient,
    private val toolRegistry: ToolRegistry? = null,
    val approvalController: ApprovalController = ApprovalController(),
    private val approvalPolicy: ApprovalPolicy = InMemoryApprovalPolicy(),
    private val events: AgentEvents? = null,
    /** У модели флаг VISION — иначе образы вырезаются с пометкой (провайдеры без vision их отвергают). */
    private val visionEnabled: Boolean = true,
    /** У модели флаг REASONING — Anthropic шлёт `thinking`, Responses — `reasoning.summary`. */
    private val reasoningEnabled: Boolean = false,
    private val maxToolRounds: Int = ru.outsideproarts.oassist.core.model.ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
    /** Ключ сессии для «разрешено на текущую сессию» (режим: CHAT/ASSISTANT/SHARE). */
    private val sessionScope: String = "CHAT",
    /** Запрос runtime-разрешений в момент действия; null — тулы проверяют сами. */
    private val permissionRequester: PermissionRequester? = null,
    /** Заблокирован ли экран в момент запроса (пометка модели и подписи диалога). */
    private val screenLocked: () -> Boolean = { false },
    private val fallbackFarewell: String? = null,
    private val diagnostics: DiagnosticRecorder,
    /**
     * Контекст построения стрима (upstream): сериализация запросов,
     * диагностика wire-трафика, санитайз контента событий, исполнение тулов.
     * По умолчанию — поток коллектора (прежнее поведение); фабрика приложения
     * передаёт `Dispatchers.IO`, чтобы тяжёлая работа не шла на UI-потоке
     * (оверлей ассистента собирает стрим на главном). Инструменты сами
     * переходят на Main там, где он нужен (WebRender, UiActions, DeviceActions).
     */
    private val streamContext: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    /**
     * Контекст вызова колбэков слоёв (`onTurnMessages`, `onPinChange`,
     * `AgentEvents`): слои Chat/Share исторически рассчитывают на поток
     * коллектора (их очереди записи однонитные), поэтому колбэки возвращаются
     * из upstream в [callbackContext] (фабрика передаёт `Dispatchers.Main`).
     * `NonCancellable` — терминальный transcript и эффекты не теряются при
     * отмене хода. Дефолт — без переключения (прежнее поведение).
     */
    private val callbackContext: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
) {
    private fun diagnosticSecrets(): Collection<String> =
        modelClient.diagnosticSecrets() + toolRegistry?.diagnosticSecrets().orEmpty()

    /** Колбэк слоя в его контексте; NonCancellable — событие не теряется при отмене. */
    private suspend fun <T> inCallbackContext(block: suspend () -> T): T =
        withContext(kotlinx.coroutines.NonCancellable + callbackContext) { block() }

    /** Ответить на запрос подтверждения; `false` — такого ожидания нет. */
    fun resolveApproval(callId: String, decision: ApprovalDecision): Boolean =
        approvalController.resolve(callId, decision)

    fun stream(
        modelId: String,
        systemPrompt: String?,
        history: List<ChatMessage>,
        userMessage: String,
        /** Голосовое приложение PTT к user-ходу (null — текстовый вопрос). */
        audio: ru.outsideproarts.oassist.core.model.AudioPart? = null,
        /** Образы к user-сообщению (Share-вложения, скриншоты). Null — без образов. */
        images: List<ru.outsideproarts.oassist.core.model.ImagePart>? = null,
        /** Ровно один терминальный transcript, включая ошибку/отмену; callback не должен бросать. */
        onTurnMessages: ((List<ChatMessage>) -> Unit)? = null,
        promptContext: List<ContextData> = emptyList(),
        modelUserInput: ModelUserInput? = null,
        /**
         * Закрепления/открепления сообщений (эффект `message_pin`/`message_unpin`):
         * вызывается после применения к живой истории; вызывающий слой сохраняет
         * флаг вместе с ходом (адреса `current:<индекс>` — у текущего хода).
         * Сбой колбэка не роняет ход — превращается в предупреждение модели.
         */
        onPinChange: (suspend (PinChange) -> Unit)? = null,
    ): Flow<ChatChunk> = flow {
        val diagnosticOperation = Diagnostics.newOperationId()
        val parentOperation = currentCoroutineContext()[DiagnosticContext]?.operationId
        diagnostics.emit(DiagnosticKind.OPERATION_START, diagnosticOperation,
            parentOperationId = parentOperation,
            attributes = mapOf("mode" to sessionScope, "operation" to "agent"), secrets = diagnosticSecrets())
        try {
        val currentInput = modelUserInput ?: ModelUserInput(userMessage)
        val messages = buildList {
            addAll(trimHistoryAudio(history))
            add(
                ChatMessage(
                    ChatRole.USER,
                    currentInput.requestText,
                    audio = audio,
                    images = images.orEmpty(),
                    contextData = currentInput.context,
                ),
            )
        }.toMutableList()
        val currentUserIndex = history.size
        val turnStart = messages.size
        var turnDelivered = false
        var startedCallId: String? = null
        suspend fun deliverTurn() {
            if (turnDelivered) return
            turnDelivered = true
            inCallbackContext { onTurnMessages?.invoke(messages.drop(turnStart).toList()) }
        }
        try {
        val liveSkillInstructions = linkedSetOf<String>()
        val liveToolStatuses = mutableMapOf<Int, Boolean>()

        val tools = toolRegistry?.schemas()?.map {
            ToolSchemaDto(
                function = ToolFunctionSchemaDto(
                    name = it.name,
                    description = it.description,
                    parameters = it.parameters,
                ),
            )
        } ?: emptyList()
        diagnostics.emit(DiagnosticKind.TOOL_SCHEMA, diagnosticOperation,
            content = Json.encodeToString(tools), secrets = diagnosticSecrets())

        var rounds = 0
        // Обрыв стрима модели виден только здесь (внутренний Finish не форвардится).
        // Пишется перезаписью, а не OR: обрыв промежуточного тула-раунда не должен
        // помечать усечённым целый финальный ответ.
        var streamAbrupt = false
        // Сумма usage по раундам хода; промежуточные Finish клиентов usage не несут
        // (задвоение исключено на уровне клиентов).
        var turnUsage: ProviderUsage? = null
        // Итог хода с числом запросов к модели: round-trip'ы = раунды тулов.
        fun turnUsageWithRequests(): ProviderUsage? = turnUsage?.copy(requests = rounds)
        while (maxToolRounds <= 0 || rounds < maxToolRounds) {
            rounds++
            val toolCalls = mutableListOf<ChatChunk.ToolCall>()
            val contentSb = StringBuilder()
            // Мысли раунда: живой поток наружу + накопление для
            // `ChatMessage.reasoning` (replay Anthropic/Responses в истории).
            val reasoningSb = StringBuilder()
            var reasoningSignature: String? = null
            val replayItems = mutableListOf<String>()
            var anthropicReplay: ru.outsideproarts.oassist.core.model.AnthropicReplay? = null
            var geminiReplay: ru.outsideproarts.oassist.core.model.GeminiReplay? = null

            val budgetedMessages = if (visionEnabled) {
                enforceImageBudget(messages)
            } else {
                messages.map { message ->
                    if (message.images.isEmpty()) message else message.copy(
                        images = emptyList(),
                        content = message.content + IMAGE_DISABLED_NOTE,
                    )
                }
            }
            // Закреплённые образы истории — в текущее сообщение: свежая позиция
            // гарантирует, что модель увидит референс (см. hoistPinnedImages).
            val hoisted = hoistPinnedImages(budgetedMessages, currentUserIndex)
            val roundMessages = buildRuntimeMessages(
                hoisted.messages,
                currentUserIndex,
                systemPrompt,
                promptContext,
                currentInput.copy(requestText = hoisted.messages[currentUserIndex].content),
                liveSkillInstructions.toList(),
                liveToolStatuses,
                hoisted.sources,
            )
            diagnostics.emit(DiagnosticKind.AGENT_ROUND, diagnosticOperation,
                attributes = mapOf("round" to rounds.toString(), "model" to modelId),
                content = Json.encodeToString(roundMessages), secrets = diagnosticSecrets())
            val streamRound = modelClient.chatCompletionStream(
                modelId = modelId,
                messages = roundMessages,
                tools = tools,
                reasoningEnabled = reasoningEnabled,
            )
            try {
            streamRound.retryEarlyFailure(resubscribe = {
                // Та же история/инструменты раунда: модель ещё ничего не прислала.
                modelClient.chatCompletionStream(
                    modelId = modelId,
                    messages = roundMessages,
                    tools = tools,
                    reasoningEnabled = reasoningEnabled,
                )
            }).flowOn(DiagnosticContext(diagnosticOperation, secrets = diagnosticSecrets())).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Content -> contentSb.append(chunk.delta)
                    // Мысли — live (UI показывает процесс размышления), без буферизации.
                    is ChatChunk.Reasoning -> {
                        if (chunk.delta.isNotEmpty()) reasoningSb.append(chunk.delta)
                        chunk.signature?.let { reasoningSignature = it }
                        chunk.replayJson?.let { replayItems += it }
                        chunk.anthropicReplay?.let { anthropicReplay = it }
                        chunk.geminiReplay?.let { geminiReplay = it }
                        emit(chunk)
                    }
                    is ChatChunk.ProviderNotice -> emit(chunk)
                    is ChatChunk.ToolCall -> toolCalls.add(chunk)
                    is ChatChunk.Finish -> {
                        streamAbrupt = chunk.abrupt
                        turnUsage = mergeUsage(turnUsage, chunk.usage)
                    }
                    // Модель подтверждений/результатов не присылает: ветки для exhaustiveness.
                    is ChatChunk.ApprovalNeeded -> {}
                    is ChatChunk.ToolResult -> {}
                }
            }
            } catch (failure: Throwable) {
                // This round never reached dispatch. Pending calls and their provider envelopes
                // are not a completed protocol turn; earlier executed rounds remain untouched.
                val partialReasoning = buildReasoningBlock(reasoningSb, reasoningSignature, replayItems, null, null)
                if (contentSb.isNotEmpty() || partialReasoning != null) {
                    messages.add(ChatMessage(ChatRole.ASSISTANT, contentSb.toString(),
                        reasoning = partialReasoning))
                }
                throw failure
            }
            val reasoning = buildReasoningBlock(reasoningSb, reasoningSignature, replayItems, anthropicReplay, geminiReplay)
            diagnostics.emit(DiagnosticKind.PROVIDER_RESPONSE, diagnosticOperation,
                attributes = mapOf("round" to rounds.toString(), "abrupt" to streamAbrupt.toString()),
                content = Json.encodeToString(ChatMessage(ChatRole.ASSISTANT, contentSb.toString(),
                    toolCalls = toolCalls.map { ru.outsideproarts.oassist.core.model.ToolCallRef(it.id, it.name, it.argumentsJson, it.thoughtSignature, it.geminiFunctionCallId) },
                    reasoning = reasoning)), secrets = diagnosticSecrets())

            if (toolCalls.isEmpty()) {
                if (contentSb.isNotEmpty() || reasoning != null) {
                    messages.add(ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = contentSb.toString(),
                            reasoning = reasoning,
                        ))
                }
                deliverTurn()
                if (contentSb.isNotEmpty()) emit(ChatChunk.Content(contentSb.toString()))
                val finalUsage = turnUsageWithRequests()
                emit(ChatChunk.Finish(reason = "stop", abrupt = streamAbrupt, usage = finalUsage))
                finalUsage?.let { usage -> inCallbackContext { events?.onTurnUsage(usage) } }
                diagnostics.emit(DiagnosticKind.OPERATION_END, diagnosticOperation,
                    attributes = mapOf("reason" to "stop", "abrupt" to streamAbrupt.toString()), secrets = diagnosticSecrets())
                return@flow
            }

            val includesBuiltinBye = toolCalls.any { toolRegistry?.find(it.name) is ByeTool }
            val terminalBye = includesBuiltinBye && toolCalls.size == 1
            val invalidByeBatch = includesBuiltinBye && toolCalls.size != 1

            // Ход ассистента с вызовами — одним сообщением с tool_calls,
            // чтобы сервер связал последующие tool-ответы с этим ходом.
            messages.add(
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = contentSb.toString(),
                    toolCalls = toolCalls.map { tc ->
                        ru.outsideproarts.oassist.core.model.ToolCallRef(
                            id = tc.id,
                            name = tc.name,
                            argumentsJson = tc.argumentsJson,
                            thoughtSignature = tc.thoughtSignature,
                            geminiFunctionCallId = tc.geminiFunctionCallId,
                        )
                    },
                    reasoning = reasoning,
                ),
            )

            if (contentSb.isNotEmpty() && !includesBuiltinBye) {
                emit(ChatChunk.Content(contentSb.toString(), isToolComment = true))
            }
            for (tc in toolCalls) {
                if (!invalidByeBatch) {
                    emit(ChatChunk.ToolCall(id = tc.id, name = tc.name, argumentsJson = tc.argumentsJson,
                        thoughtSignature = tc.thoughtSignature, geminiFunctionCallId = tc.geminiFunctionCallId))
                }
                val parsedArgs = runCatching {
                    Json.parseToJsonElement(tc.argumentsJson).jsonObject
                }
                val args = parsedArgs.getOrNull()
                diagnostics.emit(DiagnosticKind.TOOL_CALL, diagnosticOperation,
                    attributes = mapOf("name" to tc.name, "call_id" to tc.id,
                        "arguments_valid" to parsedArgs.isSuccess.toString()),
                    content = tc.argumentsJson, secrets = diagnosticSecrets())
                val farewellElement = if (terminalBye) args?.get("farewell") else null
                val invalidFarewell = terminalBye && (
                    parsedArgs.isFailure || (farewellElement != null &&
                        (farewellElement !is JsonPrimitive || !farewellElement.isString))
                    )
                val farewell = if (terminalBye && !invalidFarewell) {
                    (farewellElement as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
                        ?: contentSb.toString().trim().takeIf { it.isNotBlank() }
                        ?: fallbackFarewell?.trim()?.takeIf { it.isNotBlank() }
                } else null
                val toolSummary = args?.let { toolRegistry?.find(tc.name)?.describeForConfirmation(it) }
                    ?: "${tc.name}: ${tc.argumentsJson}"
                val narrationName = toolRegistry?.find(tc.name)?.narrationName ?: tc.name
                if (!includesBuiltinBye) inCallbackContext { events?.onToolCall(narrationName, tc.argumentsJson, toolSummary) }
                val result = applyPinChange(
                    when {
                        invalidByeBatch -> ToolResult(false, "bye должен быть единственным вызовом раунда; повторите запрос отдельно")
                        invalidFarewell -> ToolResult(false, "Некорректные аргументы bye: farewell должен быть строкой")
                        args == null -> ToolResult(false, "Некорректные аргументы инструмента: ожидается JSON-объект")
                        terminalBye && farewell == null -> ToolResult(false, "Для bye не задан текст прощания")
                        else -> executeOneTool(tc, args, diagnosticOperation, { startedCallId = tc.id }) { emit(it) }
                    },
                    messages,
                    currentUserIndex,
                    onPinChange,
                )
                // Commit the observed result before diagnostics, callbacks or cancellable UI emission.
                messages.add(ChatMessage(ChatRole.TOOL, result.output, toolCallId = tc.id, images = result.images))
                liveToolStatuses[messages.lastIndex] = result.success
                startedCallId = null
                diagnostics.emit(DiagnosticKind.TOOL_RESULT, diagnosticOperation,
                    attributes = mapOf("name" to tc.name, "call_id" to tc.id,
                        "success" to result.success.toString(), "images_omitted" to result.images.size.toString()),
                    content = Json.encodeToString(ChatMessage(ChatRole.TOOL, result.output, images = result.images)),
                    secrets = diagnosticSecrets())
                if (result.success && toolRegistry?.find(tc.name) is SkillGetTool) {
                    result.ownerInstructions?.takeIf { it.isNotBlank() }?.let(liveSkillInstructions::add)
                }
                if (!includesBuiltinBye) inCallbackContext { events?.onToolResult(narrationName, result.output, result.success) }
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
                if (terminalBye && result.success) {
                    val finalText = requireNotNull(farewell)
                    messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = finalText))
                    emit(ChatChunk.Content(finalText, isTerminalFarewell = true))
                    deliverTurn()
                    val finalUsage = turnUsageWithRequests()
                    emit(ChatChunk.Finish(reason = "stop", abrupt = streamAbrupt, usage = finalUsage, closeSession = true))
                    finalUsage?.let { usage -> inCallbackContext { events?.onTurnUsage(usage) } }
                    diagnostics.emit(DiagnosticKind.OPERATION_END, diagnosticOperation,
                        attributes = mapOf("reason" to "bye"), secrets = diagnosticSecrets())
                    return@flow
                }
            }
        }

        emit(ChatChunk.Content("Достигнут лимит вызовов инструментов."))
        deliverTurn()
        val finalUsage = turnUsageWithRequests()
        emit(ChatChunk.Finish(reason = "max_tool_rounds", usage = finalUsage))
        finalUsage?.let { usage -> inCallbackContext { events?.onTurnUsage(usage) } }
        diagnostics.emit(DiagnosticKind.OPERATION_END, diagnosticOperation,
            attributes = mapOf("reason" to "max_tool_rounds"), secrets = diagnosticSecrets())
        } finally {
            if (!turnDelivered) {
                val pending = messages.indexOfLast { it.role == ChatRole.ASSISTANT && !it.toolCalls.isNullOrEmpty() }
                if (pending >= turnStart) {
                    val answered = messages.drop(pending + 1).mapNotNull { it.toolCallId }.toSet()
                    messages[pending].toolCalls.orEmpty().filter { it.id !in answered }.forEach { call ->
                        val status = if (call.id == startedCallId) {
                            "Execution interrupted: outcome unknown; an external action may already have occurred. Do not repeat without checking its state."
                        } else {
                            "Not executed: the turn ended before this call started."
                        }
                        messages.add(ChatMessage(ChatRole.TOOL, status, toolCallId = call.id))
                    }
                }
                deliverTurn()
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            diagnostics.emit(DiagnosticKind.OPERATION_CANCELLED, diagnosticOperation, secrets = diagnosticSecrets())
            throw e
        } catch (e: Throwable) {
            diagnostics.emit(DiagnosticKind.OPERATION_ERROR, diagnosticOperation,
                attributes = mapOf("error" to e.javaClass.simpleName), secrets = diagnosticSecrets())
            throw e
        }
    }.flowOn(streamContext)

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
     * Reasoning-блок хода из накопленных чанков: текст (display + replay
     * Anthropic), подпись и JSON reasoning-item'ов Responses (несколько —
     * склеиваются в массив). Нечего отдавать — null (сообщение без блока).
     */
    private fun buildReasoningBlock(
        text: StringBuilder,
        signature: String?,
        replayItems: List<String>,
        anthropicReplay: ru.outsideproarts.oassist.core.model.AnthropicReplay?,
        geminiReplay: ru.outsideproarts.oassist.core.model.GeminiReplay?,
    ): ru.outsideproarts.oassist.core.model.ReasoningBlock? {
        if (text.isEmpty() && signature == null && replayItems.isEmpty() && anthropicReplay == null && geminiReplay == null) return null
        val replayJson = when (replayItems.size) {
            0 -> null
            1 -> replayItems.first()
            else -> replayItems.joinToString(",", "[", "]")
        }
        return ru.outsideproarts.oassist.core.model.ReasoningBlock(
            text = text.toString(),
            signature = signature,
            replayJson = replayJson,
            anthropicReplay = anthropicReplay,
            geminiReplay = geminiReplay,
        )
    }

    /**
     * Выполнить один тул-чонк: сначала системные runtime-разрешения, потом
     * подтверждение по группе разрешений тула. Выключенный в настройках тул не
     * выполняется: явная ошибка в ленту, а не молчаливый пропуск.
     * При паузе эмитит [ChatChunk.ApprovalNeeded] через [emitChunk] и ждёт
     * решения в [approvalController] (отмена коллектора снимает ожидание).
     */
    private suspend fun executeOneTool(
        tc: ChatChunk.ToolCall,
        args: JsonObject,
        diagnosticOperation: String,
        onDispatch: () -> Unit,
        emitChunk: suspend (ChatChunk) -> Unit,
    ): ToolResult {
        if (toolRegistry == null) return ToolResult(false, "Инструменты недоступны")
        val tool = toolRegistry.find(tc.name)
        if (tool != null && !toolRegistry.isEnabled(tc.name)) {
            return ToolResult(false, "Инструмент '${tc.name}' отключён в настройках")
        }

        val permResult = gateRuntimePermissions(tool)
        if (permResult != null) {
            diagnostics.emit(DiagnosticKind.TOOL_CALL, diagnosticOperation,
                attributes = mapOf("name" to tc.name, "call_id" to tc.id, "stage" to "runtime_permission"), secrets = diagnosticSecrets())
            return permResult
        }

        val groupKey = toolRegistry.groupKey(tc.name)
        val approvalState = approvalPolicy.state(groupKey, sessionScope)
        diagnostics.emit(DiagnosticKind.TOOL_CALL, diagnosticOperation,
            attributes = mapOf("name" to tc.name, "call_id" to tc.id, "approval_state" to approvalState.name), secrets = diagnosticSecrets())
        return when (approvalState) {
            ApprovalState.ALLOW -> withContext(DiagnosticContext(diagnosticOperation)) {
                onDispatch()
                toolRegistry.execute(tc.name, args)
            }
            ApprovalState.DENY -> ToolResult(false, DENIED_MESSAGE)
            ApprovalState.ASK -> askAndExecute(toolRegistry, tc, args, tool, groupKey, diagnosticOperation, onDispatch, emitChunk)
        }
    }

    /**
     * Применить закрепление из результата тула: сообщение помечается в живой
     * истории (метки конвертов и бюджеты следующих раундов видят флаг сразу),
     * затем эффект уходит вызывающему слою для сохранения. Неизвестный адрес —
     * честная ошибка вместо ложного «закреплено»; снятие с незакреплённого —
     * «не было закреплено». Сбой сохранения эффекта не роняет ход: модель
     * получает предупреждение, закрепление остаётся в живой истории.
     */
    private suspend fun applyPinChange(
        result: ToolResult,
        messages: MutableList<ChatMessage>,
        currentUserIndex: Int,
        onPinChange: (suspend (PinChange) -> Unit)?,
    ): ToolResult {
        val change = result.pinChange ?: return result
        val ref = parseMessageId(change.messageId)
        val target = when {
            ref == null -> -1
            ref.turnId == CURRENT_TURN_ID ->
                (currentUserIndex + ref.index).takeIf { it in messages.indices } ?: -1
            else -> messages.indexOfFirst { it.id == change.messageId }
        }
        if (target < 0) {
            return result.copy(
                success = false,
                pinChange = null,
                output = "Сообщение с адресом «${change.messageId}» не найдено в истории — закрепление не применено.",
            )
        }
        if (!change.pinned && messages[target].pin == null) {
            return result.copy(
                pinChange = null,
                output = "Сообщение «${change.messageId}» не было закреплено — менять нечего.",
            )
        }
        messages[target] = messages[target].copy(
            pin = if (change.pinned) MessagePin(change.note) else null,
        )
        try {
            inCallbackContext { onPinChange?.invoke(change) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            return result.copy(
                output = result.output +
                    "\nВнимание: закрепление действует в текущей сессии, но не сохранено в истории: ${failure.message}",
            )
        }
        return result
    }

    /**
     * Запрос системных разрешений тула в момент действия. `null` — всё выдано
     * (или нечего запрашивать), иначе — результат вместо выполнения: выдача
     * просит модель повторить, отказ — честное «действие отклонено».
     */
    private suspend fun gateRuntimePermissions(tool: Tool?): ToolResult? {
        val requester = permissionRequester ?: return null
        val missing = tool?.requiredPermissions.orEmpty().filterNot(requester::isGranted)
        if (missing.isEmpty()) return null
        return when (requester.request(missing)) {
            PermissionRequestOutcome.GRANTED -> ToolResult(
                false,
                "Разрешение «${missing.joinToString()}» выдано пользователем. " +
                    "Попробуйте выполнить действие ещё раз.",
            )
            PermissionRequestOutcome.DENIED -> ToolResult(
                false,
                "Пользователь отклонил разрешение «${missing.joinToString()}» — действие отклонено.",
            )
            PermissionRequestOutcome.DENIED_FOREVER -> ToolResult(
                false,
                "Разрешение «${missing.joinToString()}» отклонено навсегда — попросите пользователя " +
                    "выдать его в настройках приложения OAssist, затем повторите действие.",
            )
            PermissionRequestOutcome.UNAVAILABLE -> ToolResult(
                false,
                "Не удалось показать системный диалог для разрешения «${missing.joinToString()}» — " +
                    "попросите пользователя открыть OAssist и выдать разрешение в настройках приложения.",
            )
        }
    }

    private suspend fun askAndExecute(
        registry: ToolRegistry,
        tc: ChatChunk.ToolCall,
        args: JsonObject,
        tool: Tool?,
        groupKey: String,
        diagnosticOperation: String,
        onDispatch: () -> Unit,
        emitChunk: suspend (ChatChunk) -> Unit,
    ): ToolResult {
        val summary = tool?.describeForConfirmation(args) ?: "${tc.name}: ${tc.argumentsJson}"
        val locked = screenLocked()
        approvalController.prepare(tc.id)
        emitChunk(
            ChatChunk.ApprovalNeeded(
                ApprovalRequest(
                    callId = tc.id,
                    toolName = tc.name,
                    argumentsJson = tc.argumentsJson,
                    summary = summary,
                    groupKey = groupKey,
                    screenLocked = locked,
                ),
            ),
        )
        val decision = approvalController.awaitDecision(tc.id)
        diagnostics.emit(DiagnosticKind.TOOL_CALL, diagnosticOperation,
            attributes = mapOf("name" to tc.name, "call_id" to tc.id, "approval_decision" to decision.name), secrets = diagnosticSecrets())
        when (decision) {
            ApprovalDecision.ALLOW_SESSION -> approvalPolicy.setSessionAllow(groupKey, sessionScope)
            ApprovalDecision.ALWAYS_ALLOW -> approvalPolicy.setRemembered(groupKey, ApprovalState.ALLOW)
            ApprovalDecision.ALWAYS_DENY -> approvalPolicy.setRemembered(groupKey, ApprovalState.DENY)
            ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY -> Unit
        }
        val note = buildNote(decision, groupKey, locked)
        return if (decision == ApprovalDecision.DENY || decision == ApprovalDecision.ALWAYS_DENY) {
            ToolResult(false, DENIED_MESSAGE + note)
        } else {
            val result = withContext(DiagnosticContext(diagnosticOperation)) {
                onDispatch()
                registry.execute(tc.name, args)
            }
            result.copy(output = note + result.output)
        }
    }

    /** Пометка модели о решении (и о блокировке экрана — чтобы попросила разблокировать). */
    private fun buildNote(decision: ApprovalDecision, groupKey: String, locked: Boolean): String {
        val sb = StringBuilder()
        when (decision) {
            ApprovalDecision.ALLOW_ONCE -> sb.append("Пользователь разрешил только этот вызов инструмента. ")
            ApprovalDecision.ALLOW_SESSION -> sb.append("Пользователь разрешил инструменты группы «$groupKey» на текущую сессию режима «$sessionScope». ")
            ApprovalDecision.ALWAYS_ALLOW -> sb.append("Пользователь сохранил разрешение инструментов группы «$groupKey» до изменения настройки. ")
            ApprovalDecision.DENY -> sb.append("Пользователь отклонил запрошенное действие. ")
            ApprovalDecision.ALWAYS_DENY -> sb.append("Пользователь сохранил запрет инструментов группы «$groupKey» до изменения настройки. ")
        }
        if (locked) {
            sb.append("Внимание: экран был заблокирован — попросите пользователя разблокировать экран. ")
        }
        return sb.toString()
    }

    companion object {
        private const val DENIED_MESSAGE = "Отклонено пользователем. "
    }
}
