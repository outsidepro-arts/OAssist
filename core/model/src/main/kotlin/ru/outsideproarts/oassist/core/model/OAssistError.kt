package ru.outsideproarts.oassist.core.model

sealed class OAssistError(
    open val code: String,
    open val message: String,
    open val cause: Throwable? = null,
) {
    data class Network(
        override val code: String = "network",
        override val message: String,
        override val cause: Throwable? = null,
    ) : OAssistError(code, message, cause)

    data class Http(
        val status: Int,
        override val code: String = "http",
        override val message: String,
        override val cause: Throwable? = null,
    ) : OAssistError(code, message, cause)

    data class Provider(
        override val code: String = "provider",
        override val message: String,
        override val cause: Throwable? = null,
    ) : OAssistError(code, message, cause)

    data class Capability(
        val capability: Capability,
        override val code: String = "capability",
        override val message: String,
    ) : OAssistError(code, message)

    data class Validation(
        override val code: String = "validation",
        override val message: String,
    ) : OAssistError(code, message)

    data class Storage(
        override val code: String = "storage",
        override val message: String,
        override val cause: Throwable? = null,
    ) : OAssistError(code, message, cause)

    data class Cancelled(
        override val code: String = "cancelled",
        override val message: String = "Отменено",
    ) : OAssistError(code, message)

    data class Unknown(
        override val code: String = "unknown",
        override val message: String,
        override val cause: Throwable? = null,
    ) : OAssistError(code, message, cause)
}

enum class Capability {
    NETWORK,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    SHIZUKU,
    ROOT,
    RECORD_AUDIO,
    OVERLAY,
    ASSISTANT_ROLE,
}

fun Throwable.toOAssistError(): OAssistError = when (this) {
    is java.util.concurrent.CancellationException -> OAssistError.Cancelled()
    is java.io.IOException -> OAssistError.Network(message = message ?: "Сетевая ошибка", cause = this)
    else -> OAssistError.Unknown(message = message ?: javaClass.simpleName, cause = this)
}
