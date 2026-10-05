package ru.outsideproarts.oassist.core.provider.anthropic

import ru.outsideproarts.oassist.core.provider.ChatChunk

/**
 * Аккумулятор фрагментов `tool_use` стрима Anthropic.
 * Сервер шлёт вызов кусками: `content_block_start` несёт `id`+`name`,
 * дальше — куски `input` через `input_json_delta` с тем же `index` (без `id`!).
 * Ключ — `index` контент-блока, как у [ru.outsideproarts.oassist.core.provider.openai.ToolCallAssembler].
 */
class AnthropicStreamAccumulator {

    private data class Acc(
        var id: String?,
        var name: String?,
        val args: StringBuilder = StringBuilder(),
    )

    private val accs = LinkedHashMap<Int, Acc>()

    /** Старт блока; нетекстовые нетулзовые блоки игнорируются. */
    fun contentBlockStart(index: Int, blockType: String?, id: String?, name: String?) {
        if (blockType != "tool_use") return
        val acc = accs.getOrPut(index) { Acc(null, null) }
        id?.let { acc.id = it }
        name?.let { acc.name = it }
    }

    /** Кусок `input` одного блока. */
    fun inputJsonDelta(index: Int, partialJson: String) {
        accs.getOrPut(index) { Acc(null, null) }.args.append(partialJson)
    }

    /**
     * Собранные вызовы в порядке `index`.
     * Вызов без имени отдаётся с пустым именем — дальше его ловит fail-closed
     * ветка реестра (ASK + диалог), а не молчаливый пропуск.
     */
    fun build(): List<ChatChunk.ToolCall> =
        accs.map { (index, acc) ->
            ChatChunk.ToolCall(
                id = acc.id ?: "call_$index",
                name = acc.name.orEmpty(),
                argumentsJson = acc.args.toString().ifBlank { "{}" },
                index = index,
            )
        }

    fun clear() {
        accs.clear()
    }
}
