package ru.outsideproarts.oassist.core.model

import android.util.Log

/**
 * Единая точка логирования. Не логирует секреты (см. [SecretRedactor]).
 */
object OAssistLog : RedactingLogger {
    @Volatile
    var minPriority: Int = Log.DEBUG

    @Volatile
    var sink: ((Int, String, String, Throwable?) -> Unit)? = null

    override fun verbose(tag: String, message: String, throwable: Throwable?) =
        log(Log.VERBOSE, tag, message, throwable)

    override fun debug(tag: String, message: String, throwable: Throwable?) =
        log(Log.DEBUG, tag, message, throwable)

    override fun info(tag: String, message: String, throwable: Throwable?) =
        log(Log.INFO, tag, message, throwable)

    override fun warn(tag: String, message: String, throwable: Throwable?) =
        log(Log.WARN, tag, message, throwable)

    override fun error(tag: String, message: Throwable?, messageText: String) {
        val text = messageText.ifBlank { message?.message ?: "error" }
        log(Log.ERROR, tag, text, message)
    }

    override fun registerSecretAlias(alias: String) = SecretRedactor.registerSecretAlias(alias)

    override fun unregisterSecretAlias(alias: String) = SecretRedactor.unregisterSecretAlias(alias)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (priority < minPriority) return
        val safe = SecretRedactor.redact(message)
        val safeTag = SecretRedactor.redact(tag)
        val safeThrowable = throwable?.let(::sanitizeLogThrowable)
        sink?.invoke(priority, safeTag, safe, safeThrowable)
        when (priority) {
            Log.VERBOSE -> Log.v(safeTag, safe, safeThrowable)
            Log.DEBUG -> Log.d(safeTag, safe, safeThrowable)
            Log.INFO -> Log.i(safeTag, safe, safeThrowable)
            Log.WARN -> Log.w(safeTag, safe, safeThrowable)
            Log.ERROR -> Log.e(safeTag, safe, safeThrowable)
            else -> Log.println(priority, safeTag, safe)
        }
    }
}

internal fun sanitizeLogThrowable(source: Throwable): Throwable =
    Throwable(source.javaClass.simpleName).apply { stackTrace = source.stackTrace }
