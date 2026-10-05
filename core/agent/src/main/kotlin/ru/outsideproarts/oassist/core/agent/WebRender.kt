package ru.outsideproarts.oassist.core.agent

import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Отрендеренная страница: финализированный DOM после JS. */
data class RenderedPage(
    val title: String?,
    val finalUrl: String,
    val html: String,
)

/**
 * Тяжёлый tier чтения веба: рендер через встроенный WebView (системный движок),
 * изолированно от пользователя и от остального приложения.
 *
 * Изоляция: куки запрещены и вытираются перед каждой загрузкой
 * ([CookieManager] WebView не связан с браузером пользователя),
 * файловый доступ закрыт, JS-моста в приложение нет, после рендера —
 * `destroy()` и чистка хранилищ. Один рендер за раз ([mutex]).
 */
class WebRenderActions(private val appContext: Context) {

    private val mutex = Mutex()

    suspend fun render(url: String, loadImages: Boolean): Result<RenderedPage> {
        return mutex.withLock {
            val result = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                runCatching { doRender(url, loadImages).getOrThrow() }
            } ?: return Result.failure(
                java.util.concurrent.TimeoutException("Рендер превысил $RENDER_TIMEOUT_MS мс: $url"),
            )
            result
        }
    }

    private suspend fun doRender(url: String, loadImages: Boolean): Result<RenderedPage> {
        return withContext(Dispatchers.Main) {
            val webView = try {
                WebView(appContext)
            } catch (e: Throwable) {
                return@withContext Result.failure(
                    IllegalStateException("WebView-рендер недоступен на устройстве: ${e.message}"),
                )
            }
            try {
                wipeStorage()
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkImage = !loadImages
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                }
                // Оффскрин: к окну не привязываем, но размер задаём — иначе нет layout.
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

                val done = CompletableDeferred<Result<RenderedPage>>()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        // Не форсим повторные onPageFinished от iframe/редиректов.
                        if (done.isCompleted) return
                        view.postDelayed({
                            if (done.isCompleted) return@postDelayed
                            evaluateDom(view, finishedUrl, done)
                        }, SETTLE_MS)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            done.complete(
                                Result.failure(
                                    IllegalStateException(mapRenderError(error.description.toString())),
                                ),
                            )
                        }
                    }
                }
                webView.loadUrl(url)
                // done.await() cancellable: внешний withTimeoutOrNull снимет ожидание.
                done.await()
            } finally {
                webView.destroy()
            }
        }
    }

    private suspend fun wipeStorage() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(false)
        val gone = CompletableDeferred<Unit>()
        cookies.removeAllCookies { gone.complete(Unit) }
        // Куки вытираются асинхронно; не ждём дольше пары секунд — загрузка всё равно без кук.
        withTimeoutOrNull(WIPE_TIMEOUT_MS) { gone.await() }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    /**
     * Собрать title + HTML двумя evaluateJavascript. Оба колбэка асинхронны:
     * готовность — по счётчику, без корутин (мы и так на Main).
     * Если один колбэк не придёт — внешный таймаут снимет `done.await()`.
     */
    private fun evaluateDom(
        view: WebView,
        finishedUrl: String,
        done: CompletableDeferred<Result<RenderedPage>>,
    ) {
        var title: String? = null
        var html: String? = null
        var arrived = 0
        fun tryFinish() {
            if (++arrived < 2 || done.isCompleted) return
            if (html.isNullOrBlank()) {
                done.complete(Result.failure(IllegalStateException("Пустой DOM после рендера")))
            } else {
                done.complete(Result.success(RenderedPage(title, finishedUrl, html!!)))
            }
        }
        view.evaluateJavascript("(function(){return document.title})()") {
            title = decodeJsString(it)
            tryFinish()
        }
        view.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML.slice(0,$MAX_RENDER_CHARS)})()",
        ) {
            html = decodeJsString(it)
            tryFinish()
        }
    }

    companion object {
        const val RENDER_TIMEOUT_MS = 20_000L
        private const val SETTLE_MS = 1500L
        private const val WIPE_TIMEOUT_MS = 2000L
        private const val VIEWPORT_WIDTH = 1280
        private const val VIEWPORT_HEIGHT = 800
        private const val MAX_RENDER_CHARS = 500_000
        const val MIN_STATIC_CHARS = 500
    }
}

/** Декодировать JSON-строку из evaluateJavascript в текст. */
internal fun decodeJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching {
        Json.parseToJsonElement(raw).jsonPrimitive.content
    }.getOrNull()
}
