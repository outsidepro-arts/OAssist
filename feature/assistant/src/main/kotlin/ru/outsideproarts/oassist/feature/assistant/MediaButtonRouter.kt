package ru.outsideproarts.oassist.feature.assistant

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat

/**
 * Перехват media play/pause (жест TalkBack двойным тапом) собственным MediaSession.
 * Сессия активна всё время жизни оверлея: событие — переключатель голосового ввода
 * и потребляется, пока окно открыто. При закрытом оверлее медиа-кнопки уходят плеерам.
 */
class MediaButtonRouter(
    private val context: Context,
    private val onPlayPause: () -> Unit,
) {

    private var session: MediaSession? = null

    fun start() {
        if (session != null) return
        session = MediaSession(context, SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val key = IntentCompat.getParcelableExtra(
                        mediaButtonIntent,
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java,
                    )
                    if (key?.action == KeyEvent.ACTION_DOWN && isToggleKey(key.keyCode)) {
                        onPlayPause()
                        return true
                    }
                    return false
                }

                override fun onPlay() {
                    onPlayPause()
                }

                override fun onPause() {
                    onPlayPause()
                }
            })
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, context.getString(R.string.assistant_title))
                    .build(),
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                    .build(),
            )
            isActive = true
        }
    }

    fun release() {
        session?.isActive = false
        session?.release()
        session = null
    }

    private fun isToggleKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK

    companion object {
        private const val SESSION_TAG = "oassist_assistant"
    }
}
