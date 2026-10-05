package ru.outsideproarts.oassist.core.data.secret

/**
 * Формат шифротекста: iv(12) || ciphertext+tag.
 * IV генерируется Keystore на каждое шифрование.
 */
object SecretCodec {
    const val IV_LENGTH = 12

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "IV должен быть $IV_LENGTH байт, получено ${iv.size}" }
        return ByteArray(IV_LENGTH + ciphertext.size).also { out ->
            System.arraycopy(iv, 0, out, 0, IV_LENGTH)
            System.arraycopy(ciphertext, 0, out, IV_LENGTH, ciphertext.size)
        }
    }

    fun unpack(packed: ByteArray): Pair<ByteArray, ByteArray> {
        require(packed.size > IV_LENGTH) { "Шифротекст слишком короткий: ${packed.size}" }
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        return iv to ciphertext
    }
}
