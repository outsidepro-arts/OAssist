package ru.outsideproarts.oassist.core.agent.mcp

import kotlinx.serialization.json.JsonObject
import ru.outsideproarts.oassist.core.agent.Tool
import ru.outsideproarts.oassist.core.agent.ToolResult
import ru.outsideproarts.oassist.core.agent.ToolSchema
import ru.outsideproarts.oassist.core.model.ActionRisk

/**
 * Адаптер внешнего MCP-инструмента под интерфейс [Tool].
 * Риск — ASK по умолчанию (внешний код, fail-closed): понижается только
 * явным «не спрашивать» в настройках. Повысить до ALWAYS_ASK — там же.
 */
class McpTool(
    private val serverName: String,
    private val toolName: String,
    private val displayName: String,
    description: String?,
    parameters: JsonObject,
    private val client: McpClient,
) : Tool {

    override val schema: ToolSchema = ToolSchema(
        name = displayName,
        description = "MCP «$serverName»: ${description?.take(DESCRIPTION_LIMIT) ?: toolName}",
        parameters = parameters,
    )

    override val risk: ActionRisk = ActionRisk.ASK

    override fun describeForConfirmation(arguments: JsonObject): String =
        "MCP «$serverName» → $toolName: $arguments"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val result = client.callTool(toolName, arguments)
        val text = result.content.mapNotNull { it.text }.joinToString("\n").ifBlank { "(пусто)" }
        val output = if (text.length > OUTPUT_LIMIT) {
            text.take(OUTPUT_LIMIT) + "\n…[обрезано]"
        } else {
            text
        }
        return ToolResult(success = !result.isError, output = output)
    }

    companion object {
        private const val DESCRIPTION_LIMIT = 500
        private const val OUTPUT_LIMIT = 8000

        /**
         * Имя в реестре: `mcp_<первые 8 id>__<имя>`, санитизировано под
         * `^[a-zA-Z0-9_-]{1,64}` (требование OpenAI function calling),
         * стабильно при переименовании сервера.
         */
        fun displayName(serverId: String, toolName: String): String {
            // Только ASCII: `isLetterOrDigit` пропускает кириллицу, а OpenAI-гейт
            // требует `^[a-zA-Z0-9_-]{1,64}` — всё остальное в `_`.
            val short = serverId.filter { it.isAsciiWord() }.take(8).ifBlank { "srv" }
            val safe = toolName.map { c -> if (c.isAsciiWord()) c else '_' }
                .joinToString("").ifBlank { "tool" }
            return ("mcp_${short}__$safe").take(MAX_NAME_LENGTH)
        }

        private fun Char.isAsciiWord(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

        private const val MAX_NAME_LENGTH = 64
    }
}
