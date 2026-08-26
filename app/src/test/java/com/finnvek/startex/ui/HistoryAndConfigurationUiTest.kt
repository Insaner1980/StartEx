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
import com.finnvek.startex.ui.screens.millisUntilNextUtcDay
import com.finnvek.startex.ui.screens.summarizeTradeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
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
    fun historyUsesUtcDayBoundariesAcrossADaylightSavingChange() {
        val now = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli()
        val today = LocalDate.of(2026, 3, 29)
        val rows =
            listOf(
                trade("utc-today", DailyPerformanceEntity.MODE_PAPER, today).copy(createdAtMillis = now),
                trade("utc-yesterday", DailyPerformanceEntity.MODE_PAPER, today.minusDays(1)).copy(
                    createdAtMillis = Instant.parse("2026-03-28T23:59:59.999Z").toEpochMilli(),
                ),
            )

        assertEquals(
            listOf("utc-today"),
            filterTradeHistory(
                rows,
                HistoryModeFilter.All,
                HistoryDateFilter.Today,
                now,
            ).map(TradeExportRow::intentId),
        )
        assertEquals(84_600_000L, millisUntilNextUtcDay(now))
    }

    @Test
    fun performanceSummaryUsesTheSameFilteredTradeRows() {
        val today = LocalDate.of(2026, 8, 9)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rows =
            listOf(
                trade("paper-buy", DailyPerformanceEntity.MODE_PAPER, today, feeLamports = 3),
                trade(
                    "paper-win",
                    DailyPerformanceEntity.MODE_PAPER,
                    today,
                    side = "SELL",
                    outputAtomic = "120",
                    grossInputLamports = 100,
                    feeLamports = 2,
                ),
                trade(
                    "paper-loss",
                    DailyPerformanceEntity.MODE_PAPER,
                    today.minusDays(1),
                    side = "SELL",
                    outputAtomic = "95",
                    grossInputLamports = 100,
                ),
                trade(
                    "live-win",
                    DailyPerformanceEntity.MODE_LIVE,
                    today,
                    side = "SELL",
                    outputAtomic = "200",
                    actualOutputAtomic = "200",
                    grossInputLamports = 100,
                    feeLamports = 9,
                ),
            )
        val filtered =
            filterTradeHistory(rows, HistoryModeFilter.Paper, HistoryDateFilter.SevenDays, now)
        val summary = summarizeTradeHistory(filtered)

        requireNotNull(summary)
        assertEquals(BigInteger.valueOf(15), summary.netPnlLamports)
        assertEquals(BigInteger.valueOf(5), summary.totalFeesLamports)
        assertEquals(1L, summary.wins)
        assertEquals(1L, summary.losses)
    }

    @Test
    fun historyBoundsRowsBeforeFilteringAndAggregating() {
        val today = LocalDate.of(2026, 8, 9)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rows =
            List(250) { index ->
                trade("paper-$index", DailyPerformanceEntity.MODE_PAPER, today, feeLamports = 1)
            } +
                trade(
                    "older-live",
                    DailyPerformanceEntity.MODE_LIVE,
                    today.minusDays(1),
                    side = "SELL",
                    outputAtomic = "200",
                    actualOutputAtomic = "200",
                    grossInputLamports = 100,
                    feeLamports = 99,
                )

        val allRows = filterTradeHistory(rows, HistoryModeFilter.All, HistoryDateFilter.All, now)
        assertEquals(250, allRows.size)
        assertEquals(BigInteger.valueOf(250), summarizeTradeHistory(allRows)?.totalFeesLamports)
        assertTrue(filterTradeHistory(rows, HistoryModeFilter.Live, HistoryDateFilter.All, now).isEmpty())
    }

    @Test
    fun liveSummaryDoesNotUseExpectedOutputWhenActualOutputIsMissing() {
        val today = LocalDate.of(2026, 8, 9)
        val live =
            trade(
                "live",
                DailyPerformanceEntity.MODE_LIVE,
                today,
                side = "SELL",
                outputAtomic = "999",
                actualOutputAtomic = null,
                grossInputLamports = 100,
                feeLamports = 4,
            )

        val summary = requireNotNull(summarizeTradeHistory(listOf(live)))
        assertEquals(BigInteger.ZERO, summary.netPnlLamports)
        assertEquals(BigInteger.valueOf(4), summary.totalFeesLamports)
        assertEquals(0L, summary.wins)
        assertEquals(0L, summary.losses)
    }

    @Test
    fun performanceSummaryKeepsVeryLargeAmountsExact() {
        val today = LocalDate.of(2026, 8, 9)
        val outputAtomic = "1000000000000000000000000000000"
        val rows =
            listOf(
                trade(
                    "large-sell",
                    DailyPerformanceEntity.MODE_PAPER,
                    today,
                    side = "SELL",
                    outputAtomic = outputAtomic,
                    grossInputLamports = Long.MAX_VALUE,
                    feeLamports = Long.MAX_VALUE,
                ),
                trade(
                    "large-fee",
                    DailyPerformanceEntity.MODE_PAPER,
                    today,
                    feeLamports = Long.MAX_VALUE,
                ),
            )

        val summary = requireNotNull(summarizeTradeHistory(rows))

        assertEquals(BigInteger(outputAtomic).subtract(BigInteger.valueOf(Long.MAX_VALUE)), summary.netPnlLamports)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), summary.totalFeesLamports)
        assertEquals(1L, summary.wins)
        assertEquals(0L, summary.losses)
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

        assertTrue(canRequestSellNow(paper, MonitorState.Running, demoMode = false))
        assertFalse(canRequestSellNow(paper, MonitorState.Paused, demoMode = false))
        assertFalse(canRequestSellNow(paper, MonitorState.Running, demoMode = true))
        assertFalse(canRequestSellNow(position(mode = "LIVE", status = "OPEN"), MonitorState.Running, demoMode = false))
        assertFalse(
            canRequestSellNow(
                position(mode = "PAPER", status = "EXIT_REQUESTED"),
                MonitorState.Running,
                demoMode = false,
            ),
        )
    }

    @Test
    fun emergencyExitGateRequiresProtectablePaperPositionAndBlocksDemo() {
        val paper = position(mode = "PAPER", status = "EXIT_BLOCKED")

        assertTrue(canRequestEmergencyExit(listOf(paper), MonitorState.Running, demoMode = false))
        assertFalse(canRequestEmergencyExit(listOf(paper), MonitorState.Paused, demoMode = false))
        assertFalse(canRequestEmergencyExit(listOf(paper), MonitorState.Running, demoMode = true))
        assertFalse(
            canRequestEmergencyExit(
                listOf(position(mode = "LIVE", status = "OPEN")),
                MonitorState.Running,
                demoMode = false,
            ),
        )
        assertFalse(canRequestEmergencyExit(emptyList(), MonitorState.Running, demoMode = false))
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
        side: String = "BUY",
        outputAtomic: String = "1",
        actualOutputAtomic: String? = if (mode == DailyPerformanceEntity.MODE_LIVE) outputAtomic else null,
        grossInputLamports: Long? = null,
        feeLamports: Long = 0,
    ) = TradeExportRow(
        intentId = id,
        side = side,
        mint = "mint-$id",
        mode = mode,
        symbol = null,
        exitReason = null,
        openedAtMillis = null,
        closedAtMillis = grossInputLamports?.let { date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
        grossInputLamports = grossInputLamports,
        highestExecutableSellLamports = null,
        lowestExecutableSellLamports = null,
        strategyVersion = 1,
        entryScore = 75,
        decisionFactorsJson = null,
        rejectionCodesJson = null,
        transactionSignature = null,
        transactionStatus = "PAPER_FILLED",
        requestedInputAtomic = "1",
        expectedOutputAtomic = outputAtomic,
        actualInputAtomic = "1",
        actualOutputAtomic = actualOutputAtomic,
        totalFeeLamports = feeLamports,
        createdAtMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        confirmedAtMillis = null,
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
