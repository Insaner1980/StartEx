package com.finnvek.startex.ui

import com.finnvek.startex.R
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.ui.screens.hasStaleSellQuote
import com.finnvek.startex.ui.screens.homeActionDisabledReason
import com.finnvek.startex.ui.screens.paperGrossPnlLamports
import com.finnvek.startex.ui.screens.sellQuoteAgeSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardContractTest {
    @Test
    fun `home projection retains balance progress and failure state`() {
        val home =
            PersistedAppState(
                walletBalanceLamports = 0,
                walletBalanceLoading = true,
                walletBalanceError = R.string.balance_refresh_failed,
            ).homeScreenState()

        assertEquals(0L, home.walletBalanceLamports)
        assertTrue(home.walletBalanceLoading)
        assertEquals(R.string.balance_refresh_failed, home.walletBalanceError)
    }

    @Test
    fun `quote freshness and gross pnl remain explicit`() {
        val position = position(latestSellQuoteLamports = 125, latestSellQuoteAtMillis = 900)

        assertFalse(position.hasStaleSellQuote(nowMillis = 1_000, maximumAgeMillis = 100))
        assertTrue(position.hasStaleSellQuote(nowMillis = 1_001, maximumAgeMillis = 100))
        assertEquals(25L, paperGrossPnlLamports(sellQuoteLamports = 125, netInputLamports = 100))
        assertEquals(0L, paperGrossPnlLamports(sellQuoteLamports = 100, netInputLamports = 100))
        assertNull(paperGrossPnlLamports(Long.MAX_VALUE, -1))
        assertEquals(0L, position.sellQuoteAgeSeconds(nowMillis = 1_000))
        assertEquals(1L, position.sellQuoteAgeSeconds(nowMillis = 2_000))
        assertNull(position.sellQuoteAgeSeconds(nowMillis = 899))
    }

    @Test
    fun `disabled home actions explain the active blocker`() {
        assertEquals(
            R.string.demo_action_unavailable,
            homeActionDisabledReason(true, MonitorState.Running, R.string.sell_now_unavailable),
        )
        assertEquals(
            R.string.monitoring_must_be_running,
            homeActionDisabledReason(false, MonitorState.Paused, R.string.sell_now_unavailable),
        )
        assertEquals(
            R.string.sell_now_unavailable,
            homeActionDisabledReason(false, MonitorState.Running, R.string.sell_now_unavailable),
        )
    }

    @Test
    fun `stop after close requires a running paper session with a protectable position`() {
        val paper = position()

        assertTrue(canRequestStopAfterClose(listOf(paper), MonitorState.Running, demoMode = false))
        assertFalse(canRequestStopAfterClose(listOf(paper), MonitorState.Paused, demoMode = false))
        assertFalse(canRequestStopAfterClose(listOf(paper), MonitorState.Stopped, demoMode = false))
        assertFalse(canRequestStopAfterClose(listOf(paper), MonitorState.Running, demoMode = true))
        assertFalse(
            canRequestStopAfterClose(
                listOf(paper.copy(mode = "LIVE")),
                MonitorState.Running,
                demoMode = false,
            ),
        )
    }

    private fun position(
        latestSellQuoteLamports: Long? = 125,
        latestSellQuoteAtMillis: Long? = 1_000,
    ) = PositionEntity(
        id = "position",
        sessionId = "session",
        mint = "mint",
        entryDecisionId = "decision",
        status = "OPEN",
        tokenAmountAtomic = "1",
        grossInputLamports = 101,
        netInputLamports = 100,
        latestSellQuoteLamports = latestSellQuoteLamports,
        entrySignature = null,
        exitSignature = null,
        openedAtMillis = 1,
        updatedAtMillis = 1,
        closedAtMillis = null,
        exitReason = null,
        mode = "PAPER",
        symbol = "TKN",
        name = "Token",
        tokenDecimals = 0,
        tokenProgram = "program",
        entryCostLamports = 1,
        exitRulesVersion = "1:1",
        exitRulesJson = "{}",
        latestSellQuoteAtMillis = latestSellQuoteAtMillis,
        highestExecutableSellLamports = latestSellQuoteLamports ?: 0,
        lowestExecutableSellLamports = latestSellQuoteLamports ?: 0,
        routeAvailable = latestSellQuoteLamports != null,
        reconciliationState = "RECONCILED",
    )
}
