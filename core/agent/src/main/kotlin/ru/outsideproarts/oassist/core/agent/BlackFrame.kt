package ru.outsideproarts.oassist.core.agent

import android.graphics.Bitmap

/**
 * Почти чёрный кадр: шторка скринридера (TalkBack/SandFly «Dim screen», Jieshuo «шторка» —
 * непрозрачное чёрное окно поверх экрана), защищённое окно или просто тёмное содержимое.
 *
 * Шторку извне отключить нельзя (скринридеры не предоставляют API), а выключать сам
 * скринридер ради снимка запрещено. Поэтому кадр только детектируется и отдаётся
 * с честной пометкой ([blackFrameNote]); оконный захват (API 34+) шторку не содержит.
 */

private const val PROBE_SIZE = 64

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

/**
 * Проба кадра по пикселям. Hardware-bitmap не отдаёт пиксели напрямую
 * (`getPixels` падает) — предварительно копируется в software-конфигурацию.
 */
fun isMostlyBlack(bitmap: Bitmap): Boolean {
    val pixels = runCatching {
        val software =
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        if (software != null) {
            val probe = Bitmap.createScaledBitmap(software, PROBE_SIZE, PROBE_SIZE, true)
            val out = IntArray(PROBE_SIZE * PROBE_SIZE)
            probe.getPixels(out, 0, PROBE_SIZE, 0, 0, PROBE_SIZE, PROBE_SIZE)
            if (probe !== software) probe.recycle()
            if (software !== bitmap) software.recycle()
            out
        } else {
            null
        }
    }.getOrNull() ?: return false
    return isMostlyBlack(pixels)
}

/**
 * Пометка почти чёрного кадра инструментов. [windowCapture] — снимок конкретного окна
 * (API 34+): такой захват не содержит чужие оверлеи, поэтому шторка скринридера
 * им исключена и утверждать о ней нельзя.
 */
fun blackFrameNote(windowCapture: Boolean): String =
    if (windowCapture) {
        "Кадр почти чёрный: возможно, тёмное содержимое экрана или защищённое окно."
    } else {
        // Кадр показывает все слои экрана, включая шторку (отдельно окно снимается на Android 14+).
        "Кадр почти чёрный: возможно, экран скрыт шторкой экранного чтеца — снимок собран из всех " +
            "слоёв экрана, включая шторку. Если нужен видимый снимок — попроси пользователя " +
            "временно снять шторку жестом экранного чтеца."
    }
