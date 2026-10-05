package ru.outsideproarts.oassist.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Time {
    private val iso = DateTimeFormatter.ISO_INSTANT

    fun nowEpochMs(): Long = System.currentTimeMillis()

    fun nowInstant(): Instant = Instant.now()

    fun formatEpochMs(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        return formatter.format(Instant.ofEpochMilli(epochMs))
    }

    fun formatIso(instant: Instant): String = iso.format(instant)

    fun epochMsToIso(epochMs: Long): String = iso.format(Instant.ofEpochMilli(epochMs))
}
