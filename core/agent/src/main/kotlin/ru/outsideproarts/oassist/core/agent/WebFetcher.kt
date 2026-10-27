package ru.outsideproarts.oassist.core.agent

/** Режим выдачи: разведка или полный текст. */
enum class FetchMode {
    GIST,
    FULL,
}

/** Когда использовать WebView-рендер. */
enum class RenderMode {
    AUTO,
    NEVER,
    ALWAYS,
}

/** Готовый результат для модели. */
data class FetchedResult(
    val finalUrl: String,
    val title: String?,
    val markdown: String,
    val tier: FetchTier,
    val truncated: Boolean,
    val totalChars: Int,
)

/**
 * Оркестратор чтения веба: статика → (markdown-вариант) → WebView-рендер.
 * Рендер включается по [RenderMode]: `ALWAYS` — сразу, `AUTO` — только если
 * статика дала меньше [WebRenderActions.MIN_STATIC_CHARS] символов.
 */
class WebFetcher(
    private val content: WebContentActions,
    private val render: WebRenderActions?,
) {

    suspend fun fetch(
        rawUrl: String,
        mode: FetchMode = FetchMode.FULL,
        maxChars: Int = DEFAULT_FULL_CHARS,
        renderMode: RenderMode = RenderMode.AUTO,
        loadImages: Boolean = false,
    ): Result<FetchedResult> {
        val url = WebContentActions.normalizeUrl(rawUrl)
            ?: return Result.failure(IllegalArgumentException("Некорректный URL: $rawUrl"))
        val cap = maxChars.coerceIn(MIN_CAP, MAX_CAP)

        if (renderMode == RenderMode.ALWAYS) {
            val renderer = render
                ?: return Result.failure(IllegalStateException("WebView-рендер недоступен на устройстве"))
            return renderTier(url, mode, cap, loadImages, renderer)
        }

        val raw = content.fetchStatic(url).getOrElse { return Result.failure(it) }
        val type = raw.contentType?.lowercase() ?: ""
        if (type.contains("markdown") || (type.contains("plain") && !type.contains("html"))) {
            return Result.success(
                FetchedResult(
                    finalUrl = raw.finalUrl,
                    title = null,
                    markdown = capText(raw.body, cap).markdown,
                    tier = FetchTier.STATIC,
                    truncated = raw.bodyTruncated || raw.body.length > cap,
                    totalChars = raw.body.length,
                ),
            )
        }

        val article = extractArticle(raw.finalUrl, raw.body)
        val articleTextLength = article?.let {
            (it.title.orEmpty().length + it.excerpt.orEmpty().length + it.contentHtml.length)
        } ?: 0
        if (article != null && articleTextLength >= WebRenderActions.MIN_STATIC_CHARS) {
            return Result.success(renderResult(raw.finalUrl, article, mode, cap, FetchTier.STATIC, raw.bodyTruncated))
        }

        // Статика бедная: пробуем Markdown-вариант (дешёвый tier для док-сайтов).
        content.fetchMarkdownVariant(raw.finalUrl).getOrNull()?.let { md ->
            val capped = capText(md.body, cap)
            return Result.success(
                FetchedResult(md.finalUrl, null, capped.markdown, FetchTier.STATIC, md.bodyTruncated || capped.truncated, md.body.length),
            )
        }

        if (renderMode == RenderMode.NEVER || render == null) {
            val fallback = htmlToPlainText(raw.body, raw.finalUrl, cap)
            return Result.success(
                FetchedResult(raw.finalUrl, article?.title, fallback.markdown, FetchTier.STATIC, raw.bodyTruncated || fallback.truncated, fallback.totalChars),
            )
        }
        return renderTier(raw.finalUrl, mode, cap, loadImages, render)
    }

    private suspend fun renderTier(
        url: String,
        mode: FetchMode,
        cap: Int,
        loadImages: Boolean,
        renderer: WebRenderActions,
    ): Result<FetchedResult> {
        val rendered = renderer.render(url, loadImages).getOrElse { return Result.failure(it) }
        val article = extractArticle(rendered.finalUrl, rendered.html)
        if (article == null) {
            val fallback = htmlToPlainText(rendered.html, rendered.finalUrl, cap)
            return Result.success(
                FetchedResult(
                    rendered.finalUrl, rendered.title,
                    PARTIAL_RENDER_NOTE + fallback.markdown,
                    FetchTier.RENDERED, rendered.bodyTruncated || fallback.truncated, fallback.totalChars,
                ),
            )
        }
        return Result.success(renderResult(rendered.finalUrl, article, mode, cap, FetchTier.RENDERED, rendered.bodyTruncated))
    }

    private fun renderResult(
        finalUrl: String,
        article: ExtractedArticle,
        mode: FetchMode,
        cap: Int,
        tier: FetchTier,
        sourceTruncated: Boolean,
    ): FetchedResult {
        val effectiveCap = if (mode == FetchMode.GIST) minOf(cap, GIST_CAP) else cap
        val md = if (mode == FetchMode.GIST) {
            articleGist(article.title, article.excerpt, article.contentHtml, finalUrl, effectiveCap)
        } else {
            articleToMarkdown(article.title, article.contentHtml, finalUrl, effectiveCap)
        }
        return FetchedResult(finalUrl, article.title, md.markdown, tier, sourceTruncated || md.truncated, md.totalChars)
    }

    private fun capText(text: String, cap: Int): Capped {
        return if (text.length <= cap) {
            Capped(text, false)
        } else {
            Capped(text.take(cap).trimEnd() + "\n\n[обрезано, всего ${text.length} символов]", true)
        }
    }

    private data class Capped(val markdown: String, val truncated: Boolean)

    companion object {
        const val DEFAULT_FULL_CHARS = 12_000
        private const val GIST_CAP = 2_000
        private const val MIN_CAP = 500
        private const val MAX_CAP = 50_000
        private const val PARTIAL_RENDER_NOTE =
            "[рендер частичный: страница, вероятно, требует входа или сложного JS]\n\n"
    }
}
