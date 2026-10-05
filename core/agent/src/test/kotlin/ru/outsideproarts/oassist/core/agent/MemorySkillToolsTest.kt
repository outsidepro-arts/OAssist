package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.data.memory.MemoryStore
import ru.outsideproarts.oassist.core.data.skill.SkillStore
import ru.outsideproarts.oassist.core.model.ActionRisk
import ru.outsideproarts.oassist.core.model.SkillInfo

private class FakeMemory(var text: String = "") : MemoryStore {
    override suspend fun read(): String = text
    override suspend fun write(text: String) {
        this.text = text
    }

    override suspend fun append(line: String) {
        text = if (text.isEmpty()) line else "$text\n$line"
    }

    override suspend fun clear() {
        text = ""
    }
}

private class FakeSkills(val skills: MutableList<SkillInfo> = mutableListOf()) : SkillStore {
    override suspend fun list(): List<SkillInfo> = skills.toList()
    override suspend fun get(name: String): SkillInfo? = skills.find { it.name == name }
    override suspend fun upsert(name: String, title: String, body: String) {
        skills.removeAll { it.name == name }
        skills.add(SkillInfo(name, title, body))
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        val i = skills.indexOfFirst { it.name == name }
        if (i >= 0) skills[i] = skills[i].copy(enabled = enabled)
    }

    override suspend fun delete(name: String) {
        skills.removeAll { it.name == name }
    }
}

class MemoryToolsTest {

    @Test
    fun get_empty_saysSo() = runTest {
        val out = MemoryGetTool(FakeMemory()).execute(buildJsonObject { })

        assertTrue(out.success)
        assertEquals("(память пуста)", out.output)
    }

    @Test
    fun put_appendAndReplace() = runTest {
        val memory = FakeMemory("a")

        val appended = MemoryPutTool(memory).execute(buildJsonObject { put("text", "b") })
        assertTrue(appended.success)
        assertEquals("a\nb", memory.text)

        MemoryPutTool(memory).execute(buildJsonObject {
            put("text", "c")
            put("mode", "replace")
        })
        assertEquals("c", memory.text)
    }

    @Test
    fun put_blankText_refuses() = runTest {
        val out = MemoryPutTool(FakeMemory()).execute(buildJsonObject { put("text", "  ") })

        assertEquals(false, out.success)
    }

    @Test
    fun forget_removesMatchingLines() = runTest {
        val memory = FakeMemory("любит чай\nзовут Иван\nлюбит кофе")

        val out = MemoryForgetTool(memory).execute(buildJsonObject { put("query", "чай") })

        assertTrue(out.success)
        assertEquals("зовут Иван\nлюбит кофе", memory.text)
    }

    @Test
    fun forget_noMatch_saysSo() = runTest {
        val out = MemoryForgetTool(FakeMemory("a")).execute(buildJsonObject { put("query", "zzz") })

        assertTrue(out.success)
        assertTrue(out.output.contains("zzz"))
    }

    @Test
    fun forget_blankQuery_refuses() = runTest {
        val out = MemoryForgetTool(FakeMemory("a")).execute(buildJsonObject { })

        assertEquals(false, out.success)
    }

    @Test
    fun risks_getAuto_putForgetAsk() {
        val memory = FakeMemory()
        assertEquals(ActionRisk.AUTO, MemoryGetTool(memory).risk)
        assertEquals(ActionRisk.ASK, MemoryPutTool(memory).risk)
        assertEquals(ActionRisk.ASK, MemoryForgetTool(memory).risk)
    }
}

class SkillToolsTest {

    private fun store() = FakeSkills(
        mutableListOf(
            SkillInfo("tea", "Чай", "Заваривай чай."),
            SkillInfo("off", "Выкл", "тело", enabled = false),
        ),
    )

    @Test
    fun list_onlyEnabled() = runTest {
        val out = SkillListTool(store()).execute(buildJsonObject { })

        assertTrue(out.success)
        assertTrue(out.output.contains("tea — Чай"))
        assertTrue(!out.output.contains("off"))
    }

    @Test
    fun list_empty_saysSo() = runTest {
        val out = SkillListTool(FakeSkills()).execute(buildJsonObject { })

        assertTrue(out.output.contains("нет включённых"))
    }

    @Test
    fun get_returnsBody() = runTest {
        val out = SkillGetTool(store()).execute(buildJsonObject { put("name", "tea") })

        assertTrue(out.success)
        assertEquals("Заваривай чай.", out.output)
    }

    @Test
    fun get_unknownOrDisabled_refuses() = runTest {
        val store = store()
        assertEquals(false, SkillGetTool(store).execute(buildJsonObject { put("name", "nope") }).success)
        assertEquals(
            false,
            SkillGetTool(store).execute(buildJsonObject { put("name", "off") }).success,
        )
    }
}
