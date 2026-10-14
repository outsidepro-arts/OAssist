package ru.outsideproarts.oassist.core.provider.modelsdev

import kotlinx.serialization.Serializable

/**
 * Извлечённый срез каталога models.dev для одного провайдера: маршрутизация
 * протокола по `provider.npm` + читаемые имена моделей.
 */
@Serializable
data class ModelsDevSnapshot(
    val updatedAtEpochMs: Long = 0L,
    /** id модели → `provider.npm` (AI SDK-пакет, см. `ProtocolResolution.npmToProtocol`). */
    val npmByModelId: Map<String, String> = emptyMap(),
    /** id модели → человекочитаемое имя (для label в пикере). */
    val nameByModelId: Map<String, String> = emptyMap(),
)

/** Персистентный кэш срезов models.dev по ключу провайдера (напр. `opencode`). */
interface ModelsDevCache {
    suspend fun read(providerKey: String): ModelsDevSnapshot?
    suspend fun write(providerKey: String, snapshot: ModelsDevSnapshot)
}
