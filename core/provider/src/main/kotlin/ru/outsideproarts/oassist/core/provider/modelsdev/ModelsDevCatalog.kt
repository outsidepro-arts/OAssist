package ru.outsideproarts.oassist.core.provider.modelsdev

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.outsideproarts.oassist.core.provider.ProviderJson

/**
 * Каталог models.dev: маршрутизация протокола моделей агрегаторов. Живой
 * `/models` агрегаторов тип эндпоинта не отдаёт, а models.dev отдаёт его в
 * поле `provider.npm` каждой модели — но только общим бандлом `api.json`
 * (~5.6 МБ), поэтому срез извлекается один раз и кэшируется.
 *
 * Политика: свежий кэш (TTL) — без сети; протухший — один запрос с таймаутом,
 * при отказе — stale-кэш или null (дальше эвристика). Повторных попыток в
 * процессе после сбоя нет (backoff [FAILURE_BACKOFF_MS]).
 */
class ModelsDevCatalog(
    private val http: HttpClient,
    private val cache: ModelsDevCache,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val memory = mutableMapOf<String, ModelsDevSnapshot>()
    private var lastFailureAtMs: Long? = null

    /** `provider.npm` модели или null (нет в каталоге). */
    suspend fun npmFor(providerKey: String, modelId: String): String? =
        snapshotFor(providerKey)?.npmByModelId?.get(modelId)

    /** Имена моделей провайдера (label в пикере); пусто, если каталог недоступен. */
    suspend fun namesFor(providerKey: String): Map<String, String> =
        snapshotFor(providerKey)?.nameByModelId ?: emptyMap()

    suspend fun snapshotFor(providerKey: String): ModelsDevSnapshot? {
        memory[providerKey]?.let { return it }
        val cached = cache.read(providerKey)
        if (cached != null && now() - cached.updatedAtEpochMs < TTL_MS) {
            memory[providerKey] = cached
            return cached
        }
        val failedAt = lastFailureAtMs
        if (failedAt != null && now() - failedAt < FAILURE_BACKOFF_MS) {
            return cached?.also { memory[providerKey] = it }
        }
        val fetched = fetchSnapshot(providerKey)
        if (fetched == null) {
            lastFailureAtMs = now()
            return cached?.also { memory[providerKey] = it }
        }
        cache.write(providerKey, fetched)
        memory[providerKey] = fetched
        return fetched
    }

    private suspend fun fetchSnapshot(providerKey: String): ModelsDevSnapshot? {
        val body = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching { http.get(API_URL).bodyAsText() }.getOrNull()
        } ?: return null
        val (npm, names) = extractSlice(body, providerKey) ?: return null
        return ModelsDevSnapshot(updatedAtEpochMs = now(), npmByModelId = npm, nameByModelId = names)
    }

    companion object {
        const val API_URL = "https://models.dev/api.json"
        const val TTL_MS = 72 * 60 * 60 * 1000L
        const val FETCH_TIMEOUT_MS = 30_000L
        const val FAILURE_BACKOFF_MS = 6 * 60 * 60 * 1000L

        /**
         * Чистое извлечение среза провайдера из тела `api.json`:
         * `.<providerKey>.models.<id>.provider.npm` и `.name`. Кривой JSON — null.
         */
        internal fun extractSlice(body: String, providerKey: String): Pair<Map<String, String>, Map<String, String>>? {
            val root = runCatching { ProviderJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val models = root[providerKey]?.jsonObject?.get("models")?.jsonObject ?: return null
            val npm = mutableMapOf<String, String>()
            val names = mutableMapOf<String, String>()
            for ((id, value) in models) {
                val model = value as? JsonObject ?: continue
                val modelNpm = model["provider"]?.jsonObject?.get("npm")?.jsonPrimitive?.content
                if (!modelNpm.isNullOrBlank()) npm[id] = modelNpm
                val name = model["name"]?.jsonPrimitive?.content
                if (!name.isNullOrBlank()) names[id] = name
            }
            return npm to names
        }
    }
}

/** Файловый кэш срезов: `<dir>/<providerKey>.json` (device-protected storage). */
class FileModelsDevCache(private val dir: File) : ModelsDevCache {

    override suspend fun read(providerKey: String): ModelsDevSnapshot? {
        val file = fileFor(providerKey)
        if (!file.isFile) return null
        return runCatching {
            ProviderJson.decodeFromString(ModelsDevSnapshot.serializer(), file.readText())
        }.getOrNull()
    }

    override suspend fun write(providerKey: String, snapshot: ModelsDevSnapshot) {
        runCatching {
            dir.mkdirs()
            fileFor(providerKey).writeText(
                ProviderJson.encodeToString(ModelsDevSnapshot.serializer(), snapshot),
            )
        }
    }

    private fun fileFor(providerKey: String): File = File(dir, "$providerKey.json")
}
