package ru.outsideproarts.oassist.core.data.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ProviderProfile
import ru.outsideproarts.oassist.core.model.Time

/**
 * Репозиторий провайдеров/режимов: конфиг + секреты через [SecretStore].
 * Секреты адресуются alias-схемой (см. [aliasForApiKey] и др.), plaintext живёт только
 * внутри вызовов set/put и никогда не возвращается наружу.
 */
class ProviderRepository(
    private val store: ProviderConfigStore,
    private val secrets: SecretStore,
) {

    fun observeProviders(): Flow<List<ProviderProfile>> = store.providerProfiles

    fun observeModeConfigs(): Flow<List<ModeConfig>> = store.modeConfigs

    fun observeModelCatalog(providerId: String): Flow<ModelCatalog?> = store.modelCatalog(providerId)

    suspend fun upsertProvider(profile: ProviderProfile) = store.upsertProviderProfile(profile)

    suspend fun getProvider(id: String): ProviderProfile? =
        store.providerProfiles.first().firstOrNull { it.id == id }

    /** Удаляет профиль вместе со всеми его секретами. */
    suspend fun deleteProvider(id: String) {
        val profile = getProvider(id)
        profile?.let { p ->
            p.apiKeyRef?.let { secrets.delete(it) }
            p.extraHeaders.values.forEach { secrets.delete(it) }
        }
        secrets.delete(aliasForApiKey(id))
        // Legacy purge: Basic-алиасы версий до удаления Basic Auth (M3.4b).
        secrets.delete(aliasForBasicUser(id))
        secrets.delete(aliasForBasicPassword(id))
        store.deleteProviderProfile(id)
    }

    suspend fun hasApiKey(providerId: String): Boolean =
        secrets.contains(aliasForApiKey(providerId))

    suspend fun setApiKey(providerId: String, value: CharArray) {
        secrets.put(aliasForApiKey(providerId), value)
        updateProvider(providerId) { it.copy(apiKeyRef = aliasForApiKey(providerId)) }
    }

    suspend fun clearApiKey(providerId: String) {
        secrets.delete(aliasForApiKey(providerId))
        updateProvider(providerId) { it.copy(apiKeyRef = null) }
    }

    /**
     * Сохраняет значение extra-заголовка как секрет. Имя заголовка в профиле — имя,
     * значение — alias в [ProviderProfile.extraHeaders].
     */
    suspend fun setExtraHeader(providerId: String, headerName: String, value: CharArray) {
        val alias = aliasForExtraHeader(providerId, headerName)
        secrets.put(alias, value)
        updateProvider(providerId) {
            it.copy(extraHeaders = it.extraHeaders + (headerName to alias))
        }
    }

    suspend fun clearExtraHeader(providerId: String, headerName: String) {
        val alias = aliasForExtraHeader(providerId, headerName)
        secrets.delete(alias)
        updateProvider(providerId) {
            it.copy(extraHeaders = it.extraHeaders - headerName)
        }
    }

    suspend fun setModeConfig(config: ModeConfig) = store.setModeConfig(config)

    suspend fun modeConfig(mode: AssistMode): ModeConfig =
        store.modeConfigs.first().firstOrNull { it.mode == mode } ?: ModeConfig.default(mode)

    suspend fun saveModelCatalog(providerId: String, models: List<ModelInfo>) {
        store.saveModelCatalog(
            ModelCatalog(
                providerId = providerId,
                updatedAtEpochMs = Time.nowEpochMs(),
                models = models,
            ),
        )
    }

    /**
     * Точечное обновление capabilities одной модели (проба/ручная правка).
     * Модели вне каталога (ручной ввод) — добавляются новой записью.
     */
    suspend fun updateModelCapabilities(
        providerId: String,
        modelId: String,
        capabilities: Set<ModelCapability>,
    ) {
        val current = store.modelCatalog(providerId).first()
        store.saveModelCatalog(
            patchModelCapabilities(current, providerId, modelId, capabilities, Time.nowEpochMs()),
        )
    }

    private suspend fun updateProvider(id: String, transform: (ProviderProfile) -> ProviderProfile) {
        getProvider(id)?.let { store.upsertProviderProfile(transform(it)) }
    }

    companion object {
        fun aliasForApiKey(providerId: String): String = "provider.$providerId.apiKey"
        fun aliasForBasicUser(providerId: String): String = "provider.$providerId.basicUser"
        fun aliasForBasicPassword(providerId: String): String = "provider.$providerId.basicPassword"
        fun aliasForExtraHeader(providerId: String, headerName: String): String =
            "provider.$providerId.header.${headerName.lowercase().replace(Regex("[^a-z0-9-]"), "_")}"
    }
}

/**
 * Чистый патч каталога: обновить capabilities модели или добавить запись,
 * если модели в каталоге нет (ручной ввод). Метка времени — снаружи.
 */
internal fun patchModelCapabilities(
    current: ModelCatalog?,
    providerId: String,
    modelId: String,
    capabilities: Set<ModelCapability>,
    nowEpochMs: Long,
): ModelCatalog {
    val models = current?.models.orEmpty()
    val updated = if (models.any { it.id == modelId }) {
        models.map { if (it.id == modelId) it.copy(capabilities = capabilities) else it }
    } else {
        models + ModelInfo(id = modelId, capabilities = capabilities)
    }
    return ModelCatalog(providerId = providerId, updatedAtEpochMs = nowEpochMs, models = updated)
}
