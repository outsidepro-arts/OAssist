package ru.outsideproarts.oassist.vis

import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * RecognitionService-стаб: требуется для ROLE_ASSISTANT
 * (системный путь распознавания — через `RecognizerIntent` в UI чата/ассиста,
 * а не через этот сервис). Честно отвечает `ERROR_CLIENT`, а не висит молча:
 * система сразу показывает свой фолбэк вместо бесконечного слушания.
 */
class OAssistRecognitionService : RecognitionService() {
    override fun onStartListening(intent: android.content.Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) {
        // Слушать не начинали — отвечать нечего.
    }
}
