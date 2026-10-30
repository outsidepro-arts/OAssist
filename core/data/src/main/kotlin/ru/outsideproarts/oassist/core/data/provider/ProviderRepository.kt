package ru.outsideproarts.oassist.core.data.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.ModelProtocol
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
        // Страховка удаления: Basic-алиасы могли остаться от прошлых версий
        // и легаси-конфигов без текущих ссылок в профиле.
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
     * Сохраняет пару Basic-учётных данных ([ru.outsideproarts.oassist.core.model.AuthScheme.BASIC])
     * как секреты: логин и пароль пишутся только в [SecretStore].
     */
    suspend fun setBasicCredentials(providerId: String, user: CharArray, password: CharArray) {
        secrets.put(aliasForBasicUser(providerId), user)
        secrets.put(aliasForBasicPassword(providerId), password)
        updateProvider(providerId) {
            it.copy(
                basicUserRef = aliasForBasicUser(providerId),
                basicPasswordRef = aliasForBasicPassword(providerId),
            )
        }
    }

    /** Удаляет сохранённую пару Basic-учётных данных. */
    suspend fun clearBasicCredentials(providerId: String) {
        secrets.delete(aliasForBasicUser(providerId))
        secrets.delete(aliasForBasicPassword(providerId))
        updateProvider(providerId) { it.copy(basicUserRef = null, basicPasswordRef = null) }
    }

    /**
     * Сохраняет значение extra-заголовка как секрет. Имя заголовка в профиле — имя,
     * значение — alias в [ProviderProfile.extraHeaders].
     */
    suspend fun setExtraHeader(providerId: String, headerName: String, value: CharArray) {
        val headers = getProvider(providerId)?.extraHeaders.orEmpty()
        val existingAlias = headers[headerName]?.takeUnless { alias ->
            headers.any { (name, ref) -> name != headerName && ref == alias }
        }
        val baseAlias = aliasForExtraHeader(providerId, headerName)
        var alias = existingAlias ?: baseAlias
        var suffix = 0
        while (headers.any { (name, ref) -> name != headerName && ref == alias }) {
            alias = "$baseAlias.${++suffix}"
        }
        secrets.put(alias, value)
        updateProvider(providerId) {
            it.copy(extraHeaders = it.extraHeaders + (headerName to alias))
        }
    }

    suspend fun clearExtraHeader(providerId: String, headerName: String) {
        val headers = getProvider(providerId)?.extraHeaders.orEmpty()
        val alias = headers[headerName] ?: return
        // Older profiles can share a lossy alias; keep it while another header uses it.
        if (headers.none { (name, ref) -> name != headerName && ref == alias }) {
            secrets.delete(alias)
        }
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
     * Точечное обновление capabilities модели (проба/ручная правка).
     * Модели ещё не было (ручной ввод) - добавляется. Отметка времени - новая.
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

    /**
     * Ручной override проводного протокола модели (для агрегаторов);
     * null - вернуть авто-резолв.
     */
    suspend fun updateModelProtocol(
        providerId: String,
        modelId: String,
        protocol: ModelProtocol?,
    ) {
        val current = store.modelCatalog(providerId).first()
        store.saveModelCatalog(
            patchModelProtocol(current, providerId, modelId, protocol, Time.nowEpochMs()),
        )
    }

    /**
     * Свежий список с провайдера: merge с правками пользователя
     * (capabilities/protocol/label не теряются при «Обновить список»).
     */
    suspend fun refreshModelCatalog(providerId: String, fetched: List<ModelInfo>) {
        val current = store.modelCatalog(providerId).first()
        store.saveModelCatalog(
            mergeModelCatalog(current, providerId, fetched, Time.nowEpochMs()),
        )
    }

    private suspend fun updateProvider(id: String, transform: (ProviderProfile) -> ProviderProfile) {
        getProvider(id)?.let { store.upsertProviderProfile(transform(it)) }
    }

    companion object {
        fun aliasForApiKey(providerId: String): String = "provider.$providerId.apiKey"
        fun aliasForBasicUser(providerId: String): String = "provider.$providerId.basicUser"
        fun aliasForBasicPassword(providerId: String): String = "provider.$providerId.basicPassword"
        fun aliasForExtraHeader(providerId: String, headerName: String): String {
            val encodedName = buildString {
                headerName.lowercase().forEach { char ->
                    if (char in 'a'..'z' || char in '0'..'9' || char == '-') append(char)
                    else append('_').append(char.code.toString(16).padStart(4, '0'))
                }
            }
            return "provider.$providerId.header.$encodedName"
        }
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

/**
 * Точечный override проводного протокола модели (null = авто-резолв):
 * как [patchModelCapabilities], но поле protocol.
 */
internal fun patchModelProtocol(
    current: ModelCatalog?,
    providerId: String,
    modelId: String,
    protocol: ModelProtocol?,
    nowEpochMs: Long,
): ModelCatalog {
    val models = current?.models.orEmpty()
    val updated = if (models.any { it.id == modelId }) {
        models.map { if (it.id == modelId) it.copy(protocol = protocol) else it }
    } else {
        models + ModelInfo(id = modelId, protocol = protocol)
    }
    return ModelCatalog(providerId = providerId, updatedAtEpochMs = nowEpochMs, models = updated)
}

/**
 * Merge свежего списка с провайдера с правками пользователя: capabilities
 * (проба/ручные), protocol (override) и label переживают обновление каталога.
 */
internal fun mergeModelCatalog(
    current: ModelCatalog?,
    providerId: String,
    fetched: List<ModelInfo>,
    nowEpochMs: Long,
): ModelCatalog {
    val existing = current?.models.orEmpty().associateBy { it.id }
    val merged = fetched.map { fresh ->
        val old = existing[fresh.id] ?: return@map fresh
        fresh.copy(
            label = fresh.label ?: old.label,
            capabilities = old.capabilities.ifEmpty { fresh.capabilities },
            protocol = old.protocol ?: fresh.protocol,
        )
    }
    return ModelCatalog(providerId = providerId, updatedAtEpochMs = nowEpochMs, models = merged)
}
