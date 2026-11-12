package ru.outsideproarts.oassist.share

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.a11y.FlowAnnouncer
import ru.outsideproarts.oassist.core.model.TtsConfig
import ru.outsideproarts.oassist.core.speech.SpeechHandle
import ru.outsideproarts.oassist.feature.share.ShareActivity

/**
 * Якорь анонсов скринридера следует за окном Share: без привязки фразы
 * «Хода работы» и финальный ответ канала SCREEN_READER теряются (TalkBack
 * молчит) — регрессия, найденная при проверке паритета с Chat.
 */
@RunWith(AndroidJUnit4::class)
class ShareScreenReaderAnchorTest {

    private class RecordingSpeechHandle(private val anchor: AtomicReference<View?>) : SpeechHandle {
        override fun speak(sentence: String) = Unit
        override suspend fun speakWhenReady(text: String): Boolean = false
        override suspend fun awaitIdle() = Unit
        override fun stop() = Unit
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        override val announcer: FlowAnnouncer? = null
        override fun applyTts(config: TtsConfig) = Unit
        override fun setNarrativeEnabled(enabled: Boolean) = Unit
        override fun setScreenReaderAnchor(view: View?) {
            anchor.set(view)
        }
        override fun announceToScreenReader(text: String) = Unit
    }

    @Test
    fun screenReaderAnchorFollowsShareWindowLifecycle() {
        val anchor = AtomicReference<View?>(null)
        val original = SpeechHandle.get()
        SpeechHandle.register(RecordingSpeechHandle(anchor))
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = Intent(context, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "fixture")
            ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    // onResume: анонсы ходов и ответа идут от окна Share.
                    assertSame(activity.window.decorView, anchor.get())
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                // onPause: якорь отвязан — чужие анонсы не уходят в мёртвое окно.
                assertNull(anchor.get())
                scenario.moveToState(Lifecycle.State.RESUMED)
                // Возврат в окно: якорь привязывается снова.
                scenario.onActivity { activity ->
                    assertSame(activity.window.decorView, anchor.get())
                }
            }
        } finally {
            if (original != null) SpeechHandle.register(original)
        }
    }
}
