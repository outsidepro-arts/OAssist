package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Чтение веб-страниц в контекст модели (автоматическое, без участия пользователя).
 * В отличие от `web_open`/`web_search` (открывают браузер человеку), возвращает
 * содержимое: Markdown статьи через readability (+ WebView-рендер для JS-страниц).
 */
class WebFetchTool(private val fetcher: WebFetcher) : Tool {
    override val schema = ToolSchema(
        name = "web_fetch",
        description = "Прочитать веб-страницу и вернуть её текст в Markdown. Используй когда нужно узнать содержимое страницы, а не просто открыть её пользователю.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL страницы")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "gist — короткая разведка (заголовок, структура, ссылки); full — полный текст (по умолчанию full)")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Лимит символов ответа (по умолчанию 12000, макс 50000)")
                })
                put("render", buildJsonObject {
                    put("type", "string")
                    put("description", "auto — дорендерить через WebView если статики мало (по умолчанию); never — только статика; always — сразу рендер")
                })
                put("images", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Грузить ли изображения при рендере (по умолчанию false)")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
        },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val url = arguments["url"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Не указан url")
        val mode = when (arguments["mode"]?.jsonPrimitive?.content?.lowercase()) {
            "gist" -> FetchMode.GIST
            null, "full" -> FetchMode.FULL
            else -> return ToolResult(false, "mode: gist/full")
        }
        val maxChars = arguments["max_chars"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: WebFetcher.DEFAULT_FULL_CHARS
        val renderMode = when (arguments["render"]?.jsonPrimitive?.content?.lowercase()) {
            null, "auto" -> RenderMode.AUTO
            "never" -> RenderMode.NEVER
            "always" -> RenderMode.ALWAYS
            else -> return ToolResult(false, "render: auto/never/always")
        }
        val images = arguments["images"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return fetcher.fetch(url, mode, maxChars, renderMode, images).fold(
            onSuccess = { page ->
                val tier = if (page.tier == FetchTier.RENDERED) "rendered" else "static"
                val header = "[web_fetch $tier] ${page.title ?: page.finalUrl}\n${page.finalUrl}\n\n"
                ToolResult(true, header + page.markdown)
            },
            onFailure = { ToolResult(false, "Не удалось прочитать страницу: ${mapFetchError(it)}") },
        )
    }
}
