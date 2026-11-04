package ru.outsideproarts.oassist.core.data.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.AssistMode
import ru.outsideproarts.oassist.core.model.ModeConfig
import ru.outsideproarts.oassist.core.model.ModelCapability
import ru.outsideproarts.oassist.core.model.ModelCatalog
import ru.outsideproarts.oassist.core.model.ModelInfo
import ru.outsideproarts.oassist.core.model.catalogFingerprint
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

    suspend fun modelsForSelection(providerId: String): List<ModelInfo> = store.modelsForSelection(providerId).first()

    suspend fun modelInfo(profile: ProviderProfile, modelId: String): ModelInfo? = store.modelInfo(profile, modelId).first()

    suspend fun upsertProvider(profile: ProviderProfile, token: CredentialUpdateToken? = null) = store.upsertChecked(profile, token)

    suspend fun getProvider(id: String): ProviderProfile? =
        store.providerProfiles.first().firstOrNull { it.id == id }

    /** Copy only this revision's local aliases; network work runs after releasing the lock. */
    suspend fun <T> withCredentialSnapshot(id: String, revision: String?, expectedExists: Boolean = true, action: suspend (SecretStore) -> T): T {
        val snapshot = CredentialSnapshot()
        try {
            store.readCredentials(id, revision, expectedExists) { profile ->
                val aliases = profile?.let {
                    listOfNotNull(it.apiKeyRef, it.basicUserRef, it.basicPasswordRef) + it.extraHeaders.values
                }.orEmpty()
                aliases.distinct().forEach { alias -> secrets.get(alias)?.let { snapshot.values[alias] = it } }
            }
            return action(snapshot)
        } finally {
            snapshot.values.values.forEach { it.fill('\u0000') }
            snapshot.values.clear()
        }
    }

    /** Удаляет профиль вместе со всеми его секретами. */
    suspend fun deleteProvider(id: String) = store.withCredentialMutation(id) {
        deleteSecrets(id, getProvider(id))
        store.deleteProviderProfile(id)
    }

    /** Clears credentials using only the current local profile, before backup replacement. */
    suspend fun clearSecrets(id: String) = store.withCredentialMutation(id) {
        val profile = getProvider(id)
        deleteSecrets(id, profile)
        updateProvider(id) {
            it.copy(apiKeyRef = null, basicUserRef = null, basicPasswordRef = null, extraHeaders = emptyMap())
        }
    }

    internal suspend fun replaceFromBackup(
        profiles: List<ProviderProfile>, modes: List<ModeConfig>, catalogs: List<ModelCatalog>,
    ) = store.replaceFromBackup(profiles, modes, catalogs) { deleteSecrets(it.id, it) }

    private suspend fun deleteSecrets(id: String, profile: ProviderProfile?) {
        profile?.let { p ->
            p.apiKeyRef?.let { secrets.delete(it) }
            p.basicUserRef?.let { secrets.delete(it) }
            p.basicPasswordRef?.let { secrets.delete(it) }
            p.extraHeaders.values.forEach { secrets.delete(it) }
        }
        secrets.delete(aliasForApiKey(id))
        // Страховка удаления: Basic-алиасы могли остаться от прошлых версий
        // и легаси-конфигов без текущих ссылок в профиле.
        secrets.delete(aliasForBasicUser(id))
        secrets.delete(aliasForBasicPassword(id))
    }

    suspend fun hasApiKey(providerId: String): Boolean =
        secrets.contains(aliasForApiKey(providerId))

    suspend fun setApiKey(providerId: String, value: CharArray, token: CredentialUpdateToken? = null) = store.withCredentialMutation(providerId, token) {
        secrets.put(aliasForApiKey(providerId), value)
        updateProvider(providerId) { it.copy(apiKeyRef = aliasForApiKey(providerId)) }
    }

    suspend fun clearApiKey(providerId: String, token: CredentialUpdateToken? = null) = store.withCredentialMutation(providerId, token) {
        secrets.delete(aliasForApiKey(providerId))
        updateProvider(providerId) { it.copy(apiKeyRef = null) }
    }

    /**
     * Сохраняет пару Basic-учётных данных ([ru.outsideproarts.oassist.core.model.AuthScheme.BASIC])
     * как секреты: логин и пароль пишутся только в [SecretStore].
     * Возвращает старые aliases для отслеживаемого владельцем сохранения cleanup после repair.
     */
    suspend fun setBasicCredentials(providerId: String, user: CharArray, password: CharArray, token: CredentialUpdateToken? = null): Set<String> {
        try {
            return store.withCredentialMutation(providerId, token) {
                val profile = requireNotNull(getProvider(providerId)) { "Provider does not exist" }
                val partial = (profile.basicUserRef != null || profile.basicPasswordRef != null) &&
                    (profile.basicUserRef == null || profile.basicPasswordRef == null ||
                        profile.basicUserRef == profile.basicPasswordRef)
                if (partial) {
                    return@withCredentialMutation repairBasicCredentials(profile, user, password)
                }
                val userAlias = profile.basicUserRef ?: aliasForBasicUser(providerId)
                val passwordAlias = profile.basicPasswordRef ?: aliasForBasicPassword(providerId)
                secrets.putPair(userAlias, user, passwordAlias, password)
                if (profile.basicUserRef != userAlias || profile.basicPasswordRef != passwordAlias) {
                    updateProvider(providerId) {
                        it.copy(basicUserRef = userAlias, basicPasswordRef = passwordAlias)
                    }
                }
                emptySet()
            }
        } finally {
            user.fill('\u0000')
            password.fill('\u0000')
        }
    }

    // A damaged profile has no usable pair. Stage new aliases so a failed publication
    // cannot overwrite its remaining secret. The persisted reference format is unchanged.
    private suspend fun repairBasicCredentials(profile: ProviderProfile, user: CharArray, password: CharArray): Set<String> {
        val generation = UUID.randomUUID().toString()
        val userAlias = "${aliasForBasicUser(profile.id)}.$generation"
        val passwordAlias = "${aliasForBasicPassword(profile.id)}.$generation"
        try {
            secrets.putPair(userAlias, user, passwordAlias, password)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val current = requireNotNull(getProvider(profile.id)) { "Provider does not exist" }
                store.upsertProviderProfile(current.copy(basicUserRef = userAlias, basicPasswordRef = passwordAlias))
            }
        } catch (error: Throwable) {
            try {
                withContext(NonCancellable) {
                    val current = getProvider(profile.id)
                    // Never remove a pair if publication committed before reporting an error.
                    if (current?.basicUserRef != userAlias && current?.basicPasswordRef != passwordAlias) {
                        secrets.putPair(userAlias, null, passwordAlias, null)
                    }
                }
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        // The save owner tracks cleanup independently of the committed credentials.
        return setOfNotNull(profile.basicUserRef, profile.basicPasswordRef,
            aliasForBasicUser(profile.id), aliasForBasicPassword(profile.id))
    }

    /** Удаляет сохранённую пару Basic-учётных данных. */
    suspend fun clearBasicCredentials(providerId: String, token: CredentialUpdateToken? = null) = store.withCredentialMutation(providerId, token) {
        val profile = getProvider(providerId) ?: return@withCredentialMutation
        val userAlias = profile.basicUserRef ?: aliasForBasicUser(providerId)
        val passwordAlias = profile.basicPasswordRef ?: aliasForBasicPassword(providerId)
        if (userAlias == passwordAlias) secrets.delete(userAlias)
        else secrets.putPair(userAlias, null, passwordAlias, null)
        updateProvider(providerId) { it.copy(basicUserRef = null, basicPasswordRef = null) }
    }

    /**
     * Сохраняет значение extra-заголовка как секрет. Имя заголовка в профиле — имя,
     * значение — alias в [ProviderProfile.extraHeaders].
     */
    suspend fun setExtraHeader(providerId: String, headerName: String, value: CharArray, token: CredentialUpdateToken? = null) = store.withCredentialMutation(providerId, token) {
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

    suspend fun clearExtraHeader(providerId: String, headerName: String) = store.withCredentialMutation(providerId) {
        val headers = getProvider(providerId)?.extraHeaders.orEmpty()
        val alias = headers[headerName] ?: return@withCredentialMutation
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
    suspend fun refreshModelCatalog(profile: ProviderProfile, fetched: List<ModelInfo>, rejectStale: Boolean = false) {
        val current = store.modelCatalog(profile.id).first()
            ?.takeIf { it.sourceFingerprint == null || it.sourceFingerprint == profile.catalogFingerprint() }
        store.saveModelCatalog(
            mergeModelCatalog(current, profile.id, fetched, Time.nowEpochMs())
                .copy(sourceFingerprint = profile.catalogFingerprint()),
            expectedProfile = profile,
            rejectStale = rejectStale,
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

/** Short-lived read-only credential source for a single validation request. */
private class CredentialSnapshot : SecretStore {
    val values = mutableMapOf<String, CharArray>()
    override suspend fun get(alias: String): CharArray? = values[alias]?.copyOf()
    override suspend fun getPair(firstAlias: String?, secondAlias: String?) =
        firstAlias?.let { get(it) } to secondAlias?.let { get(it) }
    override suspend fun contains(alias: String) = alias in values
    override suspend fun aliases() = values.keys.toList()
    override suspend fun put(alias: String, plaintext: CharArray): Unit = error("Read-only credential snapshot")
    override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?): Unit = error("Read-only credential snapshot")
    override suspend fun delete(alias: String): Unit = error("Read-only credential snapshot")
    override suspend fun clearAll(): Unit = error("Read-only credential snapshot")
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
    return ModelCatalog(providerId = providerId, updatedAtEpochMs = nowEpochMs, models = updated, sourceFingerprint = current?.sourceFingerprint)
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
    return ModelCatalog(providerId = providerId, updatedAtEpochMs = nowEpochMs, models = updated, sourceFingerprint = current?.sourceFingerprint)
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
