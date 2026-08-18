package com.finnvek.startex.ui

import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.freshSellQuoteLamports
import com.finnvek.startex.ui.screens.HistoryDateFilter
import com.finnvek.startex.ui.screens.HistoryModeFilter
import com.finnvek.startex.ui.screens.configurationInputs
import com.finnvek.startex.ui.screens.filterTradeHistory
import com.finnvek.startex.ui.screens.summarizeDailyPerformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryAndConfigurationUiTest {
    @Test
    fun historyFiltersUseActualModeAndUtcDate() {
        val today = LocalDate.of(2026, 8, 9)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rows =
            listOf(
                trade("paper-today", DailyPerformanceEntity.MODE_PAPER, today),
                trade("live-week", DailyPerformanceEntity.MODE_LIVE, today.minusDays(6)),
                trade("paper-old", DailyPerformanceEntity.MODE_PAPER, today.minusDays(7)),
            )

        assertEquals(
            listOf("paper-today"),
            filterTradeHistory(
                rows,
                HistoryModeFilter.Paper,
                HistoryDateFilter.SevenDays,
                now,
            ).map(TradeExportRow::intentId),
        )
        assertEquals(
            listOf("paper-today", "live-week"),
            filterTradeHistory(
                rows,
                HistoryModeFilter.All,
                HistoryDateFilter.SevenDays,
                now,
            ).map(TradeExportRow::intentId),
        )
    }

    @Test
    fun performanceSummaryAddsPersistedRowsExactly() {
        val today = LocalDate.of(2026, 8, 9)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val summary =
            summarizeDailyPerformance(
                rows =
                    listOf(
                        performance(
                            today,
                            DailyPerformanceEntity.MODE_PAPER,
                            net = 20,
                            fees = 3,
                            wins = 2,
                            losses = 1,
                        ),
                        performance(
                            today.minusDays(1),
                            DailyPerformanceEntity.MODE_PAPER,
                            net = -5,
                            fees = 2,
                            wins = 0,
                            losses = 1,
                        ),
                        performance(
                            today,
                            DailyPerformanceEntity.MODE_LIVE,
                            net = 100,
                            fees = 9,
                            wins = 9,
                            losses = 0,
                        ),
                    ),
                modeFilter = HistoryModeFilter.Paper,
                dateFilter = HistoryDateFilter.SevenDays,
                nowMillis = now,
            )

        requireNotNull(summary)
        assertEquals(BigInteger.valueOf(15), summary.netPnlLamports)
        assertEquals(BigInteger.valueOf(5), summary.totalFeesLamports)
        assertEquals(2L, summary.wins)
        assertEquals(2L, summary.losses)
    }

    @Test
    fun configurationInputsUseExactDecimalStrings() {
        val inputs =
            configurationInputs(
                DefaultConfiguration.risk(createdAtMillis = 1),
                DefaultConfiguration.strategy(createdAtMillis = 1),
            )

        assertEquals("0.01", inputs.risk.maximumTradeSol)
        assertEquals("3", inputs.risk.maximumSlippagePercent)
        assertEquals("25", inputs.strategy.takeProfitPercent)
    }

    @Test
    fun sellNowGateRequiresOpenPaperPositionAndBlocksDemo() {
        val paper = position(mode = "PAPER", status = "OPEN")

        assertTrue(canRequestSellNow(paper, demoMode = false))
        assertFalse(canRequestSellNow(paper, demoMode = true))
        assertFalse(canRequestSellNow(position(mode = "LIVE", status = "OPEN"), demoMode = false))
        assertFalse(canRequestSellNow(position(mode = "PAPER", status = "EXIT_REQUESTED"), demoMode = false))
    }

    @Test
    fun emergencyExitGateRequiresProtectablePaperPositionAndBlocksDemo() {
        val paper = position(mode = "PAPER", status = "EXIT_BLOCKED")

        assertTrue(canRequestEmergencyExit(listOf(paper), demoMode = false))
        assertFalse(canRequestEmergencyExit(listOf(paper), demoMode = true))
        assertFalse(
            canRequestEmergencyExit(
                listOf(position(mode = "LIVE", status = "OPEN")),
                demoMode = false,
            ),
        )
        assertFalse(canRequestEmergencyExit(emptyList(), demoMode = false))
    }

    @Test
    fun executableQuoteRequiresCurrentAvailableRoute() {
        val fresh = position(mode = "PAPER", status = "OPEN").copy(latestSellQuoteAtMillis = 900)

        assertEquals(1L, fresh.freshSellQuoteLamports(nowMillis = 1_000, maximumAgeMillis = 100))
        assertNull(fresh.freshSellQuoteLamports(nowMillis = 1_001, maximumAgeMillis = 100))
        assertNull(fresh.copy(routeAvailable = false).freshSellQuoteLamports(1_000, 100))
        assertNull(fresh.copy(latestSellQuoteAtMillis = 1_001).freshSellQuoteLamports(1_000, 100))
    }

    private fun trade(
        id: String,
        mode: String,
        date: LocalDate,
    ) = TradeExportRow(
        intentId = id,
        side = "BUY",
        mint = "mint-$id",
        mode = mode,
        symbol = null,
        exitReason = null,
        openedAtMillis = null,
        closedAtMillis = null,
        highestExecutableSellLamports = null,
        lowestExecutableSellLamports = null,
        strategyVersion = 1,
        entryScore = 75,
        decisionFactorsJson = null,
        rejectionCodesJson = null,
        transactionSignature = null,
        transactionStatus = "PAPER_FILLED",
        requestedInputAtomic = "1",
        expectedOutputAtomic = "1",
        actualInputAtomic = "1",
        actualOutputAtomic = "1",
        totalFeeLamports = 0,
        createdAtMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        confirmedAtMillis = null,
    )

    private fun performance(
        date: LocalDate,
        mode: String,
        net: Long,
        fees: Long,
        wins: Int,
        losses: Int,
    ) = DailyPerformanceEntity(
        epochDay = date.toEpochDay(),
        mode = mode,
        grossPnlLamports = net + fees,
        netPnlLamports = net,
        totalFeesLamports = fees,
        tradeCount = wins + losses,
        winCount = wins,
        lossCount = losses,
        consecutiveLosses = losses,
        updatedAtMillis = 1,
    )

    private fun position(
        mode: String,
        status: String,
    ) = PositionEntity(
        id = "position",
        sessionId = "session",
        mint = "mint",
        entryDecisionId = "decision",
        status = status,
        tokenAmountAtomic = "1",
        grossInputLamports = 1,
        netInputLamports = 1,
        latestSellQuoteLamports = 1,
        entrySignature = null,
        exitSignature = null,
        openedAtMillis = 1,
        updatedAtMillis = 1,
        closedAtMillis = null,
        exitReason = null,
        mode = mode,
        symbol = "TKN",
        name = "Token",
        tokenDecimals = 0,
        tokenProgram = "program",
        entryCostLamports = 1,
        exitRulesVersion = "v1",
        exitRulesJson = "{}",
        latestSellQuoteAtMillis = 1,
        highestExecutableSellLamports = 1,
        lowestExecutableSellLamports = 1,
        routeAvailable = true,
        reconciliationState = "CONFIRMED",
    )
}
