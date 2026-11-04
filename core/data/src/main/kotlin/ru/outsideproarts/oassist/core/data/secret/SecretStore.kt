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
    /** Both values commit together; null removes a value. Inputs are consumed and cleared. */
    suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?)
    /** Read both values from the same storage snapshot, including legacy separate aliases. */
    suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?>
    suspend fun contains(alias: String): Boolean
    suspend fun delete(alias: String)
    suspend fun clearAll()
    suspend fun aliases(): List<String>
}
