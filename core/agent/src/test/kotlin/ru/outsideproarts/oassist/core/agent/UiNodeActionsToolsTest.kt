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
 * Скрытые действия нод. Проверяется то, что можно без Android: контракт схем
 * (на него опирается модель), честные ошибки и диагноз до обращения к экрану —
 * сам обход дерева и performAction покрываются на устройстве.
 */
class UiNodeActionsToolsTest {

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
    fun `схема списка требует индекс элемента`() {
        val schema = UiGetActionsTool(ui()).schema
        assertEquals("ui_get_actions", schema.name)
        assertEquals(setOf("index"), schema.parameters["properties"]!!.jsonObject.keys)
        assertEquals(
            listOf("index"),
            schema.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "integer",
            schema.parameters["properties"]!!.jsonObject["index"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `схема выполнения требует оба индекса`() {
        val schema = UiPerformActionTool(ui()).schema
        assertEquals("ui_perform_action", schema.name)
        assertEquals(setOf("index", "action_index"), schema.parameters["properties"]!!.jsonObject.keys)
        assertEquals(
            setOf("index", "action_index"),
            schema.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        schema.parameters["properties"]!!.jsonObject.forEach { (name, prop) ->
            assertEquals(
                "integer",
                prop.jsonObject["type"]!!.jsonPrimitive.content,
            )
            assertTrue(prop.jsonObject["description"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun `описания связывают тулы со снимком и друг с другом`() {
        val list = UiGetActionsTool(ui()).schema.description
        assertTrue(list.contains("ui_get_screen"))
        assertTrue(list.contains("has_actions"))
        assertTrue(list.contains("ui_perform_action"))
        val perform = UiPerformActionTool(ui()).schema.description
        assertTrue(perform.contains("ui_get_screen"))
        assertTrue(perform.contains("ui_get_actions"))
        assertTrue(UiGetScreenTool(ui()).schema.description.contains("has_actions"))
    }

    @Test
    fun `подтверждение называет индексы`() {
        assertEquals(
            "Показать действия элемента экрана №7",
            UiGetActionsTool(ui()).describeForConfirmation(args("index" to "7")),
        )
        assertEquals(
            "Выполнить действие №0 элемента экрана №5",
            UiPerformActionTool(ui()).describeForConfirmation(args("index" to "5", "action_index" to "0")),
        )
        assertTrue(UiGetActionsTool(ui()).describeForConfirmation(JsonObject(emptyMap())).isNotBlank())
        assertTrue(UiPerformActionTool(ui()).describeForConfirmation(JsonObject(emptyMap())).isNotBlank())
    }

    @Test
    fun `без аргументов — честные ошибки`() = runBlocking {
        val list = UiGetActionsTool(ui()).execute(JsonObject(emptyMap()))
        assertFalse(list.success)
        assertEquals("Не указан index", list.output)
        val performNoArgs = UiPerformActionTool(ui()).execute(JsonObject(emptyMap()))
        assertFalse(performNoArgs.success)
        assertEquals("Не указан index", performNoArgs.output)
        val noActionIndex = UiPerformActionTool(ui()).execute(args("index" to "5"))
        assertFalse(noActionIndex.success)
        assertEquals("Не указан action_index", noActionIndex.output)
    }

    @Test
    fun `нечисловой индекс не проходит молча`() = runBlocking {
        val result = UiGetActionsTool(ui()).execute(args("index" to "abc"))
        assertFalse(result.success)
        assertEquals("Не указан index", result.output)
    }

    @Test
    fun `без доступа к сервису — явная диагностика`() = runBlocking {
        val list = UiGetActionsTool(ui(granted = false)).execute(args("index" to "3"))
        assertFalse(list.success)
        assertEquals(UiActions.SERVICE_DISABLED_MESSAGE, list.output)
        val perform = UiPerformActionTool(ui(granted = false))
            .execute(args("index" to "3", "action_index" to "0"))
        assertFalse(perform.success)
        assertEquals(UiActions.SERVICE_DISABLED_MESSAGE, perform.output)
    }

    @Test
    fun `доступ выдан, но сервис не подключился — тоже явная диагностика`() = runBlocking {
        val result = UiGetActionsTool(ui(granted = true)).execute(args("index" to "3"))
        assertFalse(result.success)
        assertEquals(UiActions.SERVICE_NOT_CONNECTED_MESSAGE, result.output)
    }

    @Test
    fun `тулы есть в каталоге`() {
        val names = ToolCatalog.descriptors().map { it.name }
        assertTrue("ui_get_actions" in names)
        assertTrue("ui_perform_action" in names)
    }
}
