package ru.outsideproarts.oassist.core.data.secret

import android.content.Context
import java.util.Base64
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
class DataStoreSecretStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encrypt: (ByteArray) -> Outcome<ByteArray>,
    private val decrypt: (ByteArray) -> Outcome<ByteArray>,
    private val destroyKey: () -> Unit,
) : SecretStore {
    constructor(context: Context, aead: KeystoreAead) : this(
        context.secretDataStore, aead,
    )
    constructor(dataStore: DataStore<Preferences>, aead: KeystoreAead) : this(
        dataStore, aead::encrypt, aead::decrypt, aead::destroyKey,
    )

    override suspend fun put(alias: String, plaintext: CharArray) = withContext(Dispatchers.Default) {
        require(alias.isNotBlank()) { "alias не может быть пустым" }
        val bytes = String(plaintext).toByteArray(Charsets.UTF_8)
        plaintext.fill('\u0000')
        val encrypted = when (val result = encrypt(bytes)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                bytes.fill(0)
                OAssistLog.error(TAG, null, "Не удалось сохранить секрет: $alias — ${result.error.message}")
                throw IllegalStateException("Не удалось сохранить секрет")
            }
        }
        bytes.fill(0)
        dataStore.edit { prefs ->
            prefs[keyFor(alias)] = Base64.getEncoder().encodeToString(encrypted)
        }
        SecretRedactor.registerSecretAlias(alias)
        OAssistLog.info(TAG, "Секрет сохранён: $alias")
    }

    override suspend fun get(alias: String): CharArray? = withContext(Dispatchers.Default) {
        val encoded = dataStore.data.first()[keyFor(alias)] ?: return@withContext null
        val packed = Base64.getDecoder().decode(encoded)
        when (val decrypted = decrypt(packed)) {
            is Outcome.Success -> String(decrypted.value, Charsets.UTF_8).toCharArray().also {
                decrypted.value.fill(0)
            }
            is Outcome.Failure -> {
                OAssistLog.error(TAG, null, "Не удалось расшифровать секрет: $alias")
                null
            }
        }
    }

    override suspend fun putPair(
        firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?,
    ) {
        try {
            require(firstAlias.isNotBlank() && secondAlias.isNotBlank() && firstAlias != secondAlias)
            withContext(Dispatchers.Default) {
                // Finish both encryptions before the single DataStore commit.
                val firstEncoded = first?.let(::encode)
                val secondEncoded = second?.let(::encode)
                dataStore.edit { prefs ->
                    if (firstEncoded == null) prefs.remove(keyFor(firstAlias))
                    else prefs[keyFor(firstAlias)] = firstEncoded
                    if (secondEncoded == null) prefs.remove(keyFor(secondAlias))
                    else prefs[keyFor(secondAlias)] = secondEncoded
                }
                if (first == null) SecretRedactor.unregisterSecretAlias(firstAlias)
                else SecretRedactor.registerSecretAlias(firstAlias)
                if (second == null) SecretRedactor.unregisterSecretAlias(secondAlias)
                else SecretRedactor.registerSecretAlias(secondAlias)
            }
        } finally {
            first?.fill('\u0000')
            second?.fill('\u0000')
        }
    }

    override suspend fun getPair(firstAlias: String?, secondAlias: String?): Pair<CharArray?, CharArray?> =
        withContext(Dispatchers.Default) {
            val snapshot = dataStore.data.first()
            val first = firstAlias?.let { snapshot[keyFor(it)] }?.let(::decode)
            try {
                first to secondAlias?.let { snapshot[keyFor(it)] }?.let(::decode)
            } catch (error: Throwable) {
                first?.fill('\u0000')
                throw error
            }
        }

    private fun encode(value: CharArray): String {
        val bytes = String(value).toByteArray(Charsets.UTF_8)
        return try {
            when (val result = encrypt(bytes)) {
                is Outcome.Success -> Base64.getEncoder().encodeToString(result.value)
                is Outcome.Failure -> throw IllegalStateException("Не удалось сохранить секрет")
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(encoded: String): CharArray? =
        when (val result = decrypt(Base64.getDecoder().decode(encoded))) {
            is Outcome.Success -> try {
                String(result.value, Charsets.UTF_8).toCharArray()
            } finally {
                result.value.fill(0)
            }
            is Outcome.Failure -> null
        }

    override suspend fun contains(alias: String): Boolean = withContext(Dispatchers.Default) {
        dataStore.data.first().contains(keyFor(alias))
    }

    override suspend fun delete(alias: String) = withContext(Dispatchers.Default) {
        dataStore.edit { it.remove(keyFor(alias)) }
        SecretRedactor.unregisterSecretAlias(alias)
        OAssistLog.info(TAG, "Секрет удалён: $alias")
    }

    override suspend fun clearAll() = withContext(Dispatchers.Default) {
        val keys = dataStore.data.first().asMap().keys.toList()
        dataStore.edit { prefs ->
            keys.forEach { prefs.remove(it) }
        }
        destroyKey()
        OAssistLog.info(TAG, "Все секреты удалены, ключ пересоздан")
    }

    override suspend fun aliases(): List<String> = withContext(Dispatchers.Default) {
        dataStore.data.first().asMap().keys
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
