package kira.ditto.agentmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeWindowSelectionTest {
    @Test
    fun mutationSelectionNeverFallsBackToDefaultDisplay() {
        // strict=[] on a non-default VD: mutation sees nothing (no host leak).
        val selected = AgentModeUiAutomation.selectWindowsForMutation(
            displayId = 37,
            strict = emptyList(),
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun mutationSelectionUsesStrictSetWhenPresent() {
        val fake = listOf<android.view.accessibility.AccessibilityWindowInfo>()
        val selected = AgentModeUiAutomation.selectWindowsForMutation(
            displayId = 37,
            strict = fake,
        )
        assertEquals(fake, selected)
    }

    @Test
    fun readSelectionFallsBackOnlyOnDefaultDisplay() {
        val all = listOf<android.view.accessibility.AccessibilityWindowInfo>()
        val onDefault = AgentModeUiAutomation.selectWindowsForRead(
            displayId = android.view.Display.DEFAULT_DISPLAY,
            strict = emptyList(),
            allDisplays = all,
        )
        assertEquals(all, onDefault)
        val onVirtual = AgentModeUiAutomation.selectWindowsForRead(
            displayId = 42,
            strict = emptyList(),
            allDisplays = all,
        )
        assertTrue(onVirtual.isEmpty())
    }
}
