package ru.outsideproarts.oassist.core.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.ToolGroups

/**
 * Опциональный `package` у alarm_set/timer_set. Проверяется то, что можно без Android:
 * текст ошибки со списком обработчиков (на нём строится discovery модели после неудачи)
 * и контракт схем. Сам резолв/запуск — PackageManager/Context, на устройстве.
 */
class ClockHandlersTest {

    private val deskclock = ClockHandler("com.android.deskclock", "Часы")
    private val xiaomi = ClockHandler("com.xiaomi.clock", "Часы Xiaomi")

    // --- noClockHandlerError ---

    @Test
    fun `ошибка с явным пакетом называет его и перечисляет обработчики`() {
        val text = noClockHandlerError("ставить будильники", "com.foo.clock", listOf(deskclock, xiaomi))
        assertTrue(text.contains("'com.foo.clock'"))
        assertTrue(text.contains("не умеет ставить будильники"))
        assertTrue(text.contains("com.android.deskclock"))
        assertTrue(text.contains("com.xiaomi.clock"))
        assertTrue(text.contains("«Часы»"))
    }

    @Test
    fun `ошибка без пакета при наличии обработчиков перечисляет их`() {
        val text = noClockHandlerError("запускать таймеры", null, listOf(deskclock))
        assertTrue(text.contains("com.android.deskclock"))
        assertFalse(text.contains("не умеет"))
    }

    @Test
    fun `без обработчиков вообще — просьба установить часы без пустого списка`() {
        val text = noClockHandlerError("ставить будильники", null, emptyList())
        assertTrue(text.contains("Не найдено приложение часов"))
        assertTrue(text.contains("Установите приложение часов"))
        assertFalse(text.contains("Доступные приложения"))
    }

    @Test
    fun `явный пакет при пустом списке обработчиков не падает`() {
        val text = noClockHandlerError("ставить будильники", "com.foo.clock", emptyList())
        assertTrue(text.contains("'com.foo.clock'"))
        assertTrue(text.contains("Доступные приложения: нет"))
    }

    // --- formatClockHandlers ---

    @Test
    fun `формат списка — название с пакетом через запятую`() {
        assertEquals(
            "«Часы» (com.android.deskclock), «Часы Xiaomi» (com.xiaomi.clock)",
            formatClockHandlers(listOf(deskclock, xiaomi)),
        )
    }

    @Test
    fun `пустой список — «нет»`() {
        assertEquals("нет", formatClockHandlers(emptyList()))
    }

    @Test
    fun `длинный список обрезается с хвостом количества`() {
        val many = (1..12).map { ClockHandler("com.clock$it", "Часы $it") }
        val text = formatClockHandlers(many)
        assertTrue(text.contains("com.clock10"))
        assertFalse(text.contains("com.clock11"))
        assertTrue(text.contains("2 ещё"))
    }

    // --- контракт схем ---

    @Test
    fun `схема alarm_set знает package и не требует его`() {
        val params = alarmSetSchema().parameters
        val properties = params["properties"]!!.jsonObject
        assertEquals("string", properties["package"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("hour", "minute"), required)
        assertFalse(required.contains("package"))
    }

    @Test
    fun `схема timer_set знает package и не требует его`() {
        val params = timerSetSchema().parameters
        val properties = params["properties"]!!.jsonObject
        assertEquals("string", properties["package"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("seconds"), required)
        assertFalse(required.contains("package"))
    }

    @Test
    fun `описания схем упоминают пакет приложения часов и app_list`() {
        val alarmPackage = alarmSetSchema().parameters["properties"]!!.jsonObject["package"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(alarmPackage.contains("пакета"))
        assertTrue(alarmPackage.contains("app_list"))
        val timerPackage = timerSetSchema().parameters["properties"]!!.jsonObject["package"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(timerPackage.contains("пакета"))
        assertTrue(timerPackage.contains("таймеры"))
    }

    // --- группы каталога не изменились ---

    @Test
    fun `тулы остаются в группе DEVICE_WRITE`() {
        assertEquals("DEVICE.WRITE", ToolGroups.groupOf("alarm_set"))
        assertEquals("DEVICE.WRITE", ToolGroups.groupOf("timer_set"))
    }
}
