package kira.ditto.data

import java.util.concurrent.locks.ReentrantLock
import org.json.JSONObject

/**
 * One virtual display: GUI MCP calls must not overlap. Launch of another app
 * while a call is in flight returns [DisplayBusyCode] so AgentSwarm members
 * retry instead of waiting past the HTTP timeout or stealing the screen.
 */
internal class AgentModeDisplayGate(
    fair: Boolean = true,
) {
    private val lock = ReentrantLock(fair)

    fun runExclusive(
        treatContentionAsBusy: Boolean,
        block: () -> String,
    ): String {
        if (treatContentionAsBusy) {
            if (!lock.tryLock()) return displayBusyJson()
        } else {
            lock.lock()
        }
        try {
            return block()
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    fun isLocked(): Boolean = lock.isLocked

    companion object {
        const val DisplayBusyCode = "DISPLAY_BUSY"

        fun displayBusyJson(): String = JSONObject()
            .put("ok", false)
            .put("code", DisplayBusyCode)
            .put(
                "errmsg",
                "The virtual phone is still operating another call. Retry this segment after a " +
                    "short wait; do not assume the MCP backend is down, and do not tap the launcher.",
            )
            .put("consistent", "no")
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            .toString()
    }
}
