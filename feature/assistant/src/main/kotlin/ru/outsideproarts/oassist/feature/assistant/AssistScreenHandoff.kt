package ru.outsideproarts.oassist.feature.assistant

import android.os.Handler
import android.os.Looper
import ru.outsideproarts.oassist.core.agent.AssistSessionStore
import ru.outsideproarts.oassist.core.model.ImagePart

/** Only the opaque token crosses Binder. The single pending payload expires even if launch fails silently. */
object AssistScreenHandoff {
    const val EXTRA_TOKEN = "assist_screen_capture_token"
    private const val EXPIRY_MILLIS = 120_000L
    private val handler = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null

    fun publish(
        owner: AssistSessionStore.ScreenCaptureOwner,
        context: String?,
        image: ImagePart?,
    ): String? {
        val token = AssistSessionStore.publishScreenCapture(owner, context, image) ?: return null
        expiry?.let(handler::removeCallbacks)
        expiry = Runnable { AssistSessionStore.discardScreenCapture(token) }.also {
            handler.postDelayed(it, EXPIRY_MILLIS)
        }
        return token
    }
}
