package kira.ditto.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiTurnWatchdogTest {
    @Test
    fun liveTerminalExemptsSilence() {
        assertTrue(
            kimiTurnWatchdogIsExempt(
                pendingInteraction = false,
                inFlightTools = false,
                guiTeaching = false,
                activeTerminals = true,
            ),
        )
    }

    @Test
    fun nestedToolsAndTeachingStillExempt() {
        assertTrue(
            kimiTurnWatchdogIsExempt(
                pendingInteraction = false,
                inFlightTools = true,
                guiTeaching = false,
                activeTerminals = false,
            ),
        )
        assertTrue(
            kimiTurnWatchdogIsExempt(
                pendingInteraction = true,
                inFlightTools = false,
                guiTeaching = false,
                activeTerminals = false,
            ),
        )
        assertTrue(
            kimiTurnWatchdogIsExempt(
                pendingInteraction = false,
                inFlightTools = false,
                guiTeaching = true,
                activeTerminals = false,
            ),
        )
    }

    @Test
    fun pureSilenceIsNotExempt() {
        assertFalse(
            kimiTurnWatchdogIsExempt(
                pendingInteraction = false,
                inFlightTools = false,
                guiTeaching = false,
                activeTerminals = false,
            ),
        )
    }
}
