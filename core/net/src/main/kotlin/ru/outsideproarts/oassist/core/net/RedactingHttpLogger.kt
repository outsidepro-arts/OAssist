package ru.outsideproarts.oassist.core.net

import io.ktor.client.plugins.logging.Logger
import ru.outsideproarts.oassist.core.model.SecretRedactor

/**
 * Ktor [Logger], прогоняющий сообщения через [SecretRedactor].
 * Секреты (bearer, sk-, API key=...) и зарегистрированные alias заменяются на `••••`.
 */
class RedactingHttpLogger : Logger {
    override fun log(message: String) {
        val redacted = SecretRedactor.redact(message)
        android.util.Log.d("OAssistHttp", redacted)
    }
}