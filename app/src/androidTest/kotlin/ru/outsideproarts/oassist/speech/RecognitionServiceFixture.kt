package ru.outsideproarts.oassist.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** The separate test-APK process has no target app classpath, including the Kotlin runtime. */
open class RecognitionServiceFixture : RecognitionService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val failedRetryLanguages = java.util.HashSet<String>()
    private val pendingResults = java.util.HashMap<Callback, Runnable>()

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        val listener = callback ?: return
        // The fixture supplies text without reading audio; it owns the attribution context.
        createContext(
            android.content.ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build(),
        )
        val language = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: ""

        if (language.equals("retry-once:retry-marker") && failedRetryLanguages.add(language)) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        listener.readyForSpeech(Bundle())
        val result = Runnable {
            pendingResults.remove(listener)
            listener.beginningOfSpeech()
            listener.endOfSpeech()
            val results = Bundle()
            val transcripts = java.util.ArrayList<String>()
            transcripts.add("fixture:$language")
            results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, transcripts)
            listener.results(results)
        }

        if (language.equals("late:cancelled")) {
            DeferredFixtureResult.result = result
            val partial = Bundle()
            val texts = java.util.ArrayList<String>()
            texts.add("fixture:late-ready")
            partial.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, texts)
            listener.partialResults(partial)
            return
        }
        if (language.equals("delay:cancelled")) {
            // Simulate a callback that was already in flight at cancellation.
            val previous = DeferredFixtureResult.result
            DeferredFixtureResult.result = null
            previous?.run()
            val partial = Bundle()
            val texts = java.util.ArrayList<String>()
            texts.add(if (previous != null) "fixture:late-delivered" else "fixture:ready")
            partial.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, texts)
            listener.partialResults(partial)
            pendingResults.put(listener, result)
            handler.postDelayed(result, 30_000L)
        } else {
            result.run()
        }
    }

    override fun onStopListening(callback: Callback?) {
        val listener = callback ?: return
        val result = pendingResults.remove(listener) ?: return
        handler.removeCallbacks(result)
        result.run()
    }

    override fun onCancel(callback: Callback?) {
        val listener = callback ?: return
        val result = pendingResults.remove(listener)
        if (result != null) handler.removeCallbacks(result)
    }
}

class AlternateRecognitionServiceFixture : RecognitionServiceFixture() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        intent?.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            "alternate:${intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}",
        )
        super.onStartListening(intent, callback)
    }
}

private object DeferredFixtureResult {
    var result: Runnable? = null
}
