package ru.outsideproarts.oassist.core.model

import org.junit.Assert.*
import org.junit.Test

class LogThrowableTest {
    @Test fun `log throwable excludes messages causes and suppressed secrets`() {
        val secret = "unstructured-access-credential"
        val source = IllegalStateException(secret, IllegalArgumentException(secret)).apply {
            addSuppressed(Exception(secret))
        }
        val sanitized = sanitizeLogThrowable(source)
        assertFalse(sanitized.stackTraceToString().contains(secret))
        assertNull(sanitized.cause)
        assertTrue(sanitized.suppressed.isEmpty())
        assertArrayEquals(source.stackTrace, sanitized.stackTrace)
    }
}
