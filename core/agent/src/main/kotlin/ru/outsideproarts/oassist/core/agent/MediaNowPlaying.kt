package ru.outsideproarts.oassist.core.agent

/**
 * Одна активная медиа-позиция (сессия MediaSession либо медиа-уведомление).
 * Все поля — best effort: игрок может не отдавать часть метаданных.
 */
data class MediaTrack(
    val packageName: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** null = неизвестно. */
    val durationMs: Long?,
    /** null = неизвестно (не вычислено или игрок не сообщает). */
    val positionMs: Long?,
    val state: MediaPlaybackState,
)

enum class MediaPlaybackState {
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED,
    UNKNOWN,
}

/** Откуда взяты данные — для пометки в ответе модели. */
enum class MediaSource {
    /** Активные сессии MediaSessionManager.getActiveSessions (основной путь). */
    SESSION,
    /** Медиа-уведомления NotificationListener (фолбэк: плеер без публикуемой сессии). */
    NOTIFICATION,
}

/** Текст для модели: список позиций + честная пометка об источнике. */
fun formatNowPlaying(
    tracks: List<MediaTrack>,
    source: MediaSource?,
    musicActive: Boolean,
): String {
    if (tracks.isEmpty()) {
        return if (musicActive) {
            "Музыка играет, но метаданные недоступны: " +
                "ни одной активной медиа-сессии и медиа-уведомления не видно"
        } else {
            "Сейчас ничего не играет"
        }
    }
    val sourceNote = when (source) {
        MediaSource.SESSION -> "медиа-сессии"
        MediaSource.NOTIFICATION -> "по медиа-уведомлениям (сессии плеера не видны)"
        null -> null
    }
    val header = when (tracks.size) {
        1 -> "Играет:"
        else -> "Активных медиа-позиций: ${tracks.size}"
    } + (sourceNote?.let { " [$it]" } ?: "")
    val lines = tracks.take(MAX_TRACKS).mapIndexed { index, track ->
        "[${index + 1}] ${formatTrackLine(track)}"
    }
    val truncated = if (tracks.size > MAX_TRACKS) {
        "\n…[обрезано: показано $MAX_TRACKS из ${tracks.size}]"
    } else {
        ""
    }
    return (listOf(header) + lines + truncated).joinToString("\n")
}

/** ««Трек» — Исполнитель (Альбом) — playing 1:23/4:56 (pkg)» с опущенными неизвестными. */
fun formatTrackLine(track: MediaTrack): String {
    val title = track.title?.trim()?.takeUnless { it.isEmpty() } ?: "(без названия)"
    val artist = track.artist?.trim()?.takeIf { it.isNotEmpty() }
    val album = track.album?.trim()?.takeIf { it.isNotEmpty() }
    val subject = listOfNotNull("«$title»", artist, album?.let { "($it)" })
        .joinToString(" ")
    val timing = listOfNotNull(
        playbackStateName(track.state),
        formatTrackPosition(track),
    ).joinToString(" ")
    val pkg = track.packageName?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
    return "$subject — $timing$pkg"
}

/** `1:23/4:56` — позиция и длительность, только если известны обе. */
fun formatTrackPosition(track: MediaTrack): String? {
    val position = track.positionMs ?: return null
    val duration = track.durationMs ?: return null
    return "${formatTrackTime(position)}/${formatTrackTime(duration)}"
}

/** `м:ss` (или `ч:м:ss` при часах). */
fun formatTrackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun playbackStateName(state: MediaPlaybackState): String = when (state) {
    MediaPlaybackState.PLAYING -> "playing"
    MediaPlaybackState.PAUSED -> "paused"
    MediaPlaybackState.BUFFERING -> "buffering"
    MediaPlaybackState.STOPPED -> "stopped"
    MediaPlaybackState.UNKNOWN -> "unknown"
}

private const val MAX_TRACKS = 5
