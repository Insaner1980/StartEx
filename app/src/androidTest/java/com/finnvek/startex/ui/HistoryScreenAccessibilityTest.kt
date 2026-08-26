package com.finnvek.startex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.ui.screens.HistoryScreen
import com.finnvek.startex.ui.theme.StartExTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryScreenAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filtersHaveDistinctNamesAndKeepKeyboardFocusAfterSelection() {
        setHistoryContent(tradeHistory = listOf(trade()))
        val filterRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

        compose
            .onNode(hasText("All modes") and filterRole)
            .performScrollTo()
            .assertIsSelectable()
            .assertIsSelected()
        compose
            .onNode(hasText("All dates") and filterRole)
            .performScrollTo()
            .assertIsSelectable()
            .assertIsSelected()

        compose
            .onNode(hasText("PAPER") and filterRole)
            .performScrollTo()
            .requestFocus()
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
            .assertIsFocused()
            .assertIsSelected()
    }

    @Test
    fun historyCommunicatesLossUncertaintyDisabledReasonAndRecordHeading() {
        val mint = "Mint111111111111111111111111111111111111"
        setHistoryContent(
            demoMode = true,
            tradeHistory =
                listOf(
                    trade(
                        mint = mint,
                        side = "SELL",
                        expectedOutputAtomic = "95",
                        grossInputLamports = 100,
                    ),
                ),
        )

        val signedAmounts =
            compose
                .onAllNodes(hasText("SOL", substring = true))
                .fetchSemanticsNodes()
                .flatMap { node -> node.config[SemanticsProperties.Text] }
                .map { text -> text.text }
        assertTrue(
            signedAmounts.any { amount ->
                (amount.startsWith("-") || amount.startsWith("−")) &&
                    amount.contains("000000005") &&
                    amount.endsWith(" SOL")
            },
        )
        compose
            .onNodeWithText("Recorded totals from matching trades. Unavailable realized values are excluded.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText("ABC") and isHeading()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(mint).performScrollTo().assertIsDisplayed()

        val disabledReason = "History exports are unavailable in demo mode."
        compose
            .onNode(hasText("Export CSV") and hasStateDescription(disabledReason))
            .performScrollTo()
            .assertIsNotEnabled()
        compose
            .onNode(hasText("Export JSON") and hasStateDescription(disabledReason))
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithText(disabledReason).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(
                "Exports contain recorded candidates, snapshots, decisions, positions, trade intents, " +
                    "transactions and fees. Recovery phrases and API keys are never included.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun narrowRtlMaximumScaleKeepsExactIdentifierAndControlsUsable() {
        val mint = "M".repeat(44)
        // This scenario owns its constrained RTL composition.
        // CPD-OFF
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                StartExTheme {
                    Box(modifier = Modifier.width(320.dp).height(640.dp)) {
                        HistoryScreen(
                            state = PersistedAppState(loaded = true, tradeHistory = listOf(trade(mint = mint))),
                            onExportCsv = {},
                            onExportJson = {},
                        )
                    }
                }
            }
        }

        // CPD-ON
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(mint).performScrollTo().performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) {
            it(textLayouts)
        }
        assertFalse(textLayouts.single().hasVisualOverflow)

        val csv = compose.onNodeWithText("Export CSV").performScrollTo().assertIsDisplayed()
        val json = compose.onNodeWithText("Export JSON").performScrollTo().assertIsDisplayed()
        val minimumTarget = with(compose.density) { 48.dp.toPx() }
        val csvTarget = csv.fetchSemanticsNode().touchBoundsInRoot
        val jsonTarget = json.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(csvTarget.width >= minimumTarget && csvTarget.height >= minimumTarget)
        assertTrue(jsonTarget.width >= minimumTarget && jsonTarget.height >= minimumTarget)
        assertTrue(csvTarget.right <= jsonTarget.left || jsonTarget.right <= csvTarget.left)
        assertTrue(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size >= 3)
    }

    @Test
    fun landscapeMaximumScaleKeepsHistoryScrollable() {
        val mint = "L".repeat(44)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StartExTheme {
                    Box(modifier = Modifier.width(640.dp).height(320.dp)) {
                        HistoryScreen(
                            state = PersistedAppState(loaded = true, tradeHistory = listOf(trade(mint = mint))),
                            onExportCsv = {},
                            onExportJson = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(mint).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export CSV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export JSON").performScrollTo().assertIsDisplayed()
    }

    private fun setHistoryContent(
        demoMode: Boolean = false,
        tradeHistory: List<TradeExportRow>,
    ) {
        compose.setContent {
            StartExTheme {
                HistoryScreen(
                    state = PersistedAppState(loaded = true, demoMode = demoMode, tradeHistory = tradeHistory),
                    onExportCsv = {},
                    onExportJson = {},
                )
            }
        }
    }

    private fun trade(
        mint: String = "Mint111111111111111111111111111111111111",
        side: String = "BUY",
        expectedOutputAtomic: String = "1",
        grossInputLamports: Long? = null,
    ) = TradeExportRow(
        intentId = "trade-1",
        side = side,
        mint = mint,
        mode = "PAPER",
        symbol = "ABC",
        exitReason = null,
        openedAtMillis = null,
        closedAtMillis = grossInputLamports?.let { 1_786_276_800_000 },
        grossInputLamports = grossInputLamports,
        highestExecutableSellLamports = null,
        lowestExecutableSellLamports = null,
        strategyVersion = 2,
        entryScore = 81,
        decisionFactorsJson = null,
        rejectionCodesJson = null,
        transactionSignature = null,
        transactionStatus = "PAPER_FILLED",
        requestedInputAtomic = "1",
        expectedOutputAtomic = expectedOutputAtomic,
        actualInputAtomic = "1",
        actualOutputAtomic = "1",
        totalFeeLamports = 0,
        createdAtMillis = 1_786_276_800_000,
        confirmedAtMillis = null,
    )
}
