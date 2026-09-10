package kira.ditto.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AetherSelfManagementToolRoutingTest {
    @Test
    fun scheduledTaskToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_scheduled_task_manage"))
    }

    @Test
    fun piExtensionToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_extension_manage"))
    }
}
