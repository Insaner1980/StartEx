package com.finnvek.startex.service

import com.finnvek.startex.data.local.BotSessionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPreflightRecoveryTest {
    @Test
    fun `failed initial preflight must revalidate fresh prerequisites before recovery`() {
        assertTrue(requiresFreshStartPrerequisites(null))
        assertTrue(requiresFreshStartPrerequisites(session("PREFLIGHT_FAILED")))
        assertFalse(requiresFreshStartPrerequisites(session("SERVICE_DESTROYED")))
    }

    private fun session(stopReason: String) =
        BotSessionEntity(
            id = "session",
            mode = "PAPER",
            status = "NEEDS_ATTENTION",
            strategyVersion = 1,
            riskVersion = 1,
            startedAtMillis = 1,
            stoppedAtMillis = 2,
            stopReason = stopReason,
            lastHeartbeatAtMillis = 2,
        )
}
