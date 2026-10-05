package ru.outsideproarts.oassist.core.data.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCodecTest {

    @Test
    fun `pack сохраняет iv и ciphertext`() {
        val iv = ByteArray(SecretCodec.IV_LENGTH) { it.toByte() }
        val ciphertext = byteArrayOf(10, 20, 30, 40)
        val packed = SecretCodec.pack(iv, ciphertext)
        assertEquals(SecretCodec.IV_LENGTH + ciphertext.size, packed.size)
    }

    @Test
    fun `unpack восстанавливает iv и ciphertext`() {
        val iv = ByteArray(SecretCodec.IV_LENGTH) { (it * 3).toByte() }
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5)
        val packed = SecretCodec.pack(iv, ciphertext)
        val (iv2, ct2) = SecretCodec.unpack(packed)
        assertTrue(iv.contentEquals(iv2))
        assertTrue(ciphertext.contentEquals(ct2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pack отвергает неверный размер iv`() {
        SecretCodec.pack(ByteArray(8), byteArrayOf(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unpack отвергает слишком короткий буфер`() {
        SecretCodec.unpack(ByteArray(SecretCodec.IV_LENGTH))
    }
}
