package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скриншоты области и узла. Проверяется то, что можно проверить без Android:
 * контракт схем (на него опирается модель), честные ошибки и строгая валидация.
 * Сам захват кадра — код с `AccessibilityService`/`Bitmap`, покрывается на устройстве.
 */
class UiScreenshotToolsTest {

    private fun ui(granted: Boolean = false) = UiActions(
        serviceProvider = { null },
        accessGranted = { granted },
        connectTimeoutMs = 0,
        connectPollMs = 1,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }

    @Test
    fun `схема области требует четыре координаты`() {
        val schema = UiScreenshotRegionTool(ui()).schema
        assertEquals("ui_screenshot_region", schema.name)
        val properties = schema.parameters["properties"]!!.jsonObject.keys
        assertEquals(setOf("left", "top", "right", "bottom"), properties)
        val required = schema.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("left", "top", "right", "bottom"), required.toSet())
        properties.forEach { name ->
            assertEquals("integer", schema.parameters["properties"]!!.jsonObject[name]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `схема узла требует один индекс`() {
        val schema = UiScreenshotNodeTool(ui()).schema
        assertEquals("ui_screenshot_node", schema.name)
        assertEquals(setOf("index", "snapshot_id"), schema.parameters["properties"]!!.jsonObject.keys)
        assertEquals(
            listOf("index", "snapshot_id"),
            schema.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "integer",
            schema.parameters["properties"]!!.jsonObject["index"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `описания тулов упоминают источник координат и vision`() {
        assertTrue(UiScreenshotRegionTool(ui()).schema.description.contains("ui_get_screen"))
        assertTrue(UiScreenshotRegionTool(ui()).schema.description.contains("изображени"))
        assertTrue(UiScreenshotNodeTool(ui()).schema.description.contains("ui_get_screen"))
        assertTrue(UiScreenshotNodeTool(ui()).schema.description.contains("изображени"))
    }

    @Test
    fun `подтверждение области называет границы`() {
        val text = UiScreenshotRegionTool(ui())
            .describeForConfirmation(args("left" to "10", "top" to "20", "right" to "300", "bottom" to "400"))
        assertEquals("Скриншот области экрана [10,20-300,400]", text)
    }

    @Test
    fun `подтверждение узла называет индекс`() {
        val text = UiScreenshotNodeTool(ui()).describeForConfirmation(args("index" to "7"))
        assertEquals("Скриншот элемента экрана №7", text)
    }

    @Test
    fun `подтверждение без части аргументов не падает`() {
        assertTrue(UiScreenshotRegionTool(ui()).describeForConfirmation(JsonObject(emptyMap())).isNotBlank())
        assertTrue(UiScreenshotNodeTool(ui()).describeForConfirmation(JsonObject(emptyMap())).isNotBlank())
    }

    @Test
    fun `без координат область отклоняется честно`() = runBlocking {
        val result = UiScreenshotRegionTool(ui()).execute(JsonObject(emptyMap()))
        assertFalse(result.success)
        assertEquals("Нужны left/top/right/bottom", result.output)
    }

    @Test
    fun `без индекса узел отклоняется честно`() = runBlocking {
        val result = UiScreenshotNodeTool(ui()).execute(JsonObject(emptyMap()))
        assertFalse(result.success)
        assertEquals("Не указан index", result.output)
    }

    @Test
    fun `нечисловые координаты не проходят молча`() = runBlocking {
        val result = UiScreenshotRegionTool(ui())
            .execute(args("left" to "abc", "top" to "0", "right" to "10", "bottom" to "10"))
        assertFalse(result.success)
        assertEquals("Нужны left/top/right/bottom", result.output)
    }

    @Test
    fun `перевёрнутая область отклоняется до обращения к экрану`() = runBlocking {
        val result = ui().screenshotRegion(300, 400, 10, 20)
        assertFalse(result.success)
        assertTrue(result.output.contains("Некорректная область"))
    }

    @Test
    fun `вырожденная область по одной оси отклоняется`() = runBlocking {
        assertFalse(ui().screenshotRegion(10, 20, 10, 400).success)
        assertFalse(ui().screenshotRegion(10, 20, 300, 20).success)
    }

    @Test
    fun `без доступа к экрану новые снимки говорят об этом прямо`() = runBlocking {
        val region = ui().screenshotRegion(10, 20, 300, 400)
        val node = ui().screenshotNode(3)
        assertFalse(region.success)
        assertFalse(node.success)
        assertEquals(UiActions.SERVICE_DISABLED_MESSAGE, region.output)
        assertEquals(UiActions.SERVICE_DISABLED_MESSAGE, node.output)
    }

    @Test
    fun `ошибка без кадра не несёт картинок`() = runBlocking {
        assertTrue(ui().screenshotRegion(10, 20, 300, 400).images.isEmpty())
        assertTrue(ui().screenshotNode(3).images.isEmpty())
    }
}