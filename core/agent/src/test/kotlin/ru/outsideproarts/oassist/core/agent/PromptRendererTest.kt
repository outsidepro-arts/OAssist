package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.outsideproarts.oassist.core.model.SkillInfo

class PromptRendererTest {

    private val template =
        "L={locale} T={time} D={device} M=[{memory}] S=[{skills}] C=[{screen_context}] H=[{shared_content}]"

    @Test
    fun render_substitutesAllPlaceholders() {
        val out = PromptRenderer.render(
            template,
            PromptInputs(
                locale = "ru-RU",
                time = "2026-10-04",
                device = "Pixel",
                memory = "любит чай",
                skills = listOf(SkillInfo("tea", "Чай", "Заваривай чай.")),
                screenContext = "экран",
                sharedContent = "контент",
            ),
        )

        assertEquals(
            "L=ru-RU T=2026-10-04 D=Pixel M=[любит чай] " +
                "S=[## tea — Чай\nЗаваривай чай.] C=[экран] H=[контент]",
            out,
        )
    }

    @Test
    fun render_nullsBecomeEmpty() {
        val out = PromptRenderer.render(template, PromptInputs())

        assertEquals("L= T= D= M=[] S=[] C=[] H=[]", out)
        assertFalse(out.contains("{"))
    }

    @Test
    fun memory_cappedWithMark() {
        val long = "x".repeat(PromptRenderer.MAX_MEMORY_CHARS + 100)

        val out = PromptRenderer.render("M=[{memory}]", PromptInputs(locale = "ru-RU", memory = long))

        assertTrue(out.startsWith("M=[xxx"))
        assertTrue(out.endsWith("…[обрезано]]"))
        assertTrue(out.length < long.length + 20)
    }

    @Test
    fun memory_mark_english() {
        val long = "x".repeat(PromptRenderer.MAX_MEMORY_CHARS + 1)

        val out = PromptRenderer.render("M=[{memory}]", PromptInputs(locale = "en-US", memory = long))

        assertTrue(out.endsWith("…[truncated]]"))
    }

    @Test
    fun skills_onlyEnabled_cappedPerBody() {
        val big = "y".repeat(PromptRenderer.MAX_SKILL_BODY_CHARS + 50)
        val skills = listOf(
            SkillInfo("off", "Выкл", "тело", enabled = false),
            SkillInfo("big", "Большой", big),
        )

        val out = PromptRenderer.skillsBlock(skills)

        assertFalse(out.contains("Выкл"))
        assertTrue(out.contains("## big — Большой"))
        assertTrue(out.contains("…[обрезано]"))
    }

    @Test
    fun skills_totalCap_marksRemainder() {
        val skills = (1..10).map { i ->
            SkillInfo("s$i", "T$i", "z".repeat(PromptRenderer.MAX_SKILL_BODY_CHARS))
        }

        val out = PromptRenderer.skillsBlock(skills)

        assertTrue(out.contains("## s1"))
        assertTrue(out.endsWith("…[обрезано]"))
        assertFalse(out.contains("## s10"))
    }

    @Test
    fun skills_empty_isEmpty() {
        assertEquals("", PromptRenderer.skillsBlock(emptyList()))
        assertEquals(
            "",
            PromptRenderer.skillsBlock(listOf(SkillInfo("x", "X", "y", enabled = false))),
        )
    }
}
