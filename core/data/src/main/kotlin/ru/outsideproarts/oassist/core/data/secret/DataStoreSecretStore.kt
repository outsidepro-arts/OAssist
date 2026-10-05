package ru.outsideproarts.oassist.core.data.secret

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.core.model.Outcome
import ru.outsideproarts.oassist.core.model.SecretRedactor

private val Context.secretDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Шифротексты секретов в DataStore, ключ — Android Keystore.
 * DataStore лежит в device-protected storage (см. AppGraph), чтобы
 * ассистент работал с экрана блокировки.
 */
class DataStoreSecretStore(
    private val context: Context,
    private val aead: KeystoreAead,
) : SecretStore {

    override suspend fun put(alias: String, plaintext: CharArray) = withContext(Dispatchers.Default) {
        require(alias.isNotBlank()) { "alias не может быть пустым" }
        val bytes = String(plaintext).toByteArray(Charsets.UTF_8)
        plaintext.fill('\u0000')
        val encrypted = when (val result = aead.encrypt(bytes)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                bytes.fill(0)
                OAssistLog.error(TAG, null, "Не удалось сохранить секрет: $alias — ${result.error.message}")
                throw IllegalStateException("Не удалось сохранить секрет")
            }
        }
        bytes.fill(0)
        context.secretDataStore.edit { prefs ->
            prefs[keyFor(alias)] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }
        SecretRedactor.registerSecretAlias(alias)
        OAssistLog.info(TAG, "Секрет сохранён: $alias")
    }

    override suspend fun get(alias: String): CharArray? = withContext(Dispatchers.Default) {
        val encoded = context.secretDataStore.data.first()[keyFor(alias)] ?: return@withContext null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        when (val decrypted = aead.decrypt(packed)) {
            is Outcome.Success -> String(decrypted.value, Charsets.UTF_8).toCharArray().also {
                decrypted.value.fill(0)
            }
            is Outcome.Failure -> {
                OAssistLog.error(TAG, null, "Не удалось расшифровать секрет: $alias")
                null
            }
        }
    }

    override suspend fun contains(alias: String): Boolean = withContext(Dispatchers.Default) {
        context.secretDataStore.data.first().contains(keyFor(alias))
    }

    override suspend fun delete(alias: String) = withContext(Dispatchers.Default) {
        context.secretDataStore.edit { it.remove(keyFor(alias)) }
        SecretRedactor.unregisterSecretAlias(alias)
        OAssistLog.info(TAG, "Секрет удалён: $alias")
    }

    override suspend fun clearAll() = withContext(Dispatchers.Default) {
        val keys = context.secretDataStore.data.first().asMap().keys.toList()
        context.secretDataStore.edit { prefs ->
            keys.forEach { prefs.remove(it) }
        }
        aead.destroyKey()
        OAssistLog.info(TAG, "Все секреты удалены, ключ пересоздан")
    }

    override suspend fun aliases(): List<String> = withContext(Dispatchers.Default) {
        context.secretDataStore.data.first().asMap().keys
            .filter { it.name.startsWith(KEY_PREFIX) }
            .map { it.name.removePrefix(KEY_PREFIX) }
            .sorted()
    }

    private fun keyFor(alias: String) = stringPreferencesKey(KEY_PREFIX + alias)

    companion object {
        private const val TAG = "DataStoreSecretStore"
        private const val KEY_PREFIX = "secret:"
    }
}
