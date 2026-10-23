package ru.outsideproarts.oassist.core.agent

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детектор блокировки фонового запуска HyperOS/MIUI (опа 10021).
 * JVM-покрываема pure-логика: классификация производителя, режимов оппы,
 * формат команды fix и матрица shouldBlock. Reflection — на устройстве.
 */
class BgStartGuardTest {

    @Test
    fun `производители MIUI распознаются без учёта регистра`() {
        assertTrue(BgStartGuard.isMiui("Xiaomi"))
        assertTrue(BgStartGuard.isMiui("Redmi"))
        assertTrue(BgStartGuard.isMiui("POCO"))
        assertTrue(BgStartGuard.isMiui("xiaomi"))
        assertFalse(BgStartGuard.isMiui("samsung"))
        assertFalse(BgStartGuard.isMiui("Google"))
        assertFalse(BgStartGuard.isMiui(""))
    }

    @Test
    fun `режим ignore и errored считаются блоком`() {
        assertTrue(BgStartGuard.isRestrictedMode(AppOpsManager.MODE_IGNORED))
        assertTrue(BgStartGuard.isRestrictedMode(AppOpsManager.MODE_ERRORED))
        assertFalse(BgStartGuard.isRestrictedMode(AppOpsManager.MODE_ALLOWED))
        assertFalse(BgStartGuard.isRestrictedMode(AppOpsManager.MODE_DEFAULT))
        assertFalse(BgStartGuard.isRestrictedMode(AppOpsManager.MODE_FOREGROUND))
    }

    @Test
    fun `неизвестный режим оппы fail-open`() {
        assertFalse(BgStartGuard.isRestrictedMode(null))
    }

    @Test
    fun `блок — только MIUI в фоне с запрещённой опой`() {
        assertTrue(BgStartGuard.shouldBlock(isMiui = true, isAppForeground = false, mode = AppOpsManager.MODE_IGNORED))
        assertFalse(BgStartGuard.shouldBlock(isMiui = true, isAppForeground = true, mode = AppOpsManager.MODE_IGNORED))
        assertFalse(BgStartGuard.shouldBlock(isMiui = false, isAppForeground = false, mode = AppOpsManager.MODE_IGNORED))
        assertTrue(BgStartGuard.shouldBlock(isMiui = true, isAppForeground = false, mode = AppOpsManager.MODE_ERRORED))
    }

    @Test
    fun `матрица shouldBlock — все рёбра`() {
        for (mode in listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND)) {
            assertFalse(BgStartGuard.shouldBlock(isMiui = true, isAppForeground = false, mode = mode))
        }
        for (foreground in listOf(true, false)) {
            assertFalse(BgStartGuard.shouldBlock(isMiui = true, isAppForeground = foreground, mode = null))
        }
    }

    @Test
    fun `команда fix — appops set с опой 10021`() {
        assertEquals(
            listOf("appops", "set", "ru.outsideproarts.oassist", "10021", "allow"),
            BgStartGuard.fixCommand("ru.outsideproarts.oassist"),
        )
    }

    @Test
    fun `инструкция содержит adb-команду`() {
        val text = BgStartGuard.instruction("ru.outsideproarts.oassist")
        assertTrue(text.contains("adb shell appops set ru.outsideproarts.oassist 10021 allow"))
    }

    @Test
    fun `сообщение блока называет разрешение и команду`() {
        val text = BgStartGuard.blockedMessage("ru.outsideproarts.oassist")
        assertTrue(text.contains("HyperOS/MIUI"))
        assertTrue(text.contains("adb shell appops set ru.outsideproarts.oassist 10021 allow"))
    }

    @Test
    fun `op дефолтный —10021`() {
        assertEquals(10021, BgStartGuard.OP_BACKGROUND_START_ACTIVITY)
    }

    @Test
    fun `AppVisibility считает resumed-активности`() {
        AppVisibility.resetForTest()
        assertFalse(AppVisibility.isForeground)
        AppVisibility.onActivityResumed()
        assertTrue(AppVisibility.isForeground)
        AppVisibility.onActivityResumed()
        AppVisibility.onActivityPaused()
        assertTrue(AppVisibility.isForeground)
        AppVisibility.onActivityPaused()
        assertFalse(AppVisibility.isForeground)
        AppVisibility.onActivityPaused()
        assertFalse(AppVisibility.isForeground)
    }
}
