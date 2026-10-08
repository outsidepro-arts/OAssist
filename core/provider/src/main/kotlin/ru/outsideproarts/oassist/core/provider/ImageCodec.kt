package ru.outsideproarts.oassist.core.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Кодирование изображений в [ImagePart] (даунскейл + JPEG) и обратно для UI-превью.
 * Только in-memory: снимки экрана содержат чувствительный контент и на диск не пишутся
 * (в духе «контекст экрана не персистится»).
 */
object ImageCodec {

    /** Длинная сторона после даунскейла. 1280px + JPEG q75 ≈ 50–150 КБ base64. */
    const val MAX_EDGE = 1280
    const val JPEG_QUALITY = 75
    private const val MAX_SOURCE_BYTES = 25 * 1024 * 1024

    fun fromBitmap(bitmap: Bitmap, maxEdge: Int = MAX_EDGE): ImagePart {
        val w = bitmap.width
        val h = bitmap.height
        val scale = if (maxOf(w, h) <= maxEdge) 1f else maxEdge.toFloat() / maxOf(w, h)
        val targetW: Int
        val targetH: Int
        val scaled: Bitmap
        if (scale >= 1f) {
            scaled = bitmap
            targetW = w
            targetH = h
        } else {
            targetW = (w * scale).toInt().coerceAtLeast(1)
            targetH = (h * scale).toInt().coerceAtLeast(1)
            scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return ImagePart(
            imageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            mimeType = "image/jpeg",
            width = targetW,
            height = targetH,
        )
    }

    /** Декодирование сырых байтов (Share-вложения, скриншоты): даунскейл до [maxEdge]. */
    fun fromBytes(bytes: ByteArray, maxEdge: Int = MAX_EDGE): ImagePart? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return fromBitmap(decoded, maxEdge)
    }

    /** Декодирование base64 → Bitmap (UI-превью). */
    fun toBitmap(part: ImagePart): Bitmap? = runCatching {
        val bytes = Base64.decode(part.imageBase64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** Data URI для OpenAI-совместимых `image_url`. Чистая функция — тестируется на JVM. */
    fun dataUri(part: ImagePart): String = "data:" + part.mimeType + ";base64," + part.imageBase64
}