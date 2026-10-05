package ru.outsideproarts.oassist.core.model

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: OAssistError) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun <T> failure(error: OAssistError): Outcome<T> = Failure(error)
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (OAssistError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (t: Throwable) {
    Outcome.Failure(t.toOAssistError())
}
