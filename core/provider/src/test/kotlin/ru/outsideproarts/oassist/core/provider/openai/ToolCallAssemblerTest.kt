package ru.outsideproarts.oassist.core.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallAssemblerTest {

    private fun fragment(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ) = ToolCallDeltaDto(
        index = index,
        id = id,
        function = if (name == null && arguments == null) null
        else ToolCallFunctionDto(name = name, arguments = arguments),
    )

    @Test
    fun singleCall_assemblesFragments() {
        val assembler = ToolCallAssembler()
        assembler.feed(fragment(0, id = "call_abc", name = "web_search", arguments = "{\"qu"))
        assembler.feed(fragment(0, arguments = "ery\":\"погода\"}"))

        val calls = assembler.build()

        assertEquals(1, calls.size)
        assertEquals("call_abc", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("{\"query\":\"погода\"}", calls[0].argumentsJson)
        assertEquals(0, calls[0].index)
    }

    @Test
    fun parallelCalls_keepsIndexOrder() {
        val assembler = ToolCallAssembler()
        assembler.feed(fragment(0, id = "call_1", name = "alarm_set", arguments = "{\"ho"))
        assembler.feed(fragment(1, id = "call_2", name = "timer_set", arguments = "{\"se"))
        assembler.feed(fragment(0, arguments = "ur\":7}"))
        assembler.feed(fragment(1, arguments = "conds\":60}"))

        val calls = assembler.build()

        assertEquals(2, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("{\"hour\":7}", calls[0].argumentsJson)
        assertEquals("call_2", calls[1].id)
        assertEquals("{\"seconds\":60}", calls[1].argumentsJson)
    }

    @Test
    fun emptyArguments_defaultsToEmptyObject() {
        val assembler = ToolCallAssembler()
        assembler.feed(fragment(0, id = "call_x", name = "device_info"))

        val calls = assembler.build()

        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].argumentsJson)
    }

    @Test
    fun missingId_fallsBackToCallIndex() {
        val assembler = ToolCallAssembler()
        assembler.feed(fragment(2, name = "ui_back", arguments = "{}"))

        val calls = assembler.build()

        assertEquals(1, calls.size)
        assertEquals("call_2", calls[0].id)
    }

    @Test
    fun namelessCall_emittedForFailClosed() {
        val assembler = ToolCallAssembler()
        assembler.feed(fragment(0, id = "call_ghost", arguments = "{\"a\":1}"))

        val calls = assembler.build()

        // Пустое имя — дальше реестр не найдёт тул и включит ASK + диалог, а не повиснет.
        assertEquals(1, calls.size)
        assertEquals("", calls[0].name)
        assertEquals("{\"a\":1}", calls[0].argumentsJson)
    }
}
