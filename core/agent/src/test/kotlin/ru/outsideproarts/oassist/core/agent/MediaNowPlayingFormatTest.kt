package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNowPlayingFormatTest {

    private fun fullTrack() = MediaTrack(
        packageName = "com.example.player",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 296_000,
        positionMs = 83_000,
        state = MediaPlaybackState.PLAYING,
    )

    @Test
    fun format_fullTrackLine() {
        assertEquals(
            "«Song» Artist (Album) — playing 1:23/4:56 (com.example.player)",
            formatTrackLine(fullTrack()),
        )
    }

    @Test
    fun format_missingPartsOmitted() {
        val track = MediaTrack(
            packageName = null,
            title = "Song",
            artist = null,
            album = null,
            durationMs = null,
            positionMs = null,
            state = MediaPlaybackState.PAUSED,
        )
        assertEquals("«Song» — paused", formatTrackLine(track))
    }

    @Test
    fun format_blankTitleFallsBack() {
        val track = fullTrack().copy(title = "  ")
        assertTrue(formatTrackLine(track).startsWith("«(без названия)»"))
    }

    @Test
    fun format_empty_idle() {
        assertEquals(
            "Сейчас ничего не играет",
            formatNowPlaying(emptyList(), null, musicActive = false),
        )
    }

    @Test
    fun format_empty_musicActiveOnly() {
        val text = formatNowPlaying(emptyList(), null, musicActive = true)
        assertTrue(text.startsWith("Музыка играет, но метаданные недоступны"))
    }

    @Test
    fun format_singleSessionHeader() {
        val text = formatNowPlaying(listOf(fullTrack()), MediaSource.SESSION, musicActive = true)
        assertTrue(text.startsWith("Играет: [медиа-сессии]"))
        assertTrue(text.contains("«Song»"))
    }

    @Test
    fun format_notificationSourceNoted() {
        val text = formatNowPlaying(listOf(fullTrack()), MediaSource.NOTIFICATION, musicActive = true)
        assertTrue(text.startsWith("Играет: [по медиа-уведомлениям"))
    }

    @Test
    fun format_multipleTracksCapped() {
        val tracks = List(8) { fullTrack() }
        val text = formatNowPlaying(tracks, MediaSource.SESSION, musicActive = true)
        assertTrue(text.startsWith("Активных медиа-позиций: 8"))
        assertTrue(text.contains("обрезано: показано 5 из 8"))
    }

    @Test
    fun position_hmsFormats() {
        assertEquals("0:05", formatTrackTime(5_000))
        assertEquals("1:00", formatTrackTime(60_000))
        assertEquals("1:01:01", formatTrackTime(3_661_000))
        assertEquals("0:00", formatTrackTime(-1))
    }

    @Test
    fun position_missingDurationNoSlash() {
        val track = fullTrack().copy(durationMs = null)
        assertEquals(null, formatTrackPosition(track))
    }

    @Test
    fun playbackState_names() {
        assertEquals("playing", playbackStateName(MediaPlaybackState.PLAYING))
        assertEquals("paused", playbackStateName(MediaPlaybackState.PAUSED))
        assertEquals("unknown", playbackStateName(MediaPlaybackState.UNKNOWN))
    }
}
