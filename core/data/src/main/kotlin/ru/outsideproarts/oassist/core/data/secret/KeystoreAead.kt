package ru.outsideproarts.oassist.core.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ru.outsideproarts.oassist.core.model.OAssistError
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.core.model.Outcome
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM поверх Android Keystore. Ключ non-extractable, StrongBox с фолбэком.
 *
 * Без setUserAuthenticationRequired и с setUnlockedDeviceRequired(false):
 * ассистент обязан работать с экрана блокировки.
 */
class KeystoreAead(
    private val keyAlias: String = DEFAULT_ALIAS,
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(plaintext: ByteArray): Outcome<ByteArray> = runCatchingOutcome {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        SecretCodec.pack(iv, ciphertext)
    }

    fun decrypt(packed: ByteArray): Outcome<ByteArray> = runCatchingOutcome {
        val (iv, ciphertext) = SecretCodec.unpack(packed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.doFinal(ciphertext)
    }

    fun destroyKey() {
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(false)
            .build()
        generator.init(spec)
        val key = generator.generateKey()
        OAssistLog.info(TAG, "Keystore AES-256-GCM ключ создан")
        return key
    }

    private inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (t: Throwable) {
        OAssistLog.error(TAG, t, "KeystoreAead: ${t.message}")
        Outcome.Failure(OAssistError.Storage(message = "Ошибка шифрования", cause = t))
    }

    companion object {
        private const val TAG = "KeystoreAead"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        const val DEFAULT_ALIAS = "oassist_secret_master"
    }
}
