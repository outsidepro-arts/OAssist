package ru.outsideproarts.oassist.core.provider.openai

/**
 * Собранный вызов инструмента: один логический вызов из N SSE-фрагментов.
 */
data class AssembledToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val index: Int,
)

/**
 * Аккумулятор фрагментов `tool_calls` OpenAI-совместимого стрима.
 * Сервер шлёт вызов кусками: первый фрагмент — `id`+`name`+начало `arguments`,
 * продолжения — только куски `arguments` с тем же `index` (без `id`!).
 * Без сборки каждый фрагмент исполнялся бы отдельным вызовом с битым JSON.
 */
class ToolCallAssembler {

    private data class Acc(
        var id: String?,
        var name: String?,
        val args: StringBuilder = StringBuilder(),
    )

    private val accs = LinkedHashMap<Int, Acc>()

    /** Скормить `tool_calls` из дельты одного SSE-чанка. */
    fun feed(fragment: ToolCallDeltaDto) {
        val acc = accs.getOrPut(fragment.index) { Acc(null, null) }
        fragment.id?.let { acc.id = it }
        fragment.function?.name?.let { acc.name = it }
        fragment.function?.arguments?.let { acc.args.append(it) }
    }

    /**
     * Собранные вызовы в порядке `index`.
     * Вызов без имени (сервер так и не прислал `name`) отдаётся с пустым именем —
     * дальше его ловит fail-closed ветка реестра (ASK + диалог), а не молчаливый пропуск.
     */
    fun build(): List<AssembledToolCall> =
        accs.map { (index, acc) ->
            AssembledToolCall(
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
