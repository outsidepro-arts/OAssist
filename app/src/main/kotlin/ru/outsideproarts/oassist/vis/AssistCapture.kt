package ru.outsideproarts.oassist.vis

/** Collects at most one show request; framework callbacks carry no show identifier. */
internal class AssistCapture<T>(private val complete: (String?, T?) -> Unit) {
    private var started = false
    private var terminal = false
    private var awaitingContext = false
    private var awaitingScreenshot = false
    private var context: String? = null
    private var screenshot: T? = null

    val acceptsContext: Boolean get() = started && !terminal && awaitingContext
    val acceptsScreenshot: Boolean get() = started && !terminal && awaitingScreenshot

    fun start(
        expectContext: Boolean,
        expectScreenshot: Boolean,
    ): Boolean {
        if (started || terminal) {
            cancel()
            return false
        }
        started = true
        awaitingContext = expectContext
        awaitingScreenshot = expectScreenshot
        finishIfReady()
        return true
    }

    fun context(value: String?) {
        if (!started || terminal || !awaitingContext) return
        context = value
        awaitingContext = false
        finishIfReady()
    }

    fun screenshot(value: T?) {
        if (!started || terminal || !awaitingScreenshot) return
        screenshot = value
        awaitingScreenshot = false
        finishIfReady()
    }

    fun timeout() {
        if (!started || terminal) return
        finish()
    }

    fun cancel() {
        terminal = true
        context = null
        screenshot = null
    }

    private fun finishIfReady() {
        if (!awaitingContext && !awaitingScreenshot) finish()
    }

    private fun finish() {
        terminal = true
        val capturedContext = context
        val capturedScreenshot = screenshot
        context = null
        screenshot = null
        complete(capturedContext, capturedScreenshot)
    }
}
