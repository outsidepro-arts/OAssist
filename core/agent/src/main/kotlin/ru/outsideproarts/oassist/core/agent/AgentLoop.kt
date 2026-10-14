package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.model.ApprovalState
import ru.outsideproarts.oassist.core.model.ProviderUsage
import ru.outsideproarts.oassist.core.model.mergeUsage
import ru.outsideproarts.oassist.core.provider.ChatChunk
import ru.outsideproarts.oassist.core.provider.ChatMessage
import ru.outsideproarts.oassist.core.provider.ChatRole
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.core.provider.openai.ToolFunctionSchemaDto
import ru.outsideproarts.oassist.core.provider.openai.ToolSchemaDto
import ru.outsideproarts.oassist.core.provider.retryEarlyFailure

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
    private val maxToolRounds: Int = ru.outsideproarts.oassist.core.model.ToolPreferences.DEFAULT_MAX_TOOL_ROUNDS,
    /** Ключ сессии для «разрешено на текущую сессию» (режим: CHAT/ASSISTANT/SHARE). */
    private val sessionScope: String = "CHAT",
    /** Запрос runtime-разрешений в момент действия; null — тулы проверяют сами. */
    private val permissionRequester: PermissionRequester? = null,
    /** Заблокирован ли экран в момент запроса (пометка модели и подписи диалога). */
    private val screenLocked: () -> Boolean = { false },
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
        // Обрыв стрима модели виден только здесь (внутренний Finish не форвардится).
        // Пишется перезаписью, а не OR: обрыв промежуточного тула-раунда не должен
        // помечать усечённым целый финальный ответ.
        var streamAbrupt = false
        // Сумма usage по раундам хода; промежуточные Finish клиентов usage не несут
        // (задвоение исключено на уровне клиентов).
        var turnUsage: ProviderUsage? = null
        while (maxToolRounds <= 0 || rounds < maxToolRounds) {
            rounds++
            val toolCalls = mutableListOf<ChatChunk.ToolCall>()
            val contentSb = StringBuilder()

            modelClient.chatCompletionStream(
                modelId = modelId,
                messages = enforceImageBudget(messages),
                tools = tools,
            ).retryEarlyFailure(resubscribe = {
                // Та же история/инструменты раунда: модель ещё ничего не прислала.
                modelClient.chatCompletionStream(
                    modelId = modelId,
                    messages = enforceImageBudget(messages),
                    tools = tools,
                )
            }).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Content -> contentSb.append(chunk.delta)
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

            if (toolCalls.isEmpty()) {
                if (contentSb.isNotEmpty()) emit(ChatChunk.Content(contentSb.toString()))
                emit(ChatChunk.Finish(reason = "stop", abrupt = streamAbrupt, usage = turnUsage))
                turnUsage?.let { events?.onTurnUsage(it) }
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
                val args = runCatching {
                    Json.parseToJsonElement(tc.argumentsJson).jsonObject
                }.getOrElse { kotlinx.serialization.json.buildJsonObject { } }
                val toolSummary = toolRegistry?.find(tc.name)?.describeForConfirmation(args)
                    ?: "${tc.name}: ${tc.argumentsJson}"
                events?.onToolCall(tc.name, tc.argumentsJson, toolSummary)
                val result = executeOneTool(tc, args) { emit(it) }
                events?.onToolResult(tc.name, result.output, result.success)
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
        emit(ChatChunk.Finish(reason = "max_tool_rounds", usage = turnUsage))
        turnUsage?.let { events?.onTurnUsage(it) }
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
     * Выполнить один тул-чонк: сначала системные runtime-разрешения, потом
     * подтверждение по группе разрешений тула. Выключенный в настройках тул не
     * выполняется: явная ошибка в ленту, а не молчаливый пропуск.
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

        val permResult = gateRuntimePermissions(tool)
        if (permResult != null) return permResult

        val groupKey = toolRegistry.groupKey(tc.name)
        return when (approvalPolicy.state(groupKey, sessionScope)) {
            ApprovalState.ALLOW -> toolRegistry.execute(tc.name, args)
            ApprovalState.DENY -> ToolResult(false, DENIED_MESSAGE)
            ApprovalState.ASK -> askAndExecute(toolRegistry, tc, args, tool, groupKey, emitChunk)
        }
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
        when (decision) {
            ApprovalDecision.ALLOW_SESSION -> approvalPolicy.setSessionAllow(groupKey, sessionScope)
            ApprovalDecision.ALWAYS_ALLOW -> approvalPolicy.setRemembered(groupKey, ApprovalState.ALLOW)
            ApprovalDecision.ALWAYS_DENY -> approvalPolicy.setRemembered(groupKey, ApprovalState.DENY)
            ApprovalDecision.ALLOW_ONCE, ApprovalDecision.DENY -> Unit
        }
        val note = buildNote(decision, locked)
        return if (decision == ApprovalDecision.DENY || decision == ApprovalDecision.ALWAYS_DENY) {
            ToolResult(false, DENIED_MESSAGE + note)
        } else {
            val result = registry.execute(tc.name, args)
            ToolResult(result.success, note + result.output, result.images)
        }
    }

    /** Пометка модели о решении (и о блокировке экрана — чтобы попросила разблокировать). */
    private fun buildNote(decision: ApprovalDecision, locked: Boolean): String {
        val sb = StringBuilder()
        when (decision) {
            ApprovalDecision.ALLOW_ONCE -> sb.append("Пользователь разрешил действие. ")
            ApprovalDecision.ALLOW_SESSION -> sb.append("Пользователь разрешил действие на текущую сессию. ")
            ApprovalDecision.ALWAYS_ALLOW -> sb.append("Пользователь разрешил действие навсегда. ")
            ApprovalDecision.DENY -> sb.append("Действие отклонено пользователем. ")
            ApprovalDecision.ALWAYS_DENY -> sb.append("Пользователь запретил это действие. ")
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
