package kira.ditto.ui

const val DefaultModelContextWindowTokens = 128_000L

fun resolveModelContextWindow(catalogWindow: Long?): Long =
    catalogWindow?.takeIf { it > 0L } ?: DefaultModelContextWindowTokens

fun conversationUsedContextTokens(
    lastTurnTotalTokens: Long?,
    pendingAssistantText: String = "",
): Long {
    val pendingEstimate = (pendingAssistantText.length + 3L) / 4L
    return (lastTurnTotalTokens ?: 0L).coerceAtLeast(0L) + pendingEstimate
}

fun modelContextUsageFraction(
    usedTokens: Long,
    contextWindowTokens: Long,
): Float {
    val window = contextWindowTokens.coerceAtLeast(1L)
    return (usedTokens.toFloat() / window.toFloat()).coerceIn(0f, 1f)
}

/**
 * Capsule occupancy: ACP `usage_update` when there is one, the local transcript estimate otherwise.
 *
 * While a turn is running the capsule should still grow, because the tokens being streamed are
 * really in the window. It grows by *adding the streamed text* to the ACP snapshot - which is what
 * [pendingTokens] is for.
 *
 * It used to take `maxOf(acp, localUsedTokens)` instead, and that was wrong in a way that only
 * showed up on cached turns: `localUsedTokens` carries the previous turn's billing total, which
 * counts `cacheRead` - so after a turn with a large cache hit it exceeded the true occupancy and
 * won the max. The capsule then read high for reasons that had nothing to do with how full the
 * window was. Occupancy and billing are different quantities; only one of them belongs here.
 */
fun capsuleContextUsageFraction(
    acpUsedTokens: Long?,
    acpWindowTokens: Long?,
    catalogWindow: Long?,
    localUsedTokens: Long,
    sessionRunning: Boolean = false,
    pendingTokens: Long = 0L,
): Float {
    val window = acpWindowTokens?.takeIf { it > 0L }
        ?: catalogWindow?.takeIf { it > 0L }
        ?: DefaultModelContextWindowTokens
    val acp = acpUsedTokens?.takeIf { it > 0L }
    val used = when {
        acp != null && sessionRunning -> acp + pendingTokens.coerceAtLeast(0L)
        acp != null -> acp
        else -> localUsedTokens
    }
    return modelContextUsageFraction(used, window)
}
