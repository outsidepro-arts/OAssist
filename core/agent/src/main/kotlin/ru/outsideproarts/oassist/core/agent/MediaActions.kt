package ru.outsideproarts.oassist.core.agent

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * Чтение «что играет в системе» через активные медиа-сессии
 * (MediaSessionManager.getActiveSessions — доступ даёт наш NotificationListener,
 * MEDIA_CONTENT_CONTROL недоступен обычным приложениям).
 *
 * Три тира надёжности:
 * 1. активные сессии MediaSession — основной путь, работает у плееров без уведомлений;
 * 2. медиа-уведомления NotificationListener — фолбэк для плееров без публикуемой сессии;
 * 3. голый AudioManager.isMusicActive — честное «играет, но метаданных нет».
 */
class MediaActions(
    private val context: Context,
    private val listenerComponent: () -> ComponentName?,
    private val serviceProvider: () -> NotificationListenerService?,
    private val accessGranted: () -> Boolean = { false },
) {

    suspend fun nowPlaying(): ToolResult {
        if (!accessGranted()) {
            return ToolResult(false, NotifyActions.SERVICE_DISABLED_MESSAGE)
        }
        val musicActive = runCatching {
            context.getSystemService(AudioManager::class.java)?.isMusicActive == true
        }.getOrDefault(false)

        val fromSessions = runCatching { activeSessionTracks() }
            .getOrElse { emptyList() }
        if (fromSessions.isNotEmpty()) {
            return ToolResult(true, formatNowPlaying(fromSessions, MediaSource.SESSION, musicActive))
        }

        val fromNotifications = notificationTracks()
        if (fromNotifications.isNotEmpty()) {
            return ToolResult(
                true,
                formatNowPlaying(fromNotifications, MediaSource.NOTIFICATION, musicActive),
            )
        }

        return ToolResult(true, formatNowPlaying(emptyList(), null, musicActive))
    }

    /** Сессии через NotificationListener-компонент; пропускает свои (MediaButtonRouter ассистента). */
    private fun activeSessionTracks(): List<MediaTrack> {
        val component = listenerComponent() ?: return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        val own = context.packageName
        return manager.getActiveSessions(component)
            .filter { it.packageName != own }
            .mapNotNull { it.toTrack() }
    }

    /** Медиа-уведомления: категория TRANSPORT/SERVICE либо токен сессии в extras. */
    private fun notificationTracks(): List<MediaTrack> {
        val service = serviceProvider() ?: return emptyList()
        val own = service.packageName
        return runCatching {
            service.activeNotifications
                ?.filter { !isOwnNotification(it.packageName, own) && it.notification.isMedia() }
                ?.mapNotNull { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                        ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                    val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
                    if (title.isNullOrBlank() && artist.isNullOrBlank()) return@mapNotNull null
                    MediaTrack(
                        packageName = sbn.packageName,
                        title = title,
                        artist = artist,
                        album = null,
                        durationMs = null,
                        positionMs = null,
                        state = MediaPlaybackState.UNKNOWN,
                    )
                }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun MediaController.toTrack(): MediaTrack? {
        val metadata = this.metadata
        val playback = playbackState
        val hasContent = metadata != null || playback?.state != PlaybackState.STATE_NONE
        if (!hasContent) return null
        val positionMs = playback?.let { computePosition(it) }
        return MediaTrack(
            packageName = packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(MAX_TEXT),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(MAX_TEXT),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.take(MAX_TEXT),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = positionMs,
            state = playbackStateName(playback?.state),
        )
    }

    /** Текущая позиция = базовая + прошедшее время (только для играющего). */
    private fun computePosition(playback: PlaybackState): Long? {
        val base = playback.position.takeIf { it >= 0 } ?: return null
        if (playback.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = playback.lastPositionUpdateTime.takeIf { it > 0 } ?: return base
        val delta = android.os.SystemClock.elapsedRealtime() - elapsed
        return if (delta in 0..MAX_POSITION_DRIFT_MS) base + delta else base
    }

    private fun playbackStateName(state: Int?): MediaPlaybackState = when (state) {
        PlaybackState.STATE_PLAYING -> MediaPlaybackState.PLAYING
        PlaybackState.STATE_BUFFERING -> MediaPlaybackState.BUFFERING
        PlaybackState.STATE_PAUSED -> MediaPlaybackState.PAUSED
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_NONE,
        -> MediaPlaybackState.STOPPED
        else -> MediaPlaybackState.UNKNOWN
    }

    private fun Notification.isMedia(): Boolean =
        category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

    companion object {
        private const val MAX_TEXT = 200
    }
}

private const val MAX_POSITION_DRIFT_MS = 120_000L
