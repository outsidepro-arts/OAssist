package ru.outsideproarts.oassist.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** The separate test-APK process has no target app classpath, including the Kotlin runtime. */
class RecognitionServiceFixture : RecognitionService() {

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        val listener = callback ?: return
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        listener.endOfSpeech()
        val results = Bundle()
        val transcripts = java.util.ArrayList<String>()
        transcripts.add("fixture:${intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}")
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, transcripts)
        listener.results(results)
    }

    override fun onStopListening(callback: Callback?) = Unit

    override fun onCancel(callback: Callback?) = Unit
}
