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
    fun `important state changes use noticeable channels`() {
        assertEquals(
            StartExApplication.CHANNEL_BOT_STATUS,
            policy.alert("INFO", "SESSION", "MONITORING_STARTED", "session")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_BOT_STATUS,
            policy.alert("INFO", "SESSION", "USER_REQUESTED", "session")?.channelId,
        )
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
            policy.alert("WARNING", "WALLET", "MANUAL_TRANSFER_UNCERTAIN", "transfer")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_TRADES,
            policy.alert("INFO", "WALLET", "MANUAL_TRANSFER_CONFIRMED", "transfer")?.channelId,
        )
        assertEquals(
            StartExApplication.CHANNEL_CRITICAL,
            policy.alert("WARN", "RISK", "CIRCUIT_BREAKER_ACTIVE", null)?.channelId,
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
        assertEquals(60L, model.marketAgeSeconds)
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
    fun `session pnl requires a fresh quote for every open position`() {
        val first = position(id = "one", sessionId = "session", mode = "PAPER", status = "OPEN")
        val second = position(id = "two", sessionId = "session", mode = "PAPER", status = "OPEN")
        val now = 1_000L

        assertEquals(
            2L,
            sessionPnlLamports(
                listOf(
                    first.copy(latestSellQuoteAtMillis = now),
                    second.copy(latestSellQuoteAtMillis = now),
                ),
                nowMillis = now,
                maximumQuoteAgeMillis = 100,
            ),
        )
        assertNull(
            sessionPnlLamports(
                listOf(
                    first.copy(latestSellQuoteAtMillis = now),
                    second.copy(latestSellQuoteAtMillis = now - 101),
                ),
                nowMillis = now,
                maximumQuoteAgeMillis = 100,
            ),
        )
    }

    // CPD-OFF
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
    // CPD-ON
}
