package com.finnvek.startex.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.ui.screens.HomeScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Rule
import org.junit.Test

class HomeDashboardAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeExposesExactAddressQuoteAgeAndDisabledReason() {
        val address = "9xQeWvG816bUx9EPfEZCDEYvZXsKJe6PyKwNW5wZnWGD"
        val observedAtMillis = System.currentTimeMillis()
        compose.setContent {
            StartExTheme {
                HomeScreen(
                    state =
                        HomeScreenState(
                            demoMode = false,
                            mode = TradingMode.Paper,
                            walletAddress = address,
                            walletBalanceLamports = null,
                            walletBalanceEur = null,
                            walletBalanceLoading = false,
                            walletBalanceError = null,
                            dailyPerformance = emptyList(),
                            monitorState = MonitorState.Paused,
                            openPositions = listOf(position(observedAtMillis)),
                            risk = null,
                        ),
                    onPreflight = {},
                    onRecover = {},
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onSellNow = {},
                    onEmergencyExit = {},
                    onStopAfterClose = {},
                )
            }
        }

        compose.onNode(hasContentDescription(address)).assertExists()
        compose.onNode(hasText("Quote observed")).assertExists()
        compose
            .onNode(hasText("Sell now") and hasStateDescription("Available only while monitoring is running."))
            .assertIsNotEnabled()
    }

    // This instrumented fixture intentionally mirrors the JVM contract fixture.
    // CPD-OFF
    private fun position(observedAtMillis: Long) =
        PositionEntity(
            id = "position",
            sessionId = "session",
            mint = "mint",
            entryDecisionId = "decision",
            status = "OPEN",
            tokenAmountAtomic = "1",
            grossInputLamports = 101,
            netInputLamports = 100,
            latestSellQuoteLamports = 125,
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
            latestSellQuoteAtMillis = observedAtMillis,
            highestExecutableSellLamports = 125,
            lowestExecutableSellLamports = 125,
            routeAvailable = true,
            reconciliationState = "RECONCILED",
        )
    // CPD-ON
}
