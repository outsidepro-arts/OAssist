package ru.outsideproarts.oassist.core.agent

import android.graphics.Rect

internal enum class ScreenshotSource { WINDOW, DISPLAY }

internal fun screenshotSource(api: Int, hasWindow: Boolean): ScreenshotSource =
    if (api >= 34 && hasWindow) ScreenshotSource.WINDOW else ScreenshotSource.DISPLAY

// A window capture already starts at the window origin; display captures need cropping first.
internal fun screenshotFrameCrop(source: ScreenshotSource, window: UiRect?): UiRect? =
    if (source == ScreenshotSource.DISPLAY) window else null

internal fun screenshotRegionCrop(region: UiRect?, window: UiRect?): UiRect? =
    if (region == null || window == null) null else UiRect(
        region.left - window.left, region.top - window.top,
        region.right - window.left, region.bottom - window.top,
    )

internal fun Rect.toUiRect() = UiRect(left, top, right, bottom)
internal fun UiRect.toRect() = Rect(left, top, right, bottom)
