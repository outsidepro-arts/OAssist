package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.outsideproarts.oassist.core.device.PrivExecutor

/**
 * Шторка скринридера (TalkBack «Скрыть экран», у Jieshuo — «шторка»): экран гасится,
 * снимок может выйти чёрным. Проверка кадра — pure; снятие — best-effort строго
 * на момент снимка с восстановлением в finally. Для TalkBack возврат гарантирован:
 * после повторного включения TalkBack сам возвращает шторку (документированное поведение).
 */

const val TALKBACK_SERVICE_COMPONENT =
    "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

/** Кадр почти целиком чёрный (шторка, защищённое окно или пустой экран). */
fun isMostlyBlack(pixels: IntArray, blackRatio: Double = 0.98): Boolean {
    if (pixels.isEmpty()) return true
    var black = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        if (r < 24 && g < 24 && b < 24) black++
    }
    return black >= (pixels.size * blackRatio).toInt()
}

/** Удаление компоненты из строки `enabled_accessibility_services`; флаг — было ли что удалять. */
fun removeServiceComponent(services: String, component: String): Pair<String, Boolean> {
    val target = normalizeServiceComponent(component)
    val parts = services.split(':').filter { it.isNotBlank() }
    val kept = parts.filter { normalizeServiceComponent(it) != target }
    return kept.joinToString(":") to (kept.size != parts.size)
}

/** Возврат компоненты в строку `enabled_accessibility_services` (без дублей в любой нотации). */
fun addServiceComponent(services: String, component: String): String {
    if (enabledServicesContain(services, component)) return services
    val parts = services.split(':').filter { it.isNotBlank() }
    return (parts + component).joinToString(":")
}

/**
 * Временное снятие шторки: выключает TalkBack на момент снимка (только через привилегии
 * Shizuku/root — руками в secure-настройки). Null — снимаем без снятия шторки.
 */
class ScreenCurtain(private val privProvider: suspend () -> PrivExecutor) {

    /** Возвращает восстановление (гарантированно, NonCancellable) или null — не трогали. */
    suspend fun suspendTalkbackForShot(): (suspend () -> Unit)? {
        val priv = privProvider()
        val current = priv.getSetting("secure", ENABLED_ACCESSIBILITY_SERVICES).getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val (without, removed) = removeServiceComponent(current, TALKBACK_SERVICE_COMPONENT)
        if (!removed) return null
        if (priv.putSetting("secure", ENABLED_ACCESSIBILITY_SERVICES, without).isFailure) return null
        return {
            withContext(NonCancellable) {
                runCatching { priv.putSetting("secure", ENABLED_ACCESSIBILITY_SERVICES, current) }
                delay(RESTORE_SETTLE_MS)
            }
        }
    }

    companion object {
        const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
        private const val RESTORE_SETTLE_MS = 500L
    }
}