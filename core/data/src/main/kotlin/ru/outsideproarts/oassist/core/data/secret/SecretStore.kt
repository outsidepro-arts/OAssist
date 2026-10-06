package ru.outsideproarts.oassist.core.data.secret

/**
 * Хранилище секретов. Только через него проходят API-ключи, токены, пароли,
 * значения extraHeaders, Bearer MCP, ключи TTS/STT.
 *
 * Plaintext живёт только в рамках вызова и никогда не кэшируется,
 * не логируется и не переотображается в UI.
 */
interface SecretStore {
    suspend fun put(alias: String, plaintext: CharArray)
    suspend fun get(alias: String): CharArray?
    suspend fun contains(alias: String): Boolean
    suspend fun delete(alias: String)
    suspend fun clearAll()
    suspend fun aliases(): List<String>
}
