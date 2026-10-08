package ru.outsideproarts.oassist.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AskPresetTest {

    @Test
    fun `дефолтный пресет спрашивает и то и другое`() {
        assertEquals(AskPreset.ASK_BOTH, AskPreset.DEFAULT)
        assertEquals(ApprovalState.ASK, AskPreset.ASK_BOTH.stateFor(ToolAccess.READ))
        assertEquals(ApprovalState.ASK, AskPreset.ASK_BOTH.stateFor(ToolAccess.WRITE))
    }

    @Test
    fun `Спрашивать на чтение разрешает запись`() {
        assertEquals(ApprovalState.ASK, AskPreset.ASK_READ.stateFor(ToolAccess.READ))
        assertEquals(ApprovalState.ALLOW, AskPreset.ASK_READ.stateFor(ToolAccess.WRITE))
    }

    @Test
    fun `Спрашивать на запись разрешает чтение`() {
        assertEquals(ApprovalState.ALLOW, AskPreset.ASK_WRITE.stateFor(ToolAccess.READ))
        assertEquals(ApprovalState.ASK, AskPreset.ASK_WRITE.stateFor(ToolAccess.WRITE))
    }

    @Test
    fun `MCP подчиняется только явным пресетам`() {
        AskPreset.entries.filter { it != AskPreset.ALLOW_ALL && it != AskPreset.DENY_ALL }.forEach { preset ->
            assertEquals("пресет $preset", ApprovalState.ASK, preset.stateFor(null))
        }
        assertEquals(ApprovalState.ALLOW, AskPreset.ALLOW_ALL.stateFor(null))
        assertEquals(ApprovalState.DENY, AskPreset.DENY_ALL.stateFor(null))
    }
}
