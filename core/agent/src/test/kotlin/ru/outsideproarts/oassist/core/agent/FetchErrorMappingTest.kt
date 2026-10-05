package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FetchErrorMappingTest {

    @Test
    fun httpStatus_knownCodes() {
        assertEquals("страница не найдена (HTTP 404)", httpStatusMessage(404))
        assertEquals("доступ запрещён (HTTP 403)", httpStatusMessage(403))
        assertEquals("ошибка сервера (HTTP 500)", httpStatusMessage(500))
        assertEquals("слишком много запросов, попробуйте позже (HTTP 429)", httpStatusMessage(429))
        assertEquals("ошибка HTTP 418", httpStatusMessage(418))
    }

    @Test
    fun mapFetchError_dnsAndTimeout() {
        assertEquals(
            "не удаётся разрешить имя сайта — проверьте адрес и подключение",
            mapFetchError(UnknownHostException("Unable to resolve host \"www.cbr.ru\"")),
        )
        assertEquals(
            "превышено время ожидания ответа",
            mapFetchError(SocketTimeoutException("Read timed out")),
        )
        assertEquals(
            "превышено время ожидания ответа",
            mapFetchError(java.util.concurrent.TimeoutException("x")),
        )
    }

    @Test
    fun mapFetchError_cleanPassthrough() {
        // Свои диагнозы уже чистые — возвращаются как есть.
        assertEquals(
            "Локальные адреса запрещены: localhost",
            mapFetchError(SecurityException("Локальные адреса запрещены: localhost")),
        )
        assertEquals(
            "Некорректный URL: xxx",
            mapFetchError(IllegalArgumentException("Некорректный URL: xxx")),
        )
    }

    @Test
    fun mapFetchError_truncatesGarbage() {
        // Тела ответов и трейсы (как у Ktor ResponseException) обрезаются.
        val garbage = "Client request(GET https://x/y) invalid: 404. Text: \"${"z".repeat(500)}\""
        val mapped = mapFetchError(RuntimeException(garbage))
        assertTrue(mapped.startsWith("ошибка сети: "))
        assertTrue(mapped.length <= "ошибка сети: ".length + 200)
        // Хвост дампа (дальше первых 200 символов) отрезан.
        assertTrue(!mapped.contains("z".repeat(201)))
    }

    @Test
    fun mapRenderError_chromiumCodes() {
        assertEquals(
            "не удаётся разрешить имя сайта — проверьте адрес и подключение",
            mapRenderError("net::ERR_NAME_NOT_RESOLVED"),
        )
        assertEquals("нет подключения к интернету", mapRenderError("net::ERR_INTERNET_DISCONNECTED"))
        assertEquals("ошибка загрузки страницы", mapRenderError("net::ERR_UNKNOWN_CODE"))
    }
}
