package kira.ditto.data

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiSignalStoreTest {
    @Test
    fun appendPersistsSignalsAsJsonlAndDistribution() {
        val dir = Files.createTempDirectory("gui-signals").toFile()
        try {
            val store = GuiSignalStore(dir)
            store.append(
                attemptId = "att-1",
                signal = AgentModeSafety.MacroMismatchCode,
                where = "phone_app/search_xhs",
                packageName = "com.xingin.xhs",
                snapshotId = "snap-1",
                sopId = "sop-1",
                repairStrategy = "repair_from_current_page",
            )
            store.appendReceipt(
                attemptId = "att-1",
                where = "agent_display/tap",
                result = JSONObject()
                    .put("ok", false)
                    .put("code", AgentModeSafety.StalledCode)
                    .put("package_name", "com.xingin.xhs")
                    .put("snapshot_id", "snap-2")
                    .put(
                        "failure",
                        JSONObject().put("repair_strategy", "change_input_method"),
                    ),
            )
            val recent = store.recent()
            assertEquals(2, recent.size)
            assertEquals(AgentModeSafety.StalledCode, recent[0].signal)
            assertEquals("change_input_method", recent[0].repairStrategy)
            assertEquals(AgentModeSafety.MacroMismatchCode, recent[1].signal)
            assertEquals("sop-1", recent[1].sopId)
            val dist = store.distribution()
            assertEquals(1, dist[AgentModeSafety.MacroMismatchCode])
            assertEquals(1, dist[AgentModeSafety.StalledCode])
            val file = dir.resolve(GuiSignalStore.SignalsFileName)
            assertTrue(file.isFile)
            assertEquals(2, file.readLines().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun receiptWithoutFailureCodeIsIgnored() {
        val dir = Files.createTempDirectory("gui-signals-ok").toFile()
        try {
            val store = GuiSignalStore(dir)
            store.appendReceipt(
                attemptId = "att-1",
                where = "agent_display/tap",
                result = JSONObject().put("ok", true),
            )
            assertTrue(store.recent().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
