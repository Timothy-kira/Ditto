package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerPromptDirectiveTest {

    @Test
    fun fromStorageValueParsesKnownDirectives() {
        assertEquals(ComposerPromptDirective.Goal, ComposerPromptDirective.fromStorageValue("goal"))
        assertEquals(ComposerPromptDirective.Swarm, ComposerPromptDirective.fromStorageValue(" Swarm "))
        assertEquals(ComposerPromptDirective.Tower, ComposerPromptDirective.fromStorageValue("tower"))
        assertNull(ComposerPromptDirective.fromStorageValue(""))
        assertNull(ComposerPromptDirective.fromStorageValue(null))
        assertNull(ComposerPromptDirective.fromStorageValue("plan"))
        assertNull(ComposerPromptDirective.fromStorageValue("/goal"))
    }

    @Test
    fun removeMentionTriggerTokenRemovesBareTrigger() {
        assertEquals("" to 0, removeMentionTriggerToken("@"))
    }

    @Test
    fun removeMentionTriggerTokenRemovesTriggerWithQuery() {
        assertEquals("" to 0, removeMentionTriggerToken("@附近"))
    }

    @Test
    fun removeMentionTriggerTokenKeepsLeadingTextAndCursor() {
        assertEquals("hello " to 6, removeMentionTriggerToken("hello @near"))
        assertEquals("hello " to 6, removeMentionTriggerToken("hello @"))
    }

    @Test
    fun removeMentionTriggerTokenIgnoresNonTriggerAt() {
        // '@' not preceded by whitespace is not a mention trigger.
        assertNull(removeMentionTriggerToken("a@b"))
        // whitespace after the query means the trigger is no longer active.
        assertNull(removeMentionTriggerToken("@near is great"))
        assertNull(removeMentionTriggerToken(""))
        assertNull(removeMentionTriggerToken("no mention"))
    }

    @Test
    fun removeMentionTriggerTokenRemovesOnlyLastTrigger() {
        assertEquals("@ " to 2, removeMentionTriggerToken("@ @x"))
    }

    @Test
    fun goalPrefixInstructsCreateGoalWithUserTextAsObjective() {
        val prefix = composerDirectivePromptPrefix(ComposerPromptDirective.Goal)
        assertTrue(prefix.contains("goal-intake"))
        assertTrue(prefix.contains("CreateGoal"))
        assertTrue(prefix.contains("objective"))
        assertTrue(prefix.endsWith("\n"))
        assertFalse(prefix.contains("AgentSwarm"))
    }

    @Test
    fun swarmPrefixBorrowsCliEnterReminder() {
        val prefix = composerDirectivePromptPrefix(ComposerPromptDirective.Swarm)
        assertTrue(prefix.contains("agent swarm"))
        assertTrue(prefix.contains("AgentSwarm"))
        assertTrue(prefix.contains("{{item}}"))
        assertTrue(prefix.contains("128"))
        assertTrue(prefix.endsWith("\n"))
        assertFalse(prefix.contains("CreateGoal"))
    }

    @Test
    fun towerPrefixInstructsControlTowerWorkflow() {
        val prefix = composerDirectivePromptPrefix(ComposerPromptDirective.Tower)
        assertTrue(prefix.contains("tower mode"))
        assertTrue(prefix.contains("TowerSpawn"))
        assertTrue(prefix.contains("TowerInit"))
        assertTrue(prefix.contains("never write product code"))
        assertTrue(prefix.contains("git"))
        assertTrue(prefix.endsWith("\n"))
        assertFalse(prefix.contains("AgentSwarm"))
        assertFalse(prefix.contains("CreateGoal"))
    }

    @Test
    fun directivePromptTextPrependsHiddenInstructions() {
        val composed = composerDirectivePromptText("goal", "每周跑步三次")
        assertTrue(composed.startsWith(composerDirectivePromptPrefix(ComposerPromptDirective.Goal)))
        assertTrue(composed.endsWith("每周跑步三次"))

        val swarm = composerDirectivePromptText("swarm", "整理这些文件")
        assertTrue(swarm.startsWith(composerDirectivePromptPrefix(ComposerPromptDirective.Swarm)))
        assertTrue(swarm.endsWith("整理这些文件"))
    }

    @Test
    fun directivePromptTextPassesThroughWithoutDirective() {
        assertEquals("plain text", composerDirectivePromptText("", "plain text"))
        assertEquals("plain text", composerDirectivePromptText("unknown", "plain text"))
    }
}
