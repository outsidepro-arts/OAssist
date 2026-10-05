package ru.outsideproarts.oassist.core.agent

/**
 * Живой снимок состояния устройства одной строкой для модели.
 * Всё — read-only и без runtime-разрешений.
 */
data class LiveDeviceState(
    /** Фонарик: null = неизвестно (процесс был мёртв, колбэк не приходил). */
    val torchOn: Boolean?,
    val musicActive: Boolean,
    val wifiOn: Boolean,
    /** Заряд 0–100, null = не прочиталось. */
    val batteryPct: Int?,
    /** Заряжается ли; null = неизвестно. */
    val charging: Boolean?,
    val screenOn: Boolean,
    val ringer: RingerMode,
)

enum class RingerMode {
    NORMAL,
    VIBRATE,
    SILENT,
}

/** Компактный `ключ=значение` — ~150 символов в контекст. */
fun formatDeviceState(state: LiveDeviceState): String {
    val torch = when (state.torchOn) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }
    val battery = if (state.batteryPct != null) {
        "${state.batteryPct}%${if (state.charging == true) "+" else ""}"
    } else {
        "unknown"
    }
    val ringer = when (state.ringer) {
        RingerMode.NORMAL -> "normal"
        RingerMode.VIBRATE -> "vibrate"
        RingerMode.SILENT -> "silent"
    }
    return "torch=$torch; music=${state.musicActive}; wifi=${state.wifiOn}; " +
        "battery=$battery; screen=${state.screenOn}; ringer=$ringer"
}

/**
 * Трекер фонарика: у CameraManager нет запроса состояния, только колбэк.
 * In-memory осознанно: после смерти процесса — честный `null` (unknown),
 * а не выдуманное значение. Наши собственные переключения пишутся сюда же
 * синхронно (источник правды для своих действий).
 */
class TorchTracker {
    private val states = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun onTorchChanged(cameraId: String, enabled: Boolean) {
        states[cameraId] = enabled
    }

    /** `true` — хоть одна вспышка горит; `null` — неизвестно. */
    fun snapshot(): Boolean? {
        if (states.isEmpty()) return null
        return states.values.any { it }
    }
}
