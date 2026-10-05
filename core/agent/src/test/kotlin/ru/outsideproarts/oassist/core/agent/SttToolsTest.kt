package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.speech.VoiceCaptureController

/**
 * Пара voice_listen_start/stop: старт неблокирующий, стоп возвращает текст.
 */
class SttToolsTest {

    private class FakeCapture(
        val startReply: String = "слушаю",
        val stopReply: String = "мама",
    ) : VoiceCaptureController {
        var starts = 0
        var stops = 0
        var lastLanguage = ""
        var lastTimeout = 0
        override suspend fun startCapture(language: String, timeoutSec: Int): String {
            starts++
            lastLanguage = language
            lastTimeout = timeoutSec
            return startReply
        }

        override suspend fun stopCapture(): String {
            stops++
            return stopReply
        }
    }

    @Test
    fun start_returnsStatus_stop_returnsTranscript() = runTest {
        val capture = FakeCapture()
        val start = VoiceListenStartTool(capture)
        val stop = VoiceListenStopTool(capture)

        val started = start.execute(buildJsonObject {
            put("timeout_sec", 30)
            put("language", "en-US")
        })
        assertTrue(started.success)
        assertEquals("слушаю", started.output)
        assertEquals("en-US", capture.lastLanguage)
        assertEquals(30, capture.lastTimeout)

        val stopped = stop.execute(buildJsonObject { })
        assertTrue(stopped.success)
        assertEquals("мама", stopped.output)
        assertEquals(1, capture.starts)
        assertEquals(1, capture.stops)
    }

    @Test
    fun start_defaults_ru60() = runTest {
        val capture = FakeCapture()

        VoiceListenStartTool(capture).execute(buildJsonObject { })

        assertEquals("ru-RU", capture.lastLanguage)
        assertEquals(60, capture.lastTimeout)
    }

    @Test
    fun risks_startAsks_stopAuto() {
        val capture = FakeCapture()
        assertEquals(ActionRisk.ASK, VoiceListenStartTool(capture).risk)
        assertEquals(ActionRisk.AUTO, VoiceListenStopTool(capture).risk)
        assertTrue(VoiceListenStartTool(capture).requiredPermissions.isNotEmpty())
    }

    @Test
    fun catalog_containsBoth() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue(names.contains("voice_listen_start"))
        assertTrue(names.contains("voice_listen_stop"))
    }
}
