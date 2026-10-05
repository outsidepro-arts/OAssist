package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFormatTest {

    @Test
    fun format_freshGpsFix() {
        assertEquals(
            "Координаты: 59.9386,30.3141 (точность 12 м, свежий фикс, gps)",
            formatFix(59.9386, 30.3141, 12, "gps", false),
        )
    }

    @Test
    fun format_mockFlagged() {
        // Подменённый фикс виден явно — модель не презентует его как «ты здесь».
        assertEquals(
            "Координаты: 55.7558,37.6176 (точность 10 м, свежий фикс, gps, mock)",
            formatFix(55.7558, 37.6176, 10, "gps", true),
        )
    }
}
