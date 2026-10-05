package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecretRedactorTest {

    @Before
    fun setUp() {
        SecretRedactor.unregisterSecretAlias("alias-secret-value-1234")
        SecretRedactor.unregisterSecretAlias("my-token-abcdef")
    }

    @Test
    fun `вырезает зарегистрированный alias`() {
        SecretRedactor.registerSecretAlias("alias-secret-value-1234")
        val out = SecretRedactor.redact("ключ: alias-secret-value-1234 конец")
        assertFalse(out.contains("alias-secret-value-1234"))
        assertTrue(out.contains("••••"))
    }

    @Test
    fun `вырезает api key по паттерну`() {
        val out = SecretRedactor.redact("api_key=sk-abcdef1234567890")
        assertFalse(out.contains("sk-abcdef1234567890"))
    }

    @Test
    fun `вырезает bearer токен`() {
        val out = SecretRedactor.redact("Authorization: Bearer abcdefghijklmnop")
        assertFalse(out.contains("abcdefghijklmnop"))
    }

    @Test
    fun `не трогает обычный текст`() {
        val input = "обычное сообщение без секретов"
        assertEquals(input, SecretRedactor.redact(input))
    }

    @Test
    fun `unregisterAlias перестаёт вырезать`() {
        SecretRedactor.registerSecretAlias("my-token-abcdef")
        assertTrue(SecretRedactor.redact("x my-token-abcdef y").contains("••••"))
        SecretRedactor.unregisterSecretAlias("my-token-abcdef")
        assertTrue(SecretRedactor.redact("x my-token-abcdef y").contains("my-token-abcdef"))
    }
}
