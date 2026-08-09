package com.finnvek.startex.service

import com.finnvek.startex.StartExApplication
import com.finnvek.startex.data.local.PositionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNotificationPolicyTest {
    private val policy = ServiceNotificationPolicy()

    @Test
    fun `candidate rejections do not generate notification spam`() {
        assertNull(policy.alert("INFO", "CANDIDATE", "CANDIDATE_REJECTED", "mint"))
        assertNull(policy.alert("WARN", "CANDIDATE", "OBSERVATION_QUEUE_FULL", "mint"))
    }

    @Test
    fun `trade and safety events use the expected channels`() {
        assertEquals(
            StartExApplication.CHANNEL_TRADES,
            policy.alert("INFO", "PAPER", "PAPER_POSITION_OPENED", "position")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_CRITICAL,
            policy.alert("ERROR", "PAPER", "PAPER_EXIT_BLOCKED", "position")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_CRITICAL,
            policy.alert("ERROR", "RECOVERY", "RECOVERY_AUTHENTICATION_REQUIRED", "session")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_PROVIDER,
            policy.alert("ERROR", "PROVIDER", "PROVIDER_UNAVAILABLE", "HELIUS")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_TRADES,
            policy.alert("INFO", "WALLET", "MANUAL_TRANSFER_CONFIRMED", "transfer")?.channelId,
        )
    }

    @Test
    fun `notification ids are stable per event subject and distinct between subjects`() {
        val first = requireNotNull(policy.alert("INFO", "PAPER", "PAPER_POSITION_OPENED", "one"))
        val repeated = requireNotNull(policy.alert("INFO", "PAPER", "PAPER_POSITION_OPENED", "one"))
        val other = requireNotNull(policy.alert("INFO", "PAPER", "PAPER_POSITION_OPENED", "two"))

        assertEquals(first.notificationId, repeated.notificationId)
        assertTrue(first.notificationId != other.notificationId)
    }

    @Test
    fun `foreground model reports protection counts pnl and market age without identifiers`() {
        val model =
            ForegroundNotificationModel(
                mode = "PAPER",
                sessionStatus = "PROTECTING",
                protectingOnly = true,
                openPositionCount = 2,
                sessionPnlLamports = 25_000_000,
                lastMarketSuccessAtMillis = 40_000,
                nowMillis = 100_000,
            )

        assertEquals(ForegroundStatus.PROTECTING_POSITION, model.status)
        assertEquals("+0.025 SOL", model.pnlText)
        assertEquals("1m ago", model.marketAgeText)
    }

    @Test
    fun `foreground status is fail closed and paused status wins`() {
        assertEquals(
            ForegroundStatus.NEEDS_ATTENTION,
            ForegroundNotificationModel(
                mode = "PAPER",
                sessionStatus = "NEEDS_ATTENTION",
                protectingOnly = false,
                openPositionCount = 0,
                sessionPnlLamports = null,
                lastMarketSuccessAtMillis = null,
                nowMillis = 10,
            ).status,
        )
        assertEquals(
            ForegroundStatus.PAUSED,
            ForegroundNotificationModel(
                mode = "LIVE",
                sessionStatus = "PAUSED",
                protectingOnly = true,
                openPositionCount = 1,
                sessionPnlLamports = null,
                lastMarketSuccessAtMillis = null,
                nowMillis = 10,
            ).status,
        )
    }

    @Test
    fun `emergency exit selects only current paper session positions`() {
        val open = position(id = "open", sessionId = "current", mode = "PAPER", status = "OPEN")
        val live = position(id = "live", sessionId = "current", mode = "LIVE", status = "OPEN")
        val other = position(id = "other", sessionId = "other", mode = "PAPER", status = "OPEN")

        val updates = paperEmergencyExitUpdates(listOf(open, live, other), "current", 123)

        assertEquals(listOf("open"), updates.map(PositionEntity::id))
        assertEquals("EXIT_REQUESTED", updates.single().status)
        assertEquals("EMERGENCY_EXIT", updates.single().exitReason)
        assertEquals(123L, updates.single().updatedAtMillis)
    }

    private fun position(
        id: String,
        sessionId: String,
        mode: String,
        status: String,
    ) = PositionEntity(
        id = id,
        sessionId = sessionId,
        mint = "mint-$id",
        entryDecisionId = "decision-$id",
        status = status,
        tokenAmountAtomic = "1",
        grossInputLamports = 10,
        netInputLamports = 10,
        latestSellQuoteLamports = 11,
        entrySignature = null,
        exitSignature = null,
        openedAtMillis = 1,
        updatedAtMillis = 1,
        closedAtMillis = null,
        exitReason = null,
        mode = mode,
        symbol = "TOK",
        name = "Token",
        tokenDecimals = 6,
        tokenProgram = "program",
        entryCostLamports = 0,
        exitRulesVersion = "1",
        exitRulesJson = "{}",
        latestSellQuoteAtMillis = 1,
        highestExecutableSellLamports = 11,
        lowestExecutableSellLamports = 10,
        routeAvailable = true,
        reconciliationState = "PAPER",
    )
}
