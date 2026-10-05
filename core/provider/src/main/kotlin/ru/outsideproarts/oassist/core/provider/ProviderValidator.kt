package ru.outsideproarts.oassist.core.provider

import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderProfile

/** Результат проверки параметров провайдера перед сохранением. */
sealed interface ProviderValidationResult {
    /** Проба успешна; [models] — полученный каталог (можно закэшировать). */
    data class Success(val models: List<ModelInfo>) : ProviderValidationResult

    /** Заглушка: для этого типа проверка появится позже — сохраняем без пробы. */
    data object Skipped : ProviderValidationResult

    /** Ошибка: сохранять нельзя, текст — [ProviderError.userMessage]. */
    data class Failure(val error: ProviderError) : ProviderValidationResult
}

/**
 * Проверка параметров провайдера перед сохранением: локальные поля, затем реальный
 * запрос по параметрам профиля (fetchModels с подстановкой несохранённых секретов).
 *
 * [probeTimeoutMs] — таймаут пробы; null отключает (используется в тестах).
 */
class ProviderValidator(
    private val secretStore: SecretStore,
    private val httpClient: HttpClient,
    private val probeTimeoutMs: Long? = DEFAULT_PROBE_TIMEOUT_MS,
) {

    suspend fun validate(
        profile: ProviderProfile,
        overrides: ProviderAuth? = null,
    ): ProviderValidationResult = try {
        validateInner(profile, overrides)
    } finally {
        overrides?.zeroOut()
    }

    private suspend fun validateInner(
        profile: ProviderProfile,
        overrides: ProviderAuth?,
    ): ProviderValidationResult {
        if (profile.name.isBlank()) {
            return ProviderValidationResult.Failure(
                ProviderError.InvalidInput(ProviderError.InvalidInput.Reason.NAME_REQUIRED),
            )
        }
        if (profile.baseUrl.isBlank()) {
            return ProviderValidationResult.Failure(
                ProviderError.InvalidInput(ProviderError.InvalidInput.Reason.BASE_URL_REQUIRED),
            )
        }
        if (!isAcceptableBaseUrl(profile.baseUrl)) {
            return ProviderValidationResult.Failure(
                ProviderError.InvalidInput(ProviderError.InvalidInput.Reason.BASE_URL_INVALID),
            )
        }
        return try {
            val client = ModelClient.create(profile, secretStore, httpClient, overrides)
            val models = if (probeTimeoutMs != null) {
                withTimeoutOrNull(probeTimeoutMs) { client.fetchModels() }
                    ?: return ProviderValidationResult.Failure(ProviderError.Network("timeout"))
            } else {
                client.fetchModels()
            }
            ProviderValidationResult.Success(models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ProviderValidationResult.Failure(ProviderError.from(e))
        }
    }

    private fun isAcceptableBaseUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (trimmed.any { it.isWhitespace() }) return false
        val url = runCatching { Url(trimmed) }.getOrNull() ?: return false
        return url.host.isNotBlank()
    }

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS = 20_000L
    }
}
